package com.fsodomka.idepilot;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Keeps the last lines the IDE process wrote to System.out and System.err, from plugin start on. */
final class OutputCapture {
	static final int MAX_LINES = 5000;
	static final int MAX_LINE_BYTES = 8192;
	private static final Deque<String> lines = new ArrayDeque<>();
	private static boolean installed;

	private OutputCapture() {
	}

	static synchronized void install() {
		if (installed) {
			return;
		}
		installed = true;
		System.setOut(tee(System.out, ""));
		System.setErr(tee(System.err, "[err] "));
	}

	private static PrintStream tee(PrintStream original, String prefix) {
		Charset charset = original.charset();
		return new PrintStream(new Tee(original, prefix, charset), true, charset);
	}

	/** The last lines, oldest first. */
	static synchronized List<String> lines() {
		return new ArrayList<>(lines);
	}

	private static synchronized void add(String line) {
		if (lines.size() == MAX_LINES) {
			lines.removeFirst();
		}
		lines.addLast(line);
	}

	/** Writes through to the original stream and collects complete lines. */
	static final class Tee extends OutputStream {
		private final OutputStream original;
		private final String prefix;
		private final Charset charset;
		private final ByteArrayOutputStream line = new ByteArrayOutputStream();

		Tee(OutputStream original, String prefix, Charset charset) {
			this.original = original;
			this.prefix = prefix;
			this.charset = charset;
		}

		@Override
		public void write(int b) {
			try {
				original.write(b);
			} catch (java.io.IOException ignored) {
				// keep collecting even if the console is gone
			}
			collect(new byte[]{(byte) b}, 0, 1);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) {
			try {
				original.write(bytes, offset, length);
			} catch (java.io.IOException ignored) {
				// keep collecting even if the console is gone
			}
			collect(bytes, offset, length);
		}

		@Override
		public void flush() throws java.io.IOException {
			original.flush();
		}

		/** Adds each complete line; a line longer than {@link #MAX_LINE_BYTES} is cut there. */
		private synchronized void collect(byte[] bytes, int offset, int length) {
			int start = offset;
			for (int i = offset; i < offset + length; i++) {
				if (bytes[i] == '\n') {
					line.write(bytes, start, i - start);
					emit();
					start = i + 1;
				}
			}
			line.write(bytes, start, offset + length - start);
			if (line.size() > MAX_LINE_BYTES) {
				emit();
			}
		}

		private void emit() {
			String text = line.toString(charset);
			add(prefix + (text.endsWith("\r") ? text.substring(0, text.length() - 1) : text));
			line.reset();
		}
	}
}
