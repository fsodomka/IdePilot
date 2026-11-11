package com.fsodomka.idepilot;

import com.intellij.execution.Executor;
import com.intellij.execution.ExecutorRegistry;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.impl.ExecutionManagerImpl;
import com.intellij.execution.process.BaseProcessHandler;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView;
import com.intellij.execution.ui.ConsoleViewWithDelegate;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.impl.EditorComponentImpl;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.terminal.JBTerminalWidget;
import com.intellij.terminal.TerminalExecutionConsole;
import com.intellij.ui.content.Content;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.jediterm.terminal.TtyConnector;

import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The Run/Debug consoles and Terminal tabs of the open projects: their text, state, and output since a mark. */
final class RunConsoles {
	private RunConsoles() {
	}

	/** One line per Run/Debug tab of the open projects: its name, mode and state; filtered as {@link #consoles} does. */
	static String sessions(String session, boolean running) {
		List<String> result = consoleTexts(true).stream().filter(c -> c.isIn(session) && (!running || c.running()))
				.map(c -> c.title() + " (" + c.session() + ")").toList();
		return result.isEmpty() ? none("Run or Debug sessions", session, running) : String.join("\n", result);
	}

	/**
	 * The last lines of each Run/Debug console of the open projects, under its tab's name and state; only the tabs whose name
	 * contains {@code session} (case-insensitive) when given, and with {@code running} only those still running.
	 */
	static String consoles(int lines, String contains, String session, boolean running) {
		List<String> result = new ArrayList<>();
		for (Console console : consoleTexts(true)) {
			if (!console.isIn(session) || running && !console.running()) {
				continue;
			}
			String header = "== " + console.title() + " (" + console.session() + ")";
			if (console.text() == null) {
				result.add(header + "\n(" + console.kind() + ", not readable)");
				continue;
			}
			List<String> last = IdeLog.last(IdeLog.matching(List.of(console.text().split("\r?\n")), contains), lines);
			result.add(header + (last.isEmpty() ? "\n(no matching lines)" : "\n" + String.join("\n", last)));
		}
		return result.isEmpty() ? none("Run or Debug consoles", session, running) : String.join("\n\n", result);
	}

	/** Whether a tab's {@code title} contains {@code session} (case-insensitive); any tab when null. */
	static boolean named(String title, String session) {
		return session == null || title.toLowerCase(Locale.ROOT).contains(session.toLowerCase(Locale.ROOT));
	}

	/** The result when no tab passes the session and running filters, e.g. (no running test runs named like "Foo"). */
	static String none(String what, String session, boolean running) {
		return "(no " + (running ? "running " : "") + what + (session == null ? "" : " named like \"" + session + "\"") + ")";
	}

	/**
	 * A Run/Debug tab's console: its text, or null with the console's class name when it cannot be read.
	 * {@code descriptor} identifies the run: a rerun in the same tab has a new one. {@code document} holds the text when
	 * the console is an editor, else null.
	 */
	record Console(Object descriptor, String title, boolean running, String session, String text, String kind, Document document) {
		/** Whether the tab's name contains {@code session} (case-insensitive); any tab when null. */
		boolean isIn(String session) {
			return named(title, session);
		}
	}

	/**
	 * Where a console's text ended. In a document, a range marker there, which the document moves left as the console
	 * trims its start; otherwise its length and last characters, to find that end again after a trim.
	 */
	record Mark(int length, String tail, RangeMarker end) {
		private static final int TAIL = 200;

		static Mark of(String text) {
			return new Mark(text.length(), text.substring(Math.max(0, text.length() - TAIL)), null);
		}

		/** The end of {@code console}'s text now. Call on the EDT. */
		static Mark of(Console console) {
			Mark mark = of(console.text());
			Document document = console.document();
			return document == null ? mark : new Mark(mark.length(), mark.tail(),
					document.createRangeMarker(document.getTextLength(), document.getTextLength()));
		}
	}

	/** The consoles' ends at the start of the last pilot call that acted; null before the first. */
	private static volatile Map<Object, Mark> marks;

	/**
	 * Remembers where each console's text ends now, so that {@link #since} sees only what is written later. Called at
	 * the start of each pilot call that may start a run; waits at most a second for the EDT, keeping the old marks
	 * if it is busy or the marks cannot be taken. A console that appeared since the previous marks, likely started by
	 * the call that made them, is marked at its start this once, so a call between starting a run and waiting for its
	 * output does not hide what it printed so far.
	 */
	static void markConsoles() {
		try {
			Map<Object, Mark> previous = marks;
			Map<Object, Mark> current = Edt.await(RunConsoles::currentMarks, ModalityState.any(), 1000, "EDT busy", e -> new IllegalStateException(e));
			if (previous != null) {
				current.replaceAll((descriptor, mark) -> previous.containsKey(descriptor) ? mark : Mark.of(""));
			}
			marks = current;
		} catch (RuntimeException e) {
			// keep the previous marks (an older cutoff only matches more); marking never fails the call
		}
	}

	/** The marks of the last pilot call that acted; without one yet, the consoles' ends now. */
	static Map<Object, Mark> consoleMarks() {
		Map<Object, Mark> current = marks;
		return current != null ? current : Edt.compute(RunConsoles::currentMarks);
	}

	private static Map<Object, Mark> currentMarks() {
		Map<Object, Mark> result = new IdentityHashMap<>();
		for (Console console : consoleTexts(false)) {
			if (console.text() != null) {
				result.put(console.descriptor(), Mark.of(console));
			}
		}
		return result;
	}

	/**
	 * The part of a console's {@code text} written after {@code mark}: all of it without a mark (a new run). In a
	 * document, after the mark's range marker, all of it once the marker is gone (the console trimmed past it or was
	 * cleared). Otherwise, when the console dropped its oldest text the old end is found by its tail, which repeated
	 * output can make ambiguous; when it was cleared, all of it.
	 */
	static String since(String text, Mark mark) {
		if (mark == null) {
			return text;
		}
		if (mark.end() != null) {
			int offset = mark.end().isValid() ? mark.end().getEndOffset() : 0;
			return offset <= text.length() ? text.substring(offset) : text;
		}
		String tail = mark.tail();
		int end = mark.length();
		if (end <= text.length() && text.startsWith(tail, end - tail.length())) {
			return text.substring(end);
		}
		// trimming only moves the old end left, so it is the last occurrence of the tail that ends by the old end
		int at = text.lastIndexOf(tail, Math.min(end, text.length()) - tail.length());
		return at < 0 ? text : text.substring(at + tail.length());
	}

	/**
	 * The Run/Debug consoles and Terminal tabs of the open projects; with {@code details}, each run's mode and state
	 * ({@link #session}, which queries the OS process), else an empty session, for the callers that poll.
	 */
	static List<Console> consoleTexts(boolean details) {
		return Edt.compute(() -> {
			List<Console> result = new ArrayList<>();
			for (Project project : ProjectManager.getInstance().getOpenProjects()) {
				for (RunContentDescriptor descriptor : RunContentManager.getInstance(project).getAllDescriptors()) {
					ExecutionConsole console = descriptor.getExecutionConsole();
					if (console instanceof BaseTestsOutputConsoleView tests) {
						console = tests.getConsole();
					}
					while (console instanceof ConsoleViewWithDelegate wrapper) { // e.g. Java consoles with the profiler widget
						console = wrapper.getDelegate();
					}
					ProcessHandler handler = descriptor.getProcessHandler();
					boolean running = handler != null && !handler.isProcessTerminated();
					String text = null;
					Document document = null;
					if (console instanceof ConsoleViewImpl view) {
						view.flushDeferredText();
						document = view.getEditor() == null ? null : view.getEditor().getDocument();
						text = document == null ? view.getText() : document.getText();
					} else if (console instanceof TerminalExecutionConsole terminal) { // e.g. Shell Script configurations
						text = terminal.getTerminalWidget().getText().stripTrailing();
					}
					result.add(new Console(descriptor, descriptor.getDisplayName(), running, details ? session(project, descriptor) : "", text,
							console == null ? "no console" : console.getClass().getSimpleName(), document));
				}
				terminals(project, result);
			}
			return result;
		});
	}

	/**
	 * The tabs of {@code project}'s Terminal tool window, as consoles titled "Terminal: TAB". Read from the tab's
	 * components, so without a dependency on the terminal plugin: a classic terminal's JBTerminalWidget, or the
	 * reworked terminal's output editor (the visible one; its alternate-screen editor is hidden unless in use).
	 */
	private static void terminals(Project project, List<Console> result) {
		ToolWindow window = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL);
		if (window == null) {
			return;
		}
		for (Content content : window.getContentManager().getContents()) {
			Component found = terminalText(content.getComponent());
			Document document = found instanceof EditorComponentImpl editor ? editor.getEditor().getDocument() : null;
			String text = found instanceof JBTerminalWidget widget ? widget.getText().stripTrailing()
					: document != null ? document.getText() : null;
			// a classic terminal tells whether its shell is connected; the reworked one's state is not read here
			TtyConnector tty = found instanceof JBTerminalWidget widget ? widget.getTtyConnector() : null;
			boolean running = tty != null && tty.isConnected();
			String state = found instanceof JBTerminalWidget ? running ? "running" : "exited" : "state unknown";
			result.add(new Console(content, "Terminal: " + content.getDisplayName(), running, "Terminal tab, " + state, text,
					content.getComponent().getClass().getSimpleName(), document));
		}
	}

	private static final String TERMINAL = "Terminal";

	/** The first visible terminal widget or editor under {@code component}, depth first; null when none. */
	private static Component terminalText(Component component) {
		if (!component.isVisible()) {
			return null;
		}
		if (component instanceof JBTerminalWidget || component instanceof EditorComponentImpl) {
			return component;
		}
		if (component instanceof Container container) {
			for (Component child : container.getComponents()) {
				Component found = terminalText(child);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/**
	 * A run's mode and state, e.g. "Debug, paused, pid 1234, started 10:01:02" or "Run, terminated, exit code 1": the
	 * executor, running, paused (a suspended debug session), stopping or terminated, and the local process's ID and
	 * start time when known. Call on the EDT.
	 */
	private static String session(Project project, RunContentDescriptor descriptor) {
		ProcessHandler handler = descriptor.getProcessHandler();
		XDebugSession debug = handler == null ? null : Arrays.stream(XDebuggerManager.getInstance(project).getDebugSessions())
				.filter(s -> s.getDebugProcess().getProcessHandler() == handler).findFirst().orElse(null);
		List<String> executors = ExecutionManagerImpl.getInstance(project).getExecutors(descriptor).stream().map(Executor::getId).sorted().toList();
		List<String> parts = new ArrayList<>(executors.isEmpty() ? mode(project, descriptor, debug) : executors);
		if (handler == null) {
			parts.add("no process");
		} else if (handler.isProcessTerminated()) {
			parts.add("terminated" + (handler.getExitCode() == null ? "" : ", exit code " + handler.getExitCode()));
		} else if (handler.isProcessTerminating()) {
			parts.add("stopping");
		} else {
			parts.add(debug != null && debug.isSuspended() ? "paused" : "running");
		}
		if (handler instanceof BaseProcessHandler<?> local && local.getProcess() != null) {
			try {
				ProcessHandle.Info info = local.getProcess().toHandle().info();
				parts.add("pid " + local.getProcess().pid());
				info.startInstant().ifPresent(start -> parts.add("started " + IdeLog.TIME.format(start)));
			} catch (UnsupportedOperationException e) {
				// not a local OS process
			}
		}
		return String.join(", ", parts);
	}

	/**
	 * The mode of a run the execution manager does not list executors for: a debug session registers its own descriptor
	 * there, not the one its tab shows. Debug when a debug session runs its process, else the executor whose tool window
	 * shows the tab; none when neither tells. Call on the EDT.
	 */
	private static List<String> mode(Project project, RunContentDescriptor descriptor, XDebugSession debug) {
		if (debug != null) {
			return List.of(DefaultDebugExecutor.EXECUTOR_ID);
		}
		ToolWindow window = RunContentManager.getInstance(project).getToolWindowByDescriptor(descriptor);
		Executor executor = window == null ? null : ExecutorRegistry.getInstance().getExecutorById(window.getId());
		return executor == null ? List.of() : List.of(executor.getId());
	}
}
