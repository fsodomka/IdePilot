package com.fsodomka.idepilot;

import com.intellij.ide.DataManager;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.ide.PowerSaveMode;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import org.jetbrains.annotations.NotNull;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Running actions by ID, and waiting for the UI or indexing. */
final class IdeActions {
	private static final String PLACE = "IdePilot";
	private static final int SUGGESTIONS = 15;
	private static final long SMART_SETTLE_NANOS = 1_500_000_000L;
	/** The project directories pilot_open is opening, each until its opening returns; smart waits for them. */
	private static final List<Path> opening = new CopyOnWriteArrayList<>();

	private IdeActions() {
	}

	/**
	 * Runs action {@code id} in the context of {@code ref}, or of the focused component, then watches the UI for
	 * {@code wait} seconds: returns the snapshot lines that showed meanwhile (hints, popups, dialogs; those gone again
	 * marked so) and the notifications fired.
	 */
	static String run(String id, String ref, double wait) throws InterruptedException {
		return watched(wait, ran -> start(id, ref, ran));
	}

	/**
	 * Does {@code act}, which sets its flag once what it queued has started, then, unless {@code wait} is 0, watches the
	 * UI for {@code wait} seconds: returns its result, then the snapshot lines that showed meanwhile (those gone again
	 * marked so) and the notifications fired.
	 */
	static String watched(double wait, Function<AtomicBoolean, String> act) throws InterruptedException {
		if (wait <= 0) {
			return act.apply(new AtomicBoolean());
		}
		Set<String> before = Edt.compute(UiSnapshot::text).lines().map(String::strip).collect(Collectors.toSet());
		List<String> notified = new CopyOnWriteArrayList<>();
		Disposable listening = Disposer.newDisposable();
		try {
			Notifications listener = new Notifications() {
				@Override
				public void notify(@NotNull Notification notification) {
					notified.add(IdeLog.notification(notification));
				}
			};
			// the topic does not broadcast between the application and the projects
			ApplicationManager.getApplication().getMessageBus().connect(listening).subscribe(Notifications.TOPIC, listener);
			for (Project project : ProjectManager.getInstance().getOpenProjects()) {
				project.getMessageBus().connect(listening).subscribe(Notifications.TOPIC, listener);
			}
			AtomicBoolean ran = new AtomicBoolean();
			String result = act.apply(ran);
			// each line that showed since, and whether it still shows; polled, so hints that show briefly are caught
			Map<String, Boolean> shown = new LinkedHashMap<>();
			long end = System.nanoTime() + (long) (wait * 1e9);
			for (long now = System.nanoTime(); now < end; now = System.nanoTime()) {
				Thread.sleep(Math.clamp((end - now) / 1_000_000, 1, OUTCOME_POLL_MILLIS));
				List<String> lines = Edt.compute(UiSnapshot::text).lines().filter(line -> !before.contains(line.strip())).toList();
				shown.replaceAll((line, showing) -> false);
				lines.forEach(line -> shown.put(line, true));
			}
			return result + outcome(ran.get(), wait, shown, notified);
		} finally {
			Disposer.dispose(listening);
		}
	}

	private static final long OUTCOME_POLL_MILLIS = 200;
	private static final int MAX_OUTCOME_LINES = 30;

	/** What {@link #watched} saw after the act. */
	private static String outcome(boolean ran, double wait, Map<String, Boolean> shown, List<String> notified) {
		String seconds = String.format(Locale.ROOT, "%.1f s", wait);
		StringBuilder out = new StringBuilder();
		if (!ran) {
			out.append("; it has not run within ").append(seconds).append(" (a modal dialog may block it)");
		}
		if (shown.isEmpty() && notified.isEmpty()) {
			return out.append(ran ? "; nothing new showed within " + seconds : "").toString();
		}
		if (!shown.isEmpty()) {
			out.append("\nShowed within ").append(seconds).append(':');
			int listed = 0;
			for (Map.Entry<String, Boolean> line : shown.entrySet()) {
				if (listed++ == MAX_OUTCOME_LINES) {
					out.append("\n- ... ").append(shown.size() - MAX_OUTCOME_LINES).append(" more; pilot_snapshot");
					break;
				}
				out.append('\n').append(line.getKey()).append(line.getValue() ? "" : " (gone)");
			}
		}
		if (!notified.isEmpty()) {
			out.append("\nNotifications:\n").append(String.join("\n", notified));
		}
		return out.toString();
	}

	/** Queues action {@code id} in the context of {@code ref}, or of the focused component; sets {@code ran} as it starts. */
	private static String start(String id, String ref, AtomicBoolean ran) {
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
				() -> {
					ran.set(true);
					// with no focus owner (the IDE is not the active application, or a closed dialog left none), a showing
					// project frame, so actions such as Hotswap find their project
					IdeFrame frame = context == null ? showingFrame() : null;
					Component data = frame != null ? frame.getComponent() : context;
					ActionUtil.performAction(action, AnActionEvent.createEvent(DataManager.getInstance().getDataContext(data),
							action.getTemplatePresentation().clone(), PLACE, ActionUiKind.NONE, null));
				},
				Edt.modality(context));
		return "Ran " + id + (ref == null ? "" : " on " + ref);
	}

	/**
	 * The last focused project frame if it still shows, else the first showing frame of an open project, else null.
	 * The last focused frame may be that of a closed project: the platform skips an action whose context component
	 * does not show, only logging it. Call on the EDT.
	 */
	private static IdeFrame showingFrame() {
		IdeFrame last = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
		if (last != null && last.getComponent().isShowing() && last.getProject() != null && !last.getProject().isDisposed()) {
			return last;
		}
		for (Project project : ProjectManager.getInstance().getOpenProjects()) {
			IdeFrame frame = WindowManager.getInstance().getIdeFrame(project);
			if (frame != null && frame.getComponent().isShowing()) {
				return frame;
			}
		}
		return null;
	}

	/**
	 * The actions whose ID or menu text contains {@code find} (case-insensitive), as "ID – text – shortcut" lines,
	 * sorted by ID, at most {@code limit}; groups are marked.
	 */
	static String find(String find, int limit) {
		ActionManager manager = ActionManager.getInstance();
		String needle = find.toLowerCase(Locale.ROOT);
		List<String> lines = new ArrayList<>();
		for (String id : manager.getActionIdList("").stream().sorted().toList()) {
			// the stub, not getAction: its text comes from plugin.xml, without loading the action's class
			AnAction action = manager.getActionOrStub(id);
			String text = action == null ? null : action.getTemplatePresentation().getText();
			if (!id.toLowerCase(Locale.ROOT).contains(needle) && (text == null || !text.toLowerCase(Locale.ROOT).contains(needle))) {
				continue;
			}
			String shortcut = KeymapUtil.getFirstKeyboardShortcutText(id);
			lines.add(id + (text == null || text.isBlank() ? "" : " – " + text) + (shortcut.isEmpty() ? "" : " – " + shortcut)
					+ (manager.isGroup(id) ? " (group)" : ""));
		}
		if (lines.isEmpty()) {
			return "(no action ID or text contains \"" + find + "\")";
		}
		String more = lines.size() > limit ? "\n... " + (lines.size() - limit) + " more; narrow find" : "";
		return String.join("\n", lines.subList(0, Math.min(lines.size(), limit))) + more;
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
			// off the EDT: there it would open under a modal progress whose VFS refresh runs slow operations on the EDT
			ApplicationManager.getApplication().executeOnPooledThread(() -> {
				try {
					ProjectUtil.openOrImport(file, null, false); // returns once opened, or declined to trust
				} finally {
					opening.remove(file); // one entry: the same directory may be opening twice
				}
			});
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

	/**
	 * What pilot_wait_for waits for; null or false parts are not awaited. {@code session} limits {@code console} to
	 * the Run/Debug tabs whose name contains it.
	 */
	record Wait(String text, String gone, boolean smart, boolean idle, String task, boolean sync, boolean settled, String console, String session,
			double time) {
	}

	/** How long the snapshot must stay unchanged for {@link Wait#settled}. */
	private static final long UI_SETTLE_NANOS = 300_000_000L;

	/**
	 * Waits until {@code time} seconds have passed and all of: {@code text} shows in the UI, {@code gone} no longer
	 * shows, with {@code smart} no open project is indexing, with {@code idle} no background task runs, with
	 * {@code sync} no build sync runs and one ended since the last such wait ({@link BuildSyncs}), and
	 * {@code console} shows in a Run/Debug console's output since the last pilot call that acted ({@link RunConsoles#markConsoles});
	 * fails after {@code seconds}. On success, lists the snapshot lines that contain {@code text}, with their refs, and
	 * the new console lines that contain {@code console}, with their tab's name.
	 */
	static String waitFor(Wait wait, double seconds) throws InterruptedException {
		if (wait.text() == null && wait.gone() == null && !wait.smart() && !wait.idle() && wait.task() == null && !wait.sync()
				&& !wait.settled() && wait.console() == null && wait.time() <= 0) {
			throw new IllegalArgumentException("Give text, gone, smart, idle, task, sync, settled, console or time");
		}
		if (wait.session() != null && wait.console() == null) {
			throw new IllegalArgumentException("session limits console; give console too");
		}
		if (wait.time() > MAX_SECONDS) {
			throw new IllegalArgumentException("time (" + wait.time() + " s) is past the most a call can wait (" + MAX_SECONDS
					+ " s; MCP clients give up on a call after 60 s); wait in several calls");
		}
		// a longer call would fail in the client with its own timeout, losing what was still missing
		String capped = seconds > MAX_SECONDS ? " (timeout capped from " + seconds + " s, as MCP clients give up on a call after 60 s; "
				+ "call again to keep waiting)" : "";
		seconds = Math.min(seconds, MAX_SECONDS);
		if (wait.time() > seconds) {
			throw new IllegalArgumentException("time (" + wait.time() + " s) is past the timeout (" + seconds + " s)");
		}
		Map<Object, RunConsoles.Mark> marks = wait.console() == null ? null : RunConsoles.consoleMarks();
		long start = System.nanoTime();
		if (wait.sync()) {
			BuildSyncs.forgetBefore(start);
		}
		long deadline = start + (long) (seconds * 1e9);
		long minimum = start + (long) (wait.time() * 1e9);
		long metSince = -1;
		String lastUi = null;
		String changed = null;
		long uiSince = 0;
		while (true) {
			String missing = missing(wait, marks);
			long now = System.nanoTime();
			if (missing == null && wait.settled()) {
				String ui = Edt.compute(UiSnapshot::text);
				if (!ui.equals(lastUi)) {
					changed = lastUi == null ? null : firstChange(lastUi, ui);
					lastUi = ui;
					uiSince = now;
				}
				if (now - uiSince < UI_SETTLE_NANOS) {
					missing = "the UI is still changing" + (changed == null ? "" : ", e.g. " + changed);
				} else if (Toolkit.getDefaultToolkit().getSystemEventQueue().peekEvent() != null) {
					missing = "UI events are still queued";
				}
			}
			if (missing == null) {
				if (metSince < 0) {
					metSince = now;
				}
				// smart, idle, task and sync must hold for a while: indexing, background tasks and syncs come in rounds
				boolean settled = !(wait.smart() || wait.idle() || wait.task() != null || wait.sync()) || now - metSince >= SMART_SETTLE_NANOS;
				if (settled && now >= minimum) {
					String syncs = wait.sync() ? BuildSyncs.report() : "";
					return String.format(Locale.ROOT, "Done after %.1f s", (now - start) / 1e9) + syncs + matches(wait.text())
							+ consoleMatches(wait, marks);
				}
			} else {
				metSince = -1;
			}
			if (now > deadline) {
				throw new IllegalStateException("Timed out after " + seconds + " s" + capped + ": " + missing);
			}
			// poll every 200 ms, but end a plain time wait on time
			Thread.sleep(missing == null && now < minimum ? Math.clamp((minimum - now) / 1_000_000, 1, 200) : 200);
		}
	}

	/** The snapshot lines that contain {@code text}, at most a few, so the caller can act on their refs. */
	private static String matches(String text) {
		if (text == null) {
			return "";
		}
		String ui = Edt.compute(UiSnapshot::text);
		return listed(ui.lines().filter(line -> line.contains(text)).map(String::strip).toList());
	}

	/**
	 * The lines of new console output that contain {@code console}, as "- TAB: line", at most a few, so the caller
	 * sees which run printed what; {@code marks} are where the consoles' old output ends.
	 */
	private static String consoleMatches(Wait wait, Map<Object, RunConsoles.Mark> marks) {
		if (wait.console() == null) {
			return "";
		}
		List<String> lines = new ArrayList<>();
		for (RunConsoles.Console c : RunConsoles.consoleTexts(false)) {
			if (c.isIn(wait.session()) && c.text() != null) {
				RunConsoles.since(c.text(), marks.get(c.descriptor())).lines().filter(line -> line.contains(wait.console()))
						.map(line -> "- " + c.title() + ": " + abbreviate(line.strip()))
						.forEach(lines::add);
			}
		}
		return listed(lines);
	}

	/**
	 * The first snapshot line of {@code after} that {@code before} did not have, so a settled wait that times out names
	 * what keeps changing (a progress bar, a timer); or, when only lines went, that one did.
	 */
	static String firstChange(String before, String after) {
		Set<String> old = before.lines().map(String::strip).collect(Collectors.toSet());
		return after.lines().map(String::strip).filter(line -> !old.contains(line)).findFirst().map(IdeActions::abbreviate)
				.orElse("a line went");
	}

	/** {@code line}, cut to {@link #MAX_LINE} characters. */
	private static String abbreviate(String line) {
		return line.length() <= MAX_LINE ? line : line.substring(0, MAX_LINE) + "…";
	}

	/** The first {@link #MAX_MATCHES} of {@code lines}, each on a new line, then how many more there are. */
	private static String listed(List<String> lines) {
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
	private static final int MAX_LINE = 300;
	/** The most a call waits: MCP clients (the TypeScript SDK's default) give up on a request after 60 s. */
	static final double MAX_SECONDS = 50;

	/**
	 * The file of a showing editor whose highlighting has not finished, or null: it runs under no status bar progress,
	 * and pilot_editor's problems tell to wait for idle. Only in the last focused frame, as the IDE leaves the editors
	 * of its other frames unanalysed, and not in power save mode, where it analyses none.
	 */
	private static String analysed() {
		return Edt.compute(() -> {
			IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
			Project project = frame == null ? null : frame.getProject();
			if (project == null || project.isDisposed() || PowerSaveMode.isEnabled()) {
				return null;
			}
			for (FileEditor fileEditor : FileEditorManager.getInstance(project).getSelectedEditors()) {
				if (!(fileEditor instanceof TextEditor text) || !text.getComponent().isShowing()) {
					continue;
				}
				PsiFile file = PsiDocumentManager.getInstance(project).getPsiFile(text.getEditor().getDocument());
				if (file != null && DaemonCodeAnalyzer.getInstance(project).isHighlightingAvailable(file)
						&& !DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(file)) {
					return file.getName();
				}
			}
			return null;
		});
	}

	/** What is still awaited, or null when nothing is; {@code marks} are where the consoles' old output ends. */
	private static String missing(Wait wait, Map<Object, RunConsoles.Mark> marks) {
		String text = wait.text();
		String gone = wait.gone();
		boolean smart = wait.smart();
		if (wait.sync()) {
			String sync = BuildSyncs.missing();
			if (sync != null) {
				return sync;
			}
		}
		if (wait.idle()) {
			List<BackgroundTasks.Task> running = BackgroundTasks.running();
			if (!running.isEmpty()) {
				return "background task " + UiSnapshot.quote(running.getFirst().title()) + " is running";
			}
			String analysed = analysed();
			if (analysed != null) {
				return analysed + " is still being analysed";
			}
		}
		if (wait.task() != null) {
			String task = BackgroundTasks.missing(wait.task());
			if (task != null) {
				return task;
			}
		}
		if (wait.console() != null && RunConsoles.consoleTexts(false).stream().noneMatch(c -> c.isIn(wait.session()) && c.text() != null
				&& RunConsoles.since(c.text(), marks.get(c.descriptor())).contains(wait.console()))) {
			return "\"" + wait.console() + "\" not in new output of a Run console"
					+ (wait.session() == null ? "" : " named like \"" + wait.session() + "\"");
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
