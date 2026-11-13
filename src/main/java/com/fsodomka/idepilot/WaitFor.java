package com.fsodomka.idepilot;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.ide.PowerSaveMode;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.startup.StartupManager;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;

import java.awt.Toolkit;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** pilot_wait_for: waiting for the UI, indexing, background tasks, build syncs and Run console output. */
final class WaitFor {
	private static final long SMART_SETTLE_NANOS = 1_500_000_000L;
	/** How long the snapshot must stay unchanged for {@link Wait#settled}. */
	private static final long UI_SETTLE_NANOS = 300_000_000L;
	private static final int MAX_MATCHES = 5;
	private static final int MAX_LINE = 300;

	private WaitFor() {
	}

	/**
	 * Waits until {@code time} seconds have passed and all of: {@code text} shows in the UI, {@code gone} no longer
	 * shows, with {@code smart} no open project is indexing, with {@code idle} no background task runs, with
	 * {@code sync} no build sync runs and one ended since the last such wait ({@link BuildSyncs}), and
	 * {@code console} shows in a Run/Debug console's output since the last pilot call that acted ({@link RunConsoles#markConsoles});
	 * fails after {@code seconds}. On success, lists the snapshot lines that contain {@code text}, with their refs, and
	 * the new console lines that contain {@code console}, with their tab's name.
	 */
	static String until(Wait wait, double seconds) throws InterruptedException {
		if (wait.text() == null && wait.gone() == null && !wait.smart() && !wait.idle() && wait.task() == null && !wait.sync()
				&& !wait.settled() && wait.console() == null && wait.time() <= 0) {
			throw new IllegalArgumentException("Give text, gone, smart, idle, task, sync, settled, console or time");
		}
		if (wait.session() != null && wait.console() == null) {
			throw new IllegalArgumentException("session limits console; give console too");
		}
		if (wait.time() > IdeActions.MAX_SECONDS) {
			throw new IllegalArgumentException("time (" + wait.time() + " s) is past the most a call can wait (" + IdeActions.MAX_SECONDS
					+ " s; MCP clients give up on a call after 60 s); wait in several calls");
		}
		// a longer call would fail in the client with its own timeout, losing what was still missing
		String capped = seconds > IdeActions.MAX_SECONDS ? " (timeout capped from " + seconds + " s, as MCP clients give up on a call after 60 s; "
				+ "call again to keep waiting)" : "";
		seconds = Math.min(seconds, IdeActions.MAX_SECONDS);
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
				RunConsoles.since(c, marks.get(c.descriptor())).lines().filter(line -> line.contains(wait.console()))
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
		return after.lines().map(String::strip).filter(line -> !old.contains(line)).findFirst().map(WaitFor::abbreviate)
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
				&& RunConsoles.since(c, marks.get(c.descriptor())).contains(wait.console()))) {
			return "\"" + wait.console() + "\" not in new output of a Run console"
					+ (wait.session() == null ? "" : " named like \"" + wait.session() + "\"");
		}
		if (smart) {
			Path pending = IdeActions.opening.stream().findFirst().orElse(null);
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

	/**
	 * What pilot_wait_for waits for; null or false parts are not awaited. {@code session} limits {@code console} to
	 * the Run/Debug tabs whose name contains it.
	 */
	record Wait(String text, String gone, boolean smart, boolean idle, String task, boolean sync, boolean settled, String console, String session,
			double time) {
	}
}
