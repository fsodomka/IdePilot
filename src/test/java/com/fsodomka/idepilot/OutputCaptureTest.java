package com.fsodomka.idepilot;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** The stdout/stderr tee: passes bytes through and collects complete lines into the shared buffer. */
public class OutputCaptureTest {
	private final ByteArrayOutputStream original = new ByteArrayOutputStream();

	private OutputCapture.Tee tee(String prefix) {
		return new OutputCapture.Tee(original, prefix, StandardCharsets.UTF_8);
	}

	private static List<String> last(int count) {
		List<String> lines = OutputCapture.lines();
		return lines.subList(lines.size() - count, lines.size());
	}

	@Test
	public void passesThroughAndCollectsLinesWithPrefixAndWithoutCr() {
		byte[] bytes = "one\r\ntwo\n".getBytes(StandardCharsets.UTF_8);
		tee("[err] ").write(bytes, 0, bytes.length);
		assertArrayEquals(bytes, original.toByteArray());
		assertEquals(List.of("[err] one", "[err] two"), last(2));
	}

	@Test
	public void unfinishedLineWaitsForItsNewline() {
		OutputCapture.Tee tee = tee("");
		byte[] bytes = "partial-6f1c".getBytes(StandardCharsets.UTF_8);
		tee.write(bytes, 0, bytes.length);
		assertFalse(OutputCapture.lines().contains("partial-6f1c"));
		tee.write('\n');
		assertEquals(List.of("partial-6f1c"), last(1));
	}

	@Test
	public void multiByteCharacterSplitAcrossWrites() {
		OutputCapture.Tee tee = tee("");
		byte[] bytes = "price 5 €\n".getBytes(StandardCharsets.UTF_8); // the euro sign is 3 bytes
		for (byte b : bytes) {
			tee.write(b);
		}
		assertEquals(List.of("price 5 €"), last(1));
	}

	@Test
	public void cutsALineWithoutNewlineAtTheCap() {
		OutputCapture.Tee tee = tee("");
		byte[] bytes = "x".repeat(OutputCapture.MAX_LINE_BYTES + 1).getBytes(StandardCharsets.UTF_8);
		tee.write(bytes, 0, bytes.length);
		assertEquals(OutputCapture.MAX_LINE_BYTES + 1, last(1).get(0).length());
		byte[] rest = "tail-9a2\n".getBytes(StandardCharsets.UTF_8);
		tee.write(rest, 0, rest.length);
		assertEquals(List.of("tail-9a2"), last(1));
	}

	@Test
	public void keepsOnlyTheLastLines() {
		OutputCapture.Tee tee = tee("");
		for (int i = 0; i <= OutputCapture.MAX_LINES; i++) {
			byte[] bytes = ("cap " + i + "\n").getBytes(StandardCharsets.UTF_8);
			tee.write(bytes, 0, bytes.length);
		}
		List<String> lines = OutputCapture.lines();
		assertEquals(OutputCapture.MAX_LINES, lines.size());
		assertEquals("cap 1", lines.get(0));
		assertEquals("cap " + OutputCapture.MAX_LINES, lines.get(lines.size() - 1));
	}
}
