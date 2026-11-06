package com.fsodomka.idepilot;

import com.intellij.diagnostic.AbstractMessage;
import com.intellij.diagnostic.MessagePool;
import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.testframework.ui.BaseTestsOutputConsoleView;
import com.intellij.execution.ui.ExecutionConsole;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.execution.ui.RunContentManager;
import com.intellij.notification.ActionCenter;
import com.intellij.notification.Notification;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.terminal.TerminalExecutionConsole;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The tail of idea.log, the IDE's caught exceptions, UI freezes, notifications, stdout/stderr and Run consoles. */
final class IdeLog {
	/** How far back to read; enough for the tail, filtered or not, without reading a large log whole. */
	private static final long MAX_BYTES = 4L << 20;
	private static final int STACK_LINES = 12;
	private static final String FREEZE_PREFIX = "threadDumps-freeze-";

	private IdeLog() {
	}

	/** The exceptions the IDE caught, read or not, each with the top of its stack trace. */
	static String errors(int count, String contains) {
		String needle = contains == null ? null : contains.toLowerCase(Locale.ROOT);
		List<String> errors = new ArrayList<>();
		for (AbstractMessage message : MessagePool.getInstance().getFatalErrors(true, true)) {
			String text = message.getThrowableText();
			if (needle != null && !text.toLowerCase(Locale.ROOT).contains(needle)) {
				continue;
			}
			String[] lines = text.split("\r?\n");
			StringBuilder error = new StringBuilder().append(message.getDate()).append(message.isRead() ? "" : " (unread)");
			if (message.getMessage() != null && !message.getMessage().isBlank()) {
				error.append(' ').append(message.getMessage());
			}
			for (int i = 0; i < Math.min(lines.length, STACK_LINES); i++) {
				error.append('\n').append(lines[i]);
			}
			if (lines.length > STACK_LINES) {
				error.append("\n\t... ").append(lines.length - STACK_LINES).append(" more lines");
			}
			errors.add(error.toString());
		}
		List<String> last = errors.subList(Math.max(0, errors.size() - Math.max(count, 1)), errors.size());
		return last.isEmpty() ? "(no IDE errors)" : String.join("\n\n", last);
	}

	/**
	 * The newest UI freezes, from the IDE's {@code threadDumps-freeze-<date>-<time>-<build>[-<culprit>]-<n>sec} folders,
	 * each with the UI thread's stack from its first thread dump.
	 */
	static String freezes(int count, String contains) {
		return freezes(Path.of(PathManager.getLogPath()), count, contains);
	}

	static String freezes(Path logDir, int count, String contains) {
		List<Path> folders;
		try (Stream<Path> list = Files.list(logDir)) {
			folders = list.filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith(FREEZE_PREFIX))
					.sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed()).toList();
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot list " + logDir, e);
		}
		String needle = contains == null ? null : contains.toLowerCase(Locale.ROOT);
		List<String> freezes = new ArrayList<>();
		for (Path folder : folders) {
			if (freezes.size() == Math.max(count, 1)) {
				break;
			}
			String stack = uiThreadStack(folder);
			if (needle != null && !stack.toLowerCase(Locale.ROOT).contains(needle)) {
				continue;
			}
			freezes.add(folder.getFileName().toString().substring(FREEZE_PREFIX.length()) + "\n" + stack);
		}
		return freezes.isEmpty() ? "(no matching freezes in " + logDir + ")" : String.join("\n\n", freezes);
	}

	/** The "AWT-EventQueue" thread's state and the top of its stack in the folder's first thread dump. */
	private static String uiThreadStack(Path folder) {
		try (Stream<Path> files = Files.list(folder)) {
			Path dump = files.filter(p -> p.getFileName().toString().startsWith("threadDump-")).sorted().findFirst().orElse(null);
			if (dump == null) {
				return "\t(no thread dump, only " + String.join(", ", fileNames(folder)) + ")";
			}
			List<String> lines = Files.readAllLines(dump, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				if (lines.get(i).startsWith("\"AWT-EventQueue")) {
					StringBuilder stack = new StringBuilder();
					for (int j = i; j < Math.min(lines.size(), i + 2 + STACK_LINES) && !lines.get(j).isBlank(); j++) {
						stack.append(j == i ? "" : "\n").append(lines.get(j));
					}
					return stack.toString();
				}
			}
			return "\t(no AWT-EventQueue thread in " + dump.getFileName() + ")";
		} catch (IOException e) {
			return "\t(cannot read: " + e.getMessage() + ")";
		}
	}

	private static List<String> fileNames(Path folder) throws IOException {
		try (Stream<Path> files = Files.list(folder)) {
			return files.map(p -> p.getFileName().toString()).sorted().toList();
		}
	}

	/** The notifications of the open projects and the application (the Notifications tool window), oldest first. */
	static String notifications(int count, String contains) {
		List<String> notifications = onUiThread(() -> {
			Set<Notification> all = new LinkedHashSet<>(ActionCenter.getNotifications(null));
			for (Project project : ProjectManager.getInstance().getOpenProjects()) {
				all.addAll(ActionCenter.getNotifications(project));
			}
			return all.stream().sorted(Comparator.comparingLong(Notification::getTimestamp)).map(n -> {
				StringBuilder text = new StringBuilder(TIME.format(Instant.ofEpochMilli(n.getTimestamp())))
						.append(' ').append(n.getType()).append(" [").append(n.getGroupId()).append("] ").append(html(n.getTitle()));
				if (n.getSubtitle() != null && !n.getSubtitle().isBlank()) {
					text.append(" / ").append(html(n.getSubtitle()));
				}
				if (!n.getContent().isBlank()) {
					text.append(": ").append(html(n.getContent()));
				}
				return (n.isExpired() ? "(expired) " : "") + text;
			}).toList();
		});
		List<String> last = last(matching(notifications, contains), count);
		return last.isEmpty() ? "(no notifications)" : String.join("\n", last);
	}

	/** The last lines the IDE process wrote to stdout and stderr since the plugin started; stderr lines start with [err]. */
	static String output(int lines, String contains) {
		List<String> last = last(matching(OutputCapture.lines(), contains), lines);
		return last.isEmpty() ? "(no stdout/stderr output)" : String.join("\n", last);
	}

	/** The last lines of each Run/Debug console of the open projects, under its tab's name. */
	static String consoles(int lines, String contains) {
		List<String> result = new ArrayList<>();
		for (Console console : consoleTexts()) {
			String header = "== " + console.title() + (console.running() ? " (running)" : "");
			if (console.text() == null) {
				result.add(header + "\n(" + console.kind() + ", not readable)");
				continue;
			}
			List<String> last = last(matching(List.of(console.text().split("\r?\n")), contains), lines);
			result.add(header + (last.isEmpty() ? "\n(no matching lines)" : "\n" + String.join("\n", last)));
		}
		return result.isEmpty() ? "(no Run or Debug consoles)" : String.join("\n\n", result);
	}

	/** A Run/Debug tab's console: its text, or null with the console's class name when it cannot be read. */
	record Console(String title, boolean running, String text, String kind) {
	}

	/** The Run/Debug consoles of the open projects. */
	static List<Console> consoleTexts() {
		return onUiThread(() -> {
			List<Console> result = new ArrayList<>();
			for (Project project : ProjectManager.getInstance().getOpenProjects()) {
				for (RunContentDescriptor descriptor : RunContentManager.getInstance(project).getAllDescriptors()) {
					ExecutionConsole console = descriptor.getExecutionConsole();
					if (console instanceof BaseTestsOutputConsoleView tests) {
						console = tests.getConsole();
					}
					boolean running = descriptor.getProcessHandler() != null && !descriptor.getProcessHandler().isProcessTerminated();
					String text = null;
					if (console instanceof ConsoleViewImpl view) {
						view.flushDeferredText();
						text = view.getText();
					} else if (console instanceof TerminalExecutionConsole terminal) { // e.g. Shell Script configurations
						text = terminal.getTerminalWidget().getText().stripTrailing();
					}
					result.add(new Console(descriptor.getDisplayName(), running, text,
							console == null ? "no console" : console.getClass().getSimpleName()));
				}
			}
			return result;
		});
	}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

	private static String html(String text) {
		return StringUtil.removeHtmlTags(text, true).replaceAll("\\s+", " ").trim();
	}

	private static <T> T onUiThread(Supplier<T> supplier) {
		AtomicReference<T> result = new AtomicReference<>();
		ApplicationManager.getApplication().invokeAndWait(() -> result.set(supplier.get()), ModalityState.any());
		return result.get();
	}

	private static List<String> matching(List<String> lines, String contains) {
		if (contains == null) {
			return lines;
		}
		String needle = contains.toLowerCase(Locale.ROOT);
		return lines.stream().filter(line -> line.toLowerCase(Locale.ROOT).contains(needle)).toList();
	}

	private static List<String> last(List<String> lines, int count) {
		return lines.subList(Math.max(0, lines.size() - Math.max(count, 1)), lines.size());
	}

	static String tail(int lines, String contains) {
		Path log = Path.of(PathManager.getLogPath(), "idea.log");
		String text;
		try (RandomAccessFile file = new RandomAccessFile(log.toFile(), "r")) {
			long start = Math.max(0, file.length() - MAX_BYTES);
			byte[] bytes = new byte[(int) (file.length() - start)];
			file.seek(start);
			file.readFully(bytes);
			text = new String(bytes, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException("Cannot read " + log, e);
		}
		String needle = contains == null ? null : contains.toLowerCase(Locale.ROOT);
		List<String> matching = new ArrayList<>();
		for (String line : text.split("\r?\n")) {
			if (needle == null || line.toLowerCase(Locale.ROOT).contains(needle)) {
				matching.add(line);
			}
		}
		List<String> last = matching.subList(Math.max(0, matching.size() - Math.max(lines, 1)), matching.size());
		return last.isEmpty() ? "(no matching lines in " + log + ")" : String.join("\n", last);
	}
}
