package com.fsodomka.idepilot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** pilot_wait_for's checks that need no running IDE: arguments and a plain time wait. */
public class WaitForTest {
	private static IdeActions.Wait time(double seconds) {
		return new IdeActions.Wait(null, null, false, false, null, null, seconds);
	}

	@Test
	public void timePastTheMostACallWaitsFails() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> IdeActions.waitFor(time(55), 80));
		assertTrue(e.getMessage(), e.getMessage().contains("several calls"));
	}

	@Test
	public void sessionWithoutConsoleFails() {
		assertThrows(IllegalArgumentException.class,
				() -> IdeActions.waitFor(new IdeActions.Wait(null, null, false, false, null, "app", 0.1), 1));
	}

	@Test
	public void consoleOutputSinceMark() {
		RunConsoles.Mark mark = RunConsoles.Mark.of("old line\nStarted\n");
		assertEquals("new\n", RunConsoles.since("old line\nStarted\nnew\n", mark));
		assertEquals("", RunConsoles.since("old line\nStarted\n", mark));
		// no mark: a new run, all of it is new
		assertEquals("Started\n", RunConsoles.since("Started\n", null));
		// the console dropped its first characters: the old end is found by its tail
		String long_ = "x".repeat(300) + "Started\n";
		assertEquals("Started again\n", RunConsoles.since(long_.substring(100) + "Started again\n", RunConsoles.Mark.of(long_)));
		// cleared: all of it is new
		assertEquals("fresh\n", RunConsoles.since("fresh\n", mark));
		// marked while empty
		assertEquals("first\n", RunConsoles.since("first\n", RunConsoles.Mark.of("")));
	}

	@Test
	public void nothingToWaitForFails() {
		assertThrows(IllegalArgumentException.class, () -> IdeActions.waitFor(time(0), 10));
	}

	@Test
	public void timePastTheTimeoutFails() {
		assertThrows(IllegalArgumentException.class, () -> IdeActions.waitFor(time(5), 1));
	}

	@Test
	public void timeWaitsAtLeastThatLong() throws InterruptedException {
		long start = System.nanoTime();
		String result = IdeActions.waitFor(time(0.3), 5);
		double elapsed = (System.nanoTime() - start) / 1e9;
		assertTrue(result, result.startsWith("Done after"));
		assertTrue("waited " + elapsed + " s", elapsed >= 0.3 && elapsed < 1);
	}
}
