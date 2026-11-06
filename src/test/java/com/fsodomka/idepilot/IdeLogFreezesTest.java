package com.fsodomka.idepilot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IdeLogFreezesTest {
	@Rule
	public final TemporaryFolder temp = new TemporaryFolder();

	private Path freeze(String name, String... dumps) throws IOException {
		Path folder = Files.createDirectory(temp.getRoot().toPath().resolve("threadDumps-freeze-" + name));
		for (int i = 0; i < dumps.length; i += 2) {
			Files.writeString(folder.resolve(dumps[i]), dumps[i + 1], StandardCharsets.UTF_8);
		}
		return folder;
	}

	private static String dump(String frame) {
		return """
				"main" prio=0 tid=0x0 nid=0x0 waiting
				\tat java.lang.Object.wait

				"AWT-EventQueue-0" prio=0 tid=0x0 nid=0x0 runnable
				  java.lang.Thread.State: RUNNABLE
				\tat %s
				\tat java.awt.EventDispatchThread.run

				"Other" prio=0
				""".formatted(frame);
	}

	@Test
	public void newestFirstWithUiThreadStack() throws IOException {
		freeze("20261101-100000-IU-242.1-Old-5sec", "threadDump-20261101-100005.txt", dump("com.example.Old.slow"));
		freeze("20261102-100000-IU-242.1-New-12sec",
				"threadDump-20261102-100010.txt", dump("com.example.Later.slow"),
				"threadDump-20261102-100005.txt", dump("com.example.New.slow"));
		Files.createDirectory(temp.getRoot().toPath().resolve("not-a-freeze"));

		String text = IdeLog.freezes(temp.getRoot().toPath(), 5, null);
		String[] freezes = text.split("\n\n");
		assertEquals(2, freezes.length);
		assertTrue(freezes[0], freezes[0].startsWith("20261102-100000-IU-242.1-New-12sec\n\"AWT-EventQueue-0\""));
		assertTrue("first thread dump, by name", freezes[0].contains("com.example.New.slow"));
		assertFalse(freezes[0].contains("Later"));
		assertFalse("stops at the blank line", freezes[0].contains("\"Other\""));
		assertTrue(freezes[1], freezes[1].startsWith("20261101-100000-IU-242.1-Old-5sec\n"));
	}

	@Test
	public void countAndContains() throws IOException {
		freeze("20261101-100000-IU-242.1-5sec", "threadDump-1.txt", dump("com.example.Old.slow"));
		freeze("20261102-100000-IU-242.1-5sec", "threadDump-1.txt", dump("com.example.New.slow"));
		Path dir = temp.getRoot().toPath();

		assertFalse(IdeLog.freezes(dir, 1, null).contains("Old"));
		assertTrue(IdeLog.freezes(dir, 0, null).contains("New")); // at least one
		String old = IdeLog.freezes(dir, 5, "OLD.SLOW");
		assertTrue(old, old.startsWith("20261101-"));
		assertFalse(old.contains("New"));
		assertTrue(IdeLog.freezes(dir, 5, "nowhere").startsWith("(no matching freezes in "));
	}

	@Test
	public void missingDumpOrThread() throws IOException {
		freeze("20261102-100000-IU-242.1-5sec", "report.txt", "x");
		freeze("20261101-100000-IU-242.1-5sec", "threadDump-1.txt", "\"main\" prio=0\n");
		String[] freezes = IdeLog.freezes(temp.getRoot().toPath(), 5, null).split("\n\n");
		assertEquals("20261102-100000-IU-242.1-5sec\n\t(no thread dump, only report.txt)", freezes[0]);
		assertEquals("20261101-100000-IU-242.1-5sec\n\t(no AWT-EventQueue thread in threadDump-1.txt)", freezes[1]);
	}
}
