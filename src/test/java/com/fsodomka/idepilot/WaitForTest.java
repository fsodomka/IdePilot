package com.fsodomka.idepilot;

import org.junit.Test;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** pilot_wait_for's checks that need no running IDE: arguments and a plain time wait. */
public class WaitForTest {
	private static IdeActions.Wait time(double seconds) {
		return new IdeActions.Wait(null, null, false, false, null, seconds);
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
