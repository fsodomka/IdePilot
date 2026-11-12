package com.fsodomka.idepilot;

import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.ProgressModel;
import com.intellij.openapi.progress.TaskInfo;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.openapi.wm.ex.StatusBarEx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The background tasks that run: those in the open projects' status bars, then a modal progress, if one runs. */
final class BackgroundTasks {
	static final String MODAL = "modal progress";

	private BackgroundTasks() {
	}

	/** A running task; {@code fraction} is -1 when indeterminate, {@code project} null for a modal progress. */
	record Task(String title, String text, String detail, double fraction, boolean cancellable, String project) {
		boolean matches(String name) {
			String lower = name.toLowerCase(Locale.ROOT);
			return title.toLowerCase(Locale.ROOT).contains(lower) || text != null && text.toLowerCase(Locale.ROOT).contains(lower);
		}

		String line() {
			StringBuilder line = new StringBuilder(UiSnapshot.quote(title));
			if (text != null && !text.equals(title)) {
				line.append(": ").append(text);
			}
			if (detail != null) {
				line.append(" | ").append(detail);
			}
			line.append(fraction < 0 ? " (indeterminate)" : String.format(Locale.ROOT, " (%d%%)", Math.round(fraction * 100)));
			if (cancellable) {
				line.append(" (cancellable)");
			}
			if (project != null) {
				line.append(" [").append(project).append(']');
			}
			return line.toString();
		}
	}

	static List<Task> running() {
		return Edt.compute(() -> {
			List<Task> tasks = new ArrayList<>();
			Set<ProgressIndicator> seen = Collections.newSetFromMap(new IdentityHashMap<>());
			for (Project project : ProjectManager.getInstance().getOpenProjects()) {
				if (!(WindowManager.getInstance().getStatusBar(project) instanceof StatusBarEx statusBar)) {
					continue;
				}
				for (Pair<TaskInfo, ProgressModel> process : statusBar.getBackgroundProcessModels()) {
					ProgressModel model = process.second;
					if (seen.add(model.getProgressIndicator()) && model.isRunning() && !model.isFinished(process.first)
							&& !model.isStopping(process.first)) {
						tasks.add(task(firstNonBlank(process.first.getTitle(), firstNonBlank(model.getText(),
								model.getProgressIndicator().getClass().getSimpleName())), model.getText(), model.getDetails(),
								model.isIndeterminate() ? -1 : model.getFraction(), model.isCancellable(), project.getName()));
					}
				}
			}
			// a modal progress is in no status bar, and the public API tells only that one runs: pilot_snapshot shows its dialog
			if (ProgressManager.getInstance().hasModalProgressIndicator()) {
				tasks.add(task(MODAL, null, null, -1, false, null));
			}
			return tasks;
		});
	}

	private static Task task(String title, String text, String detail, double fraction, boolean cancellable, String project) {
		return new Task(title, blankToNull(text), blankToNull(detail), fraction, cancellable, project);
	}

	private static String firstNonBlank(String text, String fallback) {
		return StringUtil.isEmptyOrSpaces(text) ? fallback : text.strip();
	}

	private static String blankToNull(String text) {
		return StringUtil.isEmptyOrSpaces(text) ? null : text.strip();
	}

	/** One line per running task, at most {@code count}, only those whose line contains {@code contains}. */
	static String tasks(int count, String contains) {
		List<String> lines = running().stream().map(Task::line)
				.filter(line -> contains == null || line.toLowerCase(Locale.ROOT).contains(contains.toLowerCase(Locale.ROOT)))
				.toList();
		if (lines.isEmpty()) {
			return contains == null ? "No background task runs" : "No running background task contains \"" + contains + "\"";
		}
		List<String> shown = lines.subList(0, Math.min(count, lines.size()));
		return String.join("\n", shown) + (lines.size() > shown.size() ? "\n... " + (lines.size() - shown.size()) + " more" : "");
	}

	/** What runs that {@code name} waits for (a task whose title or text contains it), or null when none does. */
	static String missing(String name) {
		return running().stream().filter(task -> task.matches(name)).findFirst()
				.map(task -> "background task " + UiSnapshot.quote(task.title()) + " is running").orElse(null);
	}
}
