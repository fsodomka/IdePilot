package com.fsodomka.idepilot;

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType;
import com.intellij.openapi.externalSystem.service.notification.ExternalSystemProgressNotificationManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Build tool (Gradle, Maven, ...) syncs, for pilot_wait_for sync: the ones running, and the ones that ended and were
 * not reported yet, so a sync that ended shortly before the wait began still counts.
 */
final class BuildSyncs {
	private BuildSyncs() {
	}

	/** A sync that ended: its build system, project path, outcome and, when it failed, the error; {@code endedAt} in nanoTime. */
	record Ended(String system, String path, String outcome, String error, double seconds, long endedAt) {
		@Override
		public @NotNull String toString() {
			return String.format(Locale.ROOT, "%s sync of %s %s after %.1f s", system, path, outcome, seconds)
					+ (error == null ? "" : ": " + error);
		}
	}

	private record Running(String path, long start) {
	}

	private static final int MAX_ENDED = 20;
	private static final int MAX_ERROR = 2000;
	/** How long before a wait a sync may have ended and still count: older ones precede the reload the wait is for. */
	private static final long MAX_AGE_NANOS = 60_000_000_000L;
	private static final Map<ExternalSystemTaskId, Running> running = new LinkedHashMap<>();
	private static final Map<ExternalSystemTaskId, String> errors = new LinkedHashMap<>();
	private static final List<Ended> ended = new ArrayList<>();

	/** Starts tracking syncs; called once at startup, so syncs before the first wait are seen. */
	static void install() {
		ExternalSystemProgressNotificationManager.getInstance().addNotificationListener(new Listener());
	}

	/** Records the syncs' starts and ends, with the error of those that failed. */
	static final class Listener implements ExternalSystemTaskNotificationListener {
		@Override
		public void onStart(@NotNull String projectPath, @NotNull ExternalSystemTaskId id) {
			if (isSync(id)) {
				synchronized (BuildSyncs.class) {
					running.put(id, new Running(projectPath, System.nanoTime()));
				}
			}
		}

		@Override
		public void onFailure(@NotNull String projectPath, @NotNull ExternalSystemTaskId id, @NotNull Exception e) {
			if (isSync(id)) {
				String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().strip();
				synchronized (BuildSyncs.class) {
					errors.put(id, message.length() > MAX_ERROR ? message.substring(0, MAX_ERROR) + "..." : message);
				}
			}
		}

		@Override
		public void onCancel(@NotNull String projectPath, @NotNull ExternalSystemTaskId id) {
			if (isSync(id)) {
				synchronized (BuildSyncs.class) {
					errors.putIfAbsent(id, null);
				}
			}
		}

		@Override
		public void onEnd(@NotNull String projectPath, @NotNull ExternalSystemTaskId id) {
			if (isSync(id)) {
				end(id, projectPath);
			}
		}
	}

	private static boolean isSync(ExternalSystemTaskId id) {
		return id.getType() == ExternalSystemTaskType.RESOLVE_PROJECT;
	}

	private static synchronized void end(ExternalSystemTaskId id, String projectPath) {
		Running started = running.remove(id);
		boolean failed = errors.containsKey(id);
		String error = errors.remove(id);
		String outcome = !failed ? "succeeded" : error == null ? "was cancelled" : "failed";
		long now = System.nanoTime();
		double seconds = started == null ? 0 : (now - started.start()) / 1e9;
		ended.add(new Ended(id.getProjectSystemId().getReadableName(), projectPath, outcome, error, seconds, now));
		if (ended.size() > MAX_ENDED) {
			ended.removeFirst();
		}
	}

	/** Forgets the syncs that ended over a minute before {@code start} (nanoTime), when a wait for syncs begins. */
	static synchronized void forgetBefore(long start) {
		ended.removeIf(sync -> start - sync.endedAt() > MAX_AGE_NANOS);
	}

	/** What pilot_wait_for sync still waits for, or null when no sync runs and one ended unreported. */
	static synchronized String missing() {
		if (!running.isEmpty()) {
			Running first = running.values().iterator().next();
			return "build sync of " + first.path() + " is running";
		}
		return ended.isEmpty() ? "no build sync has ended since the last pilot_wait_for sync, or in the minute before this one "
				+ "(start one with ide_reload_project)" : null;
	}

	/**
	 * The syncs that ended since the last call, which it marks reported; per project the latest one is the outcome,
	 * earlier ones are marked superseded. Fails when a project's latest sync failed or was cancelled.
	 */
	static synchronized String report() {
		List<Ended> report = List.copyOf(ended);
		ended.clear();
		Map<String, Ended> latest = new LinkedHashMap<>();
		report.forEach(sync -> latest.put(sync.path(), sync));
		StringBuilder out = new StringBuilder();
		for (Ended sync : report) {
			out.append('\n').append(sync).append(latest.get(sync.path()) == sync ? "" : " (superseded)");
		}
		if (latest.values().stream().anyMatch(sync -> !sync.outcome().equals("succeeded"))) {
			throw new IllegalStateException("Build sync did not succeed:" + out);
		}
		return out.toString();
	}
}
