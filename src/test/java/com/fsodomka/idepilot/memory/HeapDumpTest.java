package com.fsodomka.idepilot.memory;

import com.sun.management.HotSpotDiagnosticMXBean;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class HeapDumpTest {
	/** Held by a static field through a list, as a leak would be. */
	static final List<Object> CACHE = new ArrayList<>();

	static final class Leaked {
		/** An array type that few JDK objects hold, so that this field is among its holders. */
		final short[] data = new short[3];
	}

	static final class Garbage {
	}

	/** Held weakly through a short chain and strongly through a longer one: the path is the strong one. */
	static final class HeldTwice {
	}

	static final Map<Object, Object> WEAK = new WeakHashMap<>();
	static final List<Object> STRONG = new ArrayList<>();

	/** Held only by a soft reference, which a full GC keeps while memory suffices. */
	static final class SoftlyHeld {
	}

	static final SoftReference<Object> SOFT = new SoftReference<>(new SoftlyHeld());

	private static Path directory;
	private static Path dump;

	@BeforeClass
	public static void dumpThisJvm() throws IOException {
		CACHE.add(new Leaked());
		holdTwice(); // in a method of its own: a local variable here would be a GC root in the dump
		new Garbage(); // unreachable: a live dump runs a full GC first
		directory = Files.createTempDirectory("heapdumptest");
		dump = directory.resolve("heap.hprof");
		ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class).dumpHeap(dump.toString(), true);
	}

	private static void holdTwice() {
		HeldTwice twice = new HeldTwice();
		WEAK.put(twice, "");
		STRONG.add(List.of(List.of(twice)));
	}

	@AfterClass
	public static void deleteDump() throws IOException {
		Files.deleteIfExists(dump); // fails on Windows if the reader left the file mapped
		Files.deleteIfExists(directory);
	}

	@Test
	public void pathEndsAtTheStaticField() throws IOException {
		String path = HeapDump.path(dump, Leaked.class.getName(), 20, holder -> true);
		assertTrue(path, path.startsWith(Leaked.class.getName() + "\n  <- java.lang.Object[0]\n  <- java.util.ArrayList.elementData\n"
				+ "  <- static " + HeapDumpTest.class.getName() + ".CACHE\n"));
	}

	@Test
	public void aThreadIsARootByItself() throws IOException {
		String path = HeapDump.path(dump, Thread.class.getName(), 20, holder -> true);
		assertTrue(path, path.matches("(?s)java\\.lang\\.Thread\n {2}<- the thread object of \"[^\"]+\"\n.*"));
	}

	@Test
	public void unreachableAndUnknownClasses() throws IOException {
		// loaded, but its only instance was garbage
		String garbage = HeapDump.path(dump, Garbage.class.getName(), 20, holder -> true);
		assertEquals("No instance of " + Garbage.class.getName() + " in the heap dump (of live objects)", garbage);
		assertThrows(IllegalArgumentException.class, () -> HeapDump.path(dump, "no.such.Class", 20, holder -> true));
	}

	@Test
	public void arraysListWhatHoldsThem() throws IOException {
		String path = HeapDump.path(dump, "short[]", 50, holder -> true);
		assertTrue(path, path.startsWith("short[]\n  <- "));
		assertTrue(path, path.contains("\nWhat holds short[] objects, as COUNT HOLDER, most first:\n"));
		assertTrue(path, path.contains("\n1\t" + Leaked.class.getName() + ".data"));
		assertThrows(IllegalArgumentException.class, () -> HeapDump.path(dump, "no.such.Class[]", 20, holder -> true));
		String shown = HeapDump.path(dump, "short[]", 50, holder -> !holder.startsWith("java."));
		assertTrue(shown, shown.contains("\n1\t" + Leaked.class.getName() + ".data") && !shown.contains("\tjava."));
	}

	@Test
	public void pathSkipsWeakAndSoftReferences() throws IOException {
		String twice = HeapDump.path(dump, HeldTwice.class.getName(), 20, holder -> true);
		assertTrue(twice, twice.contains("<- static " + HeapDumpTest.class.getName() + ".STRONG\n") && !twice.contains("referent"));
		String soft = HeapDump.path(dump, SoftlyHeld.class.getName(), 20, holder -> true);
		assertTrue(soft, soft.startsWith("No instance of " + SoftlyHeld.class.getName() + " is strongly reachable"));
	}

	@Test
	public void javaNames() {
		assertEquals("java.lang.String", HeapDump.javaName("java/lang/String"));
		assertEquals("java.lang.Object[]", HeapDump.javaName("[Ljava/lang/Object;"));
		assertEquals("byte[][]", HeapDump.javaName("[[B"));
		assertEquals("a.Outer$Inner", HeapDump.javaName("a/Outer$Inner"));
	}
}
