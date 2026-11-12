package com.fsodomka.idepilot;

import com.intellij.util.ExceptionUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Keeps the last exceptions the IDE logged as errors (those of its red balloon), from plugin start on: the IDE's own
 * list of them, MessagePool, is internal API.
 */
final class ErrorCapture extends Handler {
	static final int MAX_ERRORS = 100;
	private static final Deque<Entry> entries = new ArrayDeque<>();
	private static boolean installed;

	/** A logged error: when, its message (may be null) and its exception's stack trace. */
	record Entry(long millis, String message, String stack) {
	}

	static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		// the root logger: the IDE's loggers pass their records up to it, as to the handler that fills MessagePool
		Logger.getLogger("").addHandler(new ErrorCapture());
	}

	/** The last errors, oldest first. */
	static synchronized List<Entry> entries() {
		return new ArrayList<>(entries);
	}

	private static synchronized void add(Entry entry) {
		if (entries.size() == MAX_ERRORS) {
			entries.removeFirst();
		}
		entries.addLast(entry);
	}

	@Override
	public void publish(LogRecord record) {
		Throwable thrown = record.getThrown();
		if (thrown != null && record.getLevel().intValue() >= Level.SEVERE.intValue()) {
			add(new Entry(record.getMillis(), record.getMessage(), ExceptionUtil.getThrowableText(thrown)));
		}
	}

	@Override
	public void flush() {
	}

	@Override
	public void close() {
	}
}
