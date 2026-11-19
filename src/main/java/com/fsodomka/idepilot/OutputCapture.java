package com.fsodomka.idepilot;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;

/** Keeps the last lines the IDE process wrote to System.out and System.err, from plugin start on. */
final class OutputCapture {
	static final int MAX_LINES = 5000;
	static final int MAX_LINE_BYTES = 8192;
	private static final Deque<String> lines = new ArrayDeque<>();
	private static final ThreadLocal<RoutedOutput> routedOutput = new ThreadLocal<>();
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

	/** Route this thread's direct System.out/System.err writes to the current script as well as the IDE log. */
	static <T> T withScriptOutput(Writer output, Callable<T> evaluation) throws Exception {
		RoutedOutput previous = routedOutput.get();
		RoutedOutput current = new RoutedOutput(output);
		routedOutput.set(current);
		try {
			return evaluation.call();
		} finally {
			current.flush();
			if (previous == null) {
				routedOutput.remove();
			} else {
				routedOutput.set(previous);
			}
		}
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

	private static void route(byte[] bytes, int offset, int length, Charset charset) {
		RoutedOutput route = routedOutput.get();
		if (route != null) {
			route.write(bytes, offset, length, charset);
		}
	}

	private static final class RoutedOutput {
		private final Writer output;
		private CharsetDecoder decoder;
		private byte[] pending = new byte[0];

		private RoutedOutput(Writer output) {
			this.output = output;
		}

		private void write(byte[] bytes, int offset, int length, Charset charset) {
			if (length == 0) {
				return;
			}
			// a character can arrive in several writes (write(int), or a chunk cut inside one): keep its incomplete tail
			if (decoder == null || !decoder.charset().equals(charset)) {
				decoder = charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
			}
			ByteBuffer in = ByteBuffer.allocate(pending.length + length).put(pending).put(bytes, offset, length).flip();
			CharBuffer chars = CharBuffer.allocate((int) (in.remaining() * decoder.maxCharsPerByte()) + 1);
			decoder.decode(in, chars, false);
			pending = new byte[in.remaining()];
			in.get(pending);
			try {
				output.write(chars.array(), 0, chars.position());
			} catch (java.io.IOException ignored) {
				// Capturing output must not change whether System.out/System.err can be written.
			}
		}

		private void flush() {
			try {
				output.flush();
			} catch (java.io.IOException ignored) {
				// The script result or error is more useful than a flush failure.
			}
		}
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
			byte[] one = {(byte) b};
			collect(one, 0, 1);
			route(one, 0, 1, charset);
		}

		@Override
		public void write(byte[] bytes, int offset, int length) {
			try {
				original.write(bytes, offset, length);
			} catch (java.io.IOException ignored) {
				// keep collecting even if the console is gone
			}
			collect(bytes, offset, length);
			route(bytes, offset, length, charset);
		}

		@Override
		public void flush() throws java.io.IOException {
			original.flush();
			routeFlush();
		}

		private void routeFlush() {
			RoutedOutput route = routedOutput.get();
			if (route != null) {
				route.flush();
			}
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
