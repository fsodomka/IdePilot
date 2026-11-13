package com.fsodomka.idepilot;

import com.intellij.execution.process.NopProcessHandler;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** pilot_wait_for's checks that need no running IDE: arguments and a plain time wait. */
public class WaitForTest {
	private static WaitFor.Wait time(double seconds) {
		return new WaitFor.Wait(null, null, false, false, null, false, false, null, null, seconds);
	}

	@Test
	public void timePastTheMostACallWaitsFails() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> WaitFor.until(time(55), 80));
		assertTrue(e.getMessage(), e.getMessage().contains("several calls"));
	}

	@Test
	public void sessionWithoutConsoleFails() {
		assertThrows(IllegalArgumentException.class,
				() -> WaitFor.until(new WaitFor.Wait(null, null, false, false, null, false, false, null, "app", 0.1), 1));
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

	private static RunConsoles.Console console(boolean running, String text) {
		return new RunConsoles.Console("run", "App", running, "", text, "ConsoleViewImpl", null);
	}

	@Test
	public void buildActionsRefreshFirstOthersDoNot() {
		for (String id : new String[] {"CompileDirty", "Compile", "CompileProject", "MakeModule", "BuildArtifact", "Hotswap", "HotSwapAction"}) {
			assertTrue(id, IdeActions.builds(id));
		}
		for (String id : new String[] {"MakeStatic", "BuildMenu", "Bookmarks", "Rerun", "RebuildMenu", "ExpandAll"}) {
			assertFalse(id, IdeActions.builds(id));
		}
	}

	@Test
	public void stoppingProcessIsNotRunning() {
		NopProcessHandler handler = new NopProcessHandler();
		handler.startNotify();
		assertTrue(RunConsoles.running(handler));
		handler.destroyProcess(); // a NopProcessHandler terminates at once; before it does, it is terminating
		assertFalse(RunConsoles.running(handler));
		assertFalse(RunConsoles.running(null));
	}

	@Test
	public void wholeMarkCountsAllUnlessItsRunStoppedSince() {
		String old = "booting\nStarted App\n";
		RunConsoles.Mark running = RunConsoles.Mark.of(console(true, old)).asWhole();
		// a run started by the call before the mark: all its output counts while it runs
		assertEquals(old, RunConsoles.since(console(true, old), running));
		// the marking call stopped it (a rerun): its old Started line must not match
		assertEquals("", RunConsoles.since(console(false, old + "Process finished\n"), running).replace("Process finished\n", ""));
		// it had ended before the mark: all its output still counts
		RunConsoles.Mark ended = RunConsoles.Mark.of(console(false, old)).asWhole();
		assertEquals(old, RunConsoles.since(console(false, old), ended));
	}

	@Test
	public void firstChangeNamesANewLineElseSaysOneWent() {
		assertEquals("- label \"75%\" [ref=e2]", WaitFor.firstChange("- window\n  - label \"50%\" [ref=e2]", "- window\n  - label \"75%\" [ref=e2]"));
		assertEquals("a line went", WaitFor.firstChange("- window\n- label", "- window"));
	}

	@Test
	public void nothingToWaitForFails() {
		assertThrows(IllegalArgumentException.class, () -> WaitFor.until(time(0), 10));
	}

	@Test
	public void timePastTheTimeoutFails() {
		assertThrows(IllegalArgumentException.class, () -> WaitFor.until(time(5), 1));
	}

	@Test
	public void timeWaitsAtLeastThatLong() throws InterruptedException {
		long start = System.nanoTime();
		String result = WaitFor.until(time(0.3), 5);
		double elapsed = (System.nanoTime() - start) / 1e9;
		assertTrue(result, result.startsWith("Done after"));
		assertTrue("waited " + elapsed + " s", elapsed >= 0.3 && elapsed < 1);
	}
}
