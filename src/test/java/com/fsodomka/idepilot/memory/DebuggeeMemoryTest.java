package com.fsodomka.idepilot.memory;

import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DebuggeeMemoryTest {
	@Test
	public void grownListsOnlyGrowthMostFirst() {
		Map<String, Long> before = Map.of("a.Session", 10L, "a.Temp", 500L, "byte[]", 100L);
		Map<String, Long> after = Map.of("a.Session", 129L, "a.Temp", 20L, "byte[]", 219L, "a.New", 3L);
		// equal growth by name
		assertEquals(List.of("+119\t129\ta.Session", "+119\t219\tbyte[]", "+3\t3\ta.New"), DebuggeeMemory.grown(before, after, null, false, 10));
		assertEquals(List.of("+119\t129\ta.Session"), DebuggeeMemory.grown(before, after, "SESSION", false, 10));
		assertEquals(List.of("+119\t129\ta.Session"), DebuggeeMemory.grown(before, after, null, false, 1));
	}

	@Test
	public void topSkipsEmptyClasses() {
		Map<String, Long> counts = Map.of("a.B", 5L, "a.C", 0L, "a.D", 7L);
		assertEquals(List.of("7\ta.D", "5\ta.B"), DebuggeeMemory.top(counts, null, false, 10));
	}

	@Test
	public void hideJdkLeavesOutJdkClassesAndTheirArrays() {
		Map<String, Long> counts = Map.of("java.lang.String", 9L, "java.lang.Object[]", 8L, "sun.misc.X", 7L, "byte[]", 6L, "a.B", 5L);
		assertEquals(List.of("6\tbyte[]", "5\ta.B"), DebuggeeMemory.top(counts, null, true, 10));
		assertEquals(List.of("+6\t6\tbyte[]", "+5\t5\ta.B"), DebuggeeMemory.grown(Map.of(), counts, null, true, 10));
	}

	@Test
	public void histogramSumsClassesByJavaName() {
		String output = """
				12345:
				 num     #instances         #bytes  class name (module)
				-------------------------------------------------------
				   1:          1200          98000  [B (java.base@25)
				   2:            50           1200  java.lang.String (java.base@25)
				   3:             3            100  [Ljava.lang.Object; (java.base@25)
				   4:             2             32  a.Session
				   5:             1             16  a.Session
				Total          1256          99348
				""";
		assertEquals(Map.of("byte[]", 1200L, "java.lang.String", 50L, "java.lang.Object[]", 3L, "a.Session", 3L),
				DebuggeeMemory.histogram(output));
		assertThrows(IllegalStateException.class, () -> DebuggeeMemory.histogram("12345:\nAttach failed"));
	}

	@Test
	public void checksArgumentsBeforeTheSession() {
		assertThrows("op path needs class", IllegalArgumentException.class,
				() -> DebuggeeMemory.memory("path", "none", null, null, false, 20, 5));
		assertThrows(IllegalArgumentException.class, () -> DebuggeeMemory.memory("counts", "none", "a.B", null, false, 20, 5));
		assertThrows(IllegalArgumentException.class, () -> DebuggeeMemory.memory("heap", "none", null, null, false, 20, 5));
		assertThrows(IllegalArgumentException.class, () -> DebuggeeMemory.memory(null, "none", null, null, false, 0, 5));
	}
}
