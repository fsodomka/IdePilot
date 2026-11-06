package com.fsodomka.idepilot;

import com.intellij.ide.DataManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.impl.CoreProgressManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/** Running actions by ID, and waiting for the UI or indexing. */
final class IdeActions {
	private static final String PLACE = "IdePilot";
	private static final int SUGGESTIONS = 15;
	private static final long SMART_SETTLE_NANOS = 1_500_000_000L;
	/** The project directories pilot_open is opening, each until its opening returns; smart waits for them. */
	private static final List<Path> opening = new CopyOnWriteArrayList<>();

	private IdeActions() {
	}

	/** Runs action {@code id} in the context of {@code ref}, or of the focused component. */
	static String run(String id, String ref) {
		ActionManager manager = ActionManager.getInstance();
		AnAction action = manager.getAction(id);
		if (action == null) {
			String needle = id.toLowerCase(Locale.ROOT);
			List<String> similar = manager.getActionIdList("").stream()
					.filter(candidate -> candidate.toLowerCase(Locale.ROOT).contains(needle)).sorted().limit(SUGGESTIONS).toList();
			throw new IllegalArgumentException("No action " + id + (similar.isEmpty() ? "" : "; similar: " + String.join(", ", similar)));
		}
		Component target = ref == null ? null : UiSnapshot.component(ref);
		// later, not and-wait: the action may open a modal dialog, which would block this request
		Component context = target != null ? target : KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		// the modality of the context's dialog: write-safe, yet runs while that dialog is open
		ApplicationManager.getApplication().invokeLater(
				// performAction skips update(), as invokeAction did; tryToExecute would not run ExpandAll on a collapsed tree
				() -> ActionUtil.performAction(action, AnActionEvent.createEvent(DataManager.getInstance().getDataContext(context),
						action.getTemplatePresentation().clone(), PLACE, ActionUiKind.NONE, null)),
				context == null ? ModalityState.nonModal() : ModalityState.stateForComponent(context));
		return "Ran " + id + (ref == null ? "" : " on " + ref);
	}

	/**
	 * Opens a directory as a project, or a file in the editor of the open project that contains it (else the first open
	 * project), at {@code line} when given. Returns at once; opening finishes later.
	 */
	static String open(String path, Integer line) {
		Path file = Path.of(path).toAbsolutePath().normalize();
		if (!Files.exists(file)) {
			throw new IllegalArgumentException("No such file or directory: " + file);
		}
		if (Files.isDirectory(file)) {
			opening.add(file);
			ApplicationManager.getApplication().invokeLater(() -> {
				try {
					ProjectUtil.openOrImport(file, null, false); // returns once opened, or declined to trust
				} finally {
					opening.remove(file); // one entry: the same directory may be opening twice
				}
			}, ModalityState.nonModal());
			return "Opening project " + file + "; it may ask whether to trust it. Then pilot_wait_for smart=true.";
		}
		VirtualFile virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file);
		if (virtualFile == null) {
			throw new IllegalArgumentException("Cannot find " + file + " in the IDE's file system");
		}
		Project[] projects = ProjectManager.getInstance().getOpenProjects();
		Project project = Arrays.stream(projects)
				.filter(p -> !p.isDisposed() && ApplicationManager.getApplication().runReadAction((Computable<Boolean>) () -> ProjectFileIndex.getInstance(p).isInContent(virtualFile)))
				.findFirst().orElse(projects.length == 0 ? null : projects[0]);
		if (project == null) {
			throw new IllegalStateException("No open project to open the file in; open its project first");
		}
		ApplicationManager.getApplication().invokeLater(
				() -> new OpenFileDescriptor(project, virtualFile, line == null ? -1 : line - 1, 0).navigate(true), ModalityState.nonModal());
		return "Opening " + file + (line == null ? "" : ":" + line) + " in " + project.getName();
	}

	/** What pilot_wait_for waits for; null or false parts are not awaited. */
	record Wait(String text, String gone, boolean smart, boolean idle, String console, double time) {
	}

	/**
	 * Waits until {@code time} seconds have passed and all of: {@code text} shows in the UI, {@code gone} no longer
	 * shows, with {@code smart} no open project is indexing, with {@code idle} no background task runs, and
	 * {@code console} shows in a Run/Debug console; fails after {@code seconds}. On success, lists the snapshot lines
	 * that contain {@code text}, with their refs.
	 */
	static String waitFor(Wait wait, double seconds) throws InterruptedException {
		if (wait.text() == null && wait.gone() == null && !wait.smart() && !wait.idle() && wait.console() == null && wait.time() <= 0) {
			throw new IllegalArgumentException("Give text, gone, smart, idle, console or time");
		}
		if (wait.time() > seconds) {
			throw new IllegalArgumentException("time (" + wait.time() + " s) is past the timeout (" + seconds + " s)");
		}
		long start = System.nanoTime();
		long deadline = start + (long) (seconds * 1e9);
		long minimum = start + (long) (wait.time() * 1e9);
		long metSince = -1;
		while (true) {
			String missing = missing(wait);
			long now = System.nanoTime();
			if (missing == null) {
				if (metSince < 0) {
					metSince = now;
				}
				// smart and idle must hold for a while: indexing and background tasks come in rounds
				boolean settled = !(wait.smart() || wait.idle()) || now - metSince >= SMART_SETTLE_NANOS;
				if (settled && now >= minimum) {
					return String.format(Locale.ROOT, "Done after %.1f s", (now - start) / 1e9) + matches(wait.text());
				}
			} else {
				metSince = -1;
			}
			if (now > deadline) {
				throw new IllegalStateException("Timed out after " + seconds + " s: " + missing);
			}
			// poll every 200 ms, but end a plain time wait on time
			Thread.sleep(missing == null && now < minimum ? Math.min(200, Math.max(1, (minimum - now) / 1_000_000)) : 200);
		}
	}

	/** The snapshot lines that contain {@code text}, at most a few, so the caller can act on their refs. */
	private static String matches(String text) {
		if (text == null) {
			return "";
		}
		String ui = Edt.compute(UiSnapshot::text);
		List<String> lines = ui.lines().filter(line -> line.contains(text)).map(String::strip).toList();
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < Math.min(lines.size(), MAX_MATCHES); i++) {
			out.append('\n').append(lines.get(i));
		}
		if (lines.size() > MAX_MATCHES) {
			out.append("\n- ... ").append(lines.size() - MAX_MATCHES).append(" more");
		}
		return out.toString();
	}

	private static final int MAX_MATCHES = 5;

	/** What is still awaited, or null when nothing is. */
	private static String missing(Wait wait) {
		String text = wait.text();
		String gone = wait.gone();
		boolean smart = wait.smart();
		if (wait.idle()) {
			for (ProgressIndicator indicator : CoreProgressManager.getCurrentIndicators()) {
				if (indicator.isRunning() && !indicator.isCanceled()) {
					String what = indicator.getText();
					return "background task " + (what == null || what.isBlank() ? indicator.getClass().getSimpleName() : "\"" + what + "\"") + " is running";
				}
			}
		}
		if (wait.console() != null && IdeLog.consoleTexts().stream().noneMatch(c -> c.text() != null && c.text().contains(wait.console()))) {
			return "\"" + wait.console() + "\" not in a Run console";
		}
		if (smart) {
			Path pending = opening.stream().findFirst().orElse(null);
			if (pending != null) {
				return pending.getFileName() + " is still opening";
			}
			for (Project project : ProjectManager.getInstance().getOpenProjects()) {
				if (!project.isInitialized() || !StartupManager.getInstance(project).postStartupActivityPassed()) {
					return project.getName() + " is still opening";
				}
				if (DumbService.isDumb(project)) {
					return project.getName() + " is still indexing";
				}
			}
		}
		if (text == null && gone == null) {
			return null;
		}
		String ui = Edt.compute(UiSnapshot::text);
		if (text != null && !ui.contains(text)) {
			return "\"" + text + "\" not shown";
		}
		if (gone != null && ui.contains(gone)) {
			return "\"" + gone + "\" still shown";
		}
		return null;
	}
}
