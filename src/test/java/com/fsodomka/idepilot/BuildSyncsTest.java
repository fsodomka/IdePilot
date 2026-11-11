package com.fsodomka.idepilot;

import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** pilot_wait_for sync's bookkeeping, fed the events the platform sends. */
public class BuildSyncsTest {
	private static final ProjectSystemId GRADLE = new ProjectSystemId("GRADLE", "Gradle");
	private final BuildSyncs.Listener listener = new BuildSyncs.Listener();

	@Before
	public void reportEarlierSyncs() {
		try {
			BuildSyncs.report(); // static state: other tests' syncs
		} catch (IllegalStateException ignored) {
			// they failed; reported all the same
		}
	}

	private ExternalSystemTaskId sync(String path, Exception failure) {
		ExternalSystemTaskId id = ExternalSystemTaskId.create(GRADLE, ExternalSystemTaskType.RESOLVE_PROJECT, "test");
		listener.onStart(path, id);
		if (failure != null) {
			listener.onFailure(path, id, failure);
		}
		listener.onEnd(path, id);
		return id;
	}

	@Test
	public void runningSyncIsAwaitedThenReported() {
		assertNotNull(BuildSyncs.missing());
		ExternalSystemTaskId id = ExternalSystemTaskId.create(GRADLE, ExternalSystemTaskType.RESOLVE_PROJECT, "test");
		listener.onStart("/a", id);
		assertEquals("build sync of /a is running", BuildSyncs.missing());
		listener.onEnd("/a", id);
		assertNull(BuildSyncs.missing());
		assertTrue(BuildSyncs.report().startsWith("\nGradle sync of /a succeeded after "));
		assertNotNull("reported once", BuildSyncs.missing());
	}

	@Test
	public void otherTasksAreIgnored() {
		ExternalSystemTaskId id = ExternalSystemTaskId.create(GRADLE, ExternalSystemTaskType.EXECUTE_TASK, "test");
		listener.onStart("/a", id);
		listener.onEnd("/a", id);
		assertNotNull(BuildSyncs.missing());
	}

	@Test
	public void earlierFailureOfAProjectIsSupersededByItsLatestSync() {
		sync("/a", new RuntimeException("broken build"));
		sync("/a", null);
		String[] lines = BuildSyncs.report().strip().split("\n");
		assertEquals(2, lines.length);
		assertTrue(lines[0], lines[0].contains("failed after") && lines[0].endsWith(": broken build (superseded)"));
		assertTrue(lines[1], lines[1].contains("succeeded after") && !lines[1].contains("superseded"));
	}

	@Test
	public void latestFailureOfAnyProjectFails() {
		sync("/a", null);
		sync("/b", new RuntimeException("broken build"));
		IllegalStateException e = assertThrows(IllegalStateException.class, BuildSyncs::report);
		assertTrue(e.getMessage(), e.getMessage().contains("/b failed after") && e.getMessage().contains("broken build"));
	}

	@Test
	public void cancelledSyncFails() {
		ExternalSystemTaskId id = ExternalSystemTaskId.create(GRADLE, ExternalSystemTaskType.RESOLVE_PROJECT, "test");
		listener.onStart("/a", id);
		listener.onCancel("/a", id);
		listener.onEnd("/a", id);
		IllegalStateException e = assertThrows(IllegalStateException.class, BuildSyncs::report);
		assertTrue(e.getMessage(), e.getMessage().contains("was cancelled"));
	}

	@Test
	public void syncsEndedOverAMinuteBeforeTheWaitAreForgotten() {
		sync("/a", new RuntimeException("old"));
		BuildSyncs.forgetBefore(System.nanoTime() + 61_000_000_000L);
		assertNotNull(BuildSyncs.missing());
		sync("/a", null);
		BuildSyncs.forgetBefore(System.nanoTime());
		assertNull(BuildSyncs.missing());
		assertTrue(BuildSyncs.report().contains("succeeded"));
	}
}
