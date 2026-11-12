package com.fsodomka.idepilot;

import org.junit.Test;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The log handler: keeps the records logged as errors with an exception, in the shared buffer. */
public class ErrorCaptureTest {
	private static void publish(Level level, String message, Throwable thrown) {
		LogRecord record = new LogRecord(level, message);
		record.setThrown(thrown);
		new ErrorCapture().publish(record);
	}

	private static ErrorCapture.Entry last() {
		return ErrorCapture.entries().getLast();
	}

	@Test
	public void keepsAnErrorWithItsMessageAndStackTrace() {
		publish(Level.SEVERE, "kept-3c1", new IllegalStateException("boom-3c1"));
		assertEquals("kept-3c1", last().message());
		assertTrue(last().stack(), last().stack().startsWith("java.lang.IllegalStateException: boom-3c1"));
		assertTrue(last().stack(), last().stack().contains("at " + ErrorCaptureTest.class.getName() + ".keepsAnErrorWithItsMessageAndStackTrace"));
	}

	@Test
	public void skipsWarningsAndErrorsWithoutAnException() {
		publish(Level.WARNING, "warning-77d", new IllegalStateException("boom"));
		publish(Level.SEVERE, "bare-77d", null);
		assertFalse(ErrorCapture.entries().stream().anyMatch(e -> e.message().endsWith("-77d")));
	}

	@Test
	public void keepsOnlyTheLastErrors() {
		for (int i = 0; i <= ErrorCapture.MAX_ERRORS; i++) {
			publish(Level.SEVERE, "cap " + i, new IllegalStateException("boom"));
		}
		List<ErrorCapture.Entry> entries = ErrorCapture.entries();
		assertEquals(ErrorCapture.MAX_ERRORS, entries.size());
		assertEquals("cap 1", entries.getFirst().message());
		assertEquals("cap " + ErrorCapture.MAX_ERRORS, last().message());
	}
}
