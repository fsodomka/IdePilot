package com.fsodomka.idepilot;

import com.intellij.ide.DataManager;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.notification.Notification;
import com.intellij.notification.Notifications;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionUiKind;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.ex.ActionUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.keymap.KeymapUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.openapi.wm.WindowManager;
import org.jetbrains.annotations.NotNull;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Running and finding actions by ID, watching what they show, and opening projects and files. */
final class IdeActions {
	private static final String PLACE = "IdePilot";
	private static final int SUGGESTIONS = 15;
	/** The project directories pilot_open is opening, each until its opening returns; {@link WaitFor} smart waits for them. */
	static final List<Path> opening = new CopyOnWriteArrayList<>();

	private IdeActions() {
	}

	/**
	 * Runs action {@code id} in the context of {@code ref}, or of the focused component, then watches the UI for
	 * {@code wait} seconds: returns the snapshot lines that showed meanwhile (hints, popups, dialogs; those gone again
	 * marked so) and the notifications fired.
	 */
	static String run(String id, String ref, String session, double wait) throws InterruptedException {
		if (ref != null && session != null) {
			throw new IllegalArgumentException("Give ref or session, not both");
		}
		String note = "";
		if (builds(id)) {
			// files changed on disk show in the IDE only after a refresh, which an inactive IDE does not do; builds would skip them.
			// async: it schedules its VFS write in a write-safe context, which syncRefresh in invokeAndWait(any) is not
			CountDownLatch refreshed = new CountDownLatch(1);
			VirtualFileManager.getInstance().asyncRefresh(refreshed::countDown);
			if (!refreshed.await(REFRESH_SECONDS, TimeUnit.SECONDS)) {
				note = "\nNote: refreshing changed files did not finish within " + REFRESH_SECONDS + " s; the build may miss some";
			}
		}
		// the warning is set when the action runs: with wait 0 the call returns before that, so it shows only with wait
		AtomicReference<String> disabled = new AtomicReference<>("");
		String result = watched(wait, ran -> start(id, ref, session, ran, disabled));
		return result + note + disabled.get();
	}

	/** The IDs of actions that build: before them, changes on disk are refreshed into the IDE. */
	private static final Pattern BUILDS = Pattern.compile("(?i)(Re)?(Compile|Build|HotSwap)\\w*|Make(Module|Project)");
	private static final long REFRESH_SECONDS = 10;

	/** Whether action {@code id} builds (Compile*, Build*, Rebuild*, MakeModule, Hotswap...), not e.g. MakeStatic or BuildMenu. */
	static boolean builds(String id) {
		return BUILDS.matcher(id).matches() && !id.endsWith("Menu");
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

	/**
	 * Queues action {@code id} in the context of {@code ref}, of the Run/Debug tab named like {@code session}, or of the
	 * focused component; sets {@code ran} as it starts, and {@code disabled} to a warning when the action's update
	 * disabled it there (it runs anyway).
	 */
	private static String start(String id, String ref, String session, AtomicBoolean ran, AtomicReference<String> disabled) {
		ActionManager manager = ActionManager.getInstance();
		AnAction action = manager.getAction(id);
		if (action == null) {
			String needle = id.toLowerCase(Locale.ROOT);
			List<String> similar = manager.getActionIdList("").stream()
					.filter(candidate -> candidate.toLowerCase(Locale.ROOT).contains(needle)).sorted().limit(SUGGESTIONS).toList();
			throw new IllegalArgumentException("No action " + id + (similar.isEmpty() ? "" : "; similar: " + String.join(", ", similar)));
		}
		RunConsoles.Tab tab = session == null ? null : Edt.compute(() -> RunConsoles.tab(session));
		Component target = ref != null ? UiRefs.component(ref) : tab != null ? tab.component() : null;
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
					AnActionEvent event = AnActionEvent.createEvent(DataManager.getInstance().getDataContext(data),
							action.getTemplatePresentation().clone(), PLACE, ActionUiKind.NONE, null);
					// updateAction, not lastUpdateAndCheckDumb, which leaves a background-thread action (e.g. Stop) enabled
					ActionUtil.updateAction(action, event);
					if (!event.getPresentation().isEnabled()) {
						disabled.set("\nWarning: " + id + " is disabled in this context, so it likely did nothing; "
								+ "pass the ref of the component it acts on, or session for a Run/Debug tab's actions, or click its button");
						// run anyway, as some actions' update is stricter than their action (e.g. ExpandAll on a collapsed tree)
						event = AnActionEvent.createEvent(event.getDataContext(), action.getTemplatePresentation().clone(), PLACE,
								ActionUiKind.NONE, null);
					}
					ActionUtil.performAction(action, event);
				},
				Edt.modality(context));
		return "Ran " + id + (ref != null ? " on " + ref : tab != null ? " in the Run/Debug tab " + UiSnapshot.quote(tab.title()) : "");
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

	/** The most a call waits: MCP clients (the TypeScript SDK's default) give up on a request after 60 s. */
	static final double MAX_SECONDS = 50;

}
