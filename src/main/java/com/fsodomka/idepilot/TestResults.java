package com.fsodomka.idepilot;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.testframework.AbstractTestProxy;
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** The results of the test runs in the Run/Debug tabs of the open projects, as their test trees hold them. */
final class TestResults {
	private static final int STACK_LINES = 12;

	private TestResults() {
	}

	/**
	 * Per test tab: a summary line, then the failed tests (or, with {@code contains}, the tests of any state whose name
	 * contains it), at most {@code count} per tab, each with its message and the start of its stack trace.
	 */
	static String tests(int count, String contains, String session, boolean running) {
		return Edt.compute(() -> {
			List<String> result = new ArrayList<>();
			for (Project project : ProjectManager.getInstance().getOpenProjects()) {
				for (RunContentDescriptor descriptor : RunContentManager.getInstance(project).getAllDescriptors()) {
					if (!(descriptor.getExecutionConsole() instanceof SMTRunnerConsoleView view)) {
						continue;
					}
					String title = descriptor.getDisplayName();
					ProcessHandler handler = descriptor.getProcessHandler();
					boolean isRunning = handler != null && !handler.isProcessTerminated();
					if (!RunConsoles.named(title, session) || running && !isRunning) {
						continue;
					}
					result.add(tab(title, isRunning, view.getResultsViewer().getTestsRootNode(), count, contains));
				}
			}
			return result.isEmpty() ? RunConsoles.none("test runs", session, running) : String.join("\n\n", result);
		});
	}

	static String tab(String title, boolean running, AbstractTestProxy root, int count, String contains) {
		List<AbstractTestProxy> leaves = root.getAllTests().stream().filter(t -> t.isLeaf() && t != root).map(t -> (AbstractTestProxy) t).toList();
		int passed = 0, failed = 0, ignored = 0, inProgress = 0;
		for (AbstractTestProxy test : leaves) {
			if (test.isInProgress()) {
				inProgress++;
			} else if (test.isIgnored()) {
				ignored++;
			} else if (test.isDefect()) {
				failed++;
			} else if (test.isPassed()) {
				passed++;
			}
		}
		StringBuilder out = new StringBuilder("== ").append(title).append(" (").append(running ? "running" : "finished")
				.append(")\n").append(leaves.size()).append(" tests: ").append(passed).append(" passed, ").append(failed)
				.append(" failed, ").append(ignored).append(" ignored");
		if (inProgress > 0) {
			out.append(", ").append(inProgress).append(" running");
		}
		if (root.getDuration() != null) {
			out.append(", ").append(root.getDuration()).append(" ms");
		}
		if (leaves.isEmpty() && root.isDefect() && root.getErrorMessage() != null) { // e.g. compilation failed before any test
			out.append("\n").append(details(root));
		}
		String needle = contains == null ? null : contains.toLowerCase(Locale.ROOT);
		List<AbstractTestProxy> listed = leaves.stream().filter(t -> needle == null ? t.isDefect() && !t.isIgnored()
				: name(t).toLowerCase(Locale.ROOT).contains(needle)).toList();
		listed.stream().limit(count).forEach(t -> out.append("\n").append(details(t)));
		if (listed.size() > count) {
			out.append("\n(").append(listed.size() - count).append(" more)");
		}
		return out.toString();
	}

	private static String details(AbstractTestProxy test) {
		String state = test.isInProgress() ? "RUNNING" : test.isIgnored() ? "IGNORED" : test.isDefect() ? "FAILED" : "PASSED";
		StringBuilder out = new StringBuilder(state).append(" ").append(name(test));
		if (test.getDuration() != null) {
			out.append(" (").append(test.getDuration()).append(" ms)");
		}
		String message = test.getErrorMessage();
		if (message != null && !message.isBlank()) {
			out.append(": ").append(message.strip().replace("\n", "\n    "));
		}
		String stack = test.getStacktrace();
		if (stack != null && !stack.isBlank()) {
			List<String> lines = Arrays.stream(stack.strip().split("\r?\n")).filter(l -> !l.isBlank()).toList();
			lines.stream().limit(STACK_LINES).forEach(l -> out.append("\n    ").append(l.strip()));
			if (lines.size() > STACK_LINES) {
				out.append("\n    ... ").append(lines.size() - STACK_LINES).append(" more");
			}
		}
		return out.toString();
	}

	/** The test's name under its suite's, e.g. FooTest.testBar. */
	private static String name(AbstractTestProxy test) {
		AbstractTestProxy parent = test.getParent();
		return parent == null || parent.getParent() == null ? test.getName() : parent.getName() + "." + test.getName();
	}
}
