package com.fsodomka.idepilot;

import org.junit.Test;

import javax.swing.JPanel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** TOON cell quoting, and pilot_snapshot's toon format over plain components. */
public class ToonTest {
	@Test
	public void plainValuesStayUnquoted() {
		assertEquals("push button", Toon.value("push button"));
		assertEquals("e12.3", Toon.value("e12.3"));
		assertEquals("a.txt", Toon.value("a.txt"));
	}

	@Test
	public void ambiguousValuesAreQuoted() {
		assertEquals("\"\"", Toon.value(""));
		assertEquals("\" padded\"", Toon.value(" padded"));
		assertEquals("\"a,b\"", Toon.value("a,b"));
		assertEquals("\"C:\\\\x\"", Toon.value("C:\\x"));
		assertEquals("\"say \\\"hi\\\"\"", Toon.value("say \"hi\""));
		assertEquals("\"line\\nbreak\"", Toon.value("line\nbreak"));
		assertEquals("\"-x\"", Toon.value("-x"));
		assertEquals("\"42\"", Toon.value("42"));
		assertEquals("\"true\"", Toon.value("true"));
		assertEquals("\"null\"", Toon.value("null"));
	}

	@Test
	public void pageAsToonTable() {
		SnapshotPage page = new SnapshotPage(0, 2, false, null, true);
		UiRefs.Target window = new UiRefs.Target(new JPanel());
		page.add(0, "window \"Say \\\"hi\\\", C:\"", window, " (active)");
		page.add(1, "scroll bar", new UiRefs.Target(new JPanel()), " (focused) (selected)");
		page.add(1, "panel \"more\"", new UiRefs.Target(new JPanel()), "");
		String[] lines = page.finish(null).split("\n");
		assertEquals("lines[2]{depth,ref,role,name,states}:", lines[0]);
		assertTrue(lines[1], lines[1].matches("  0,e\\d+,window,\"Say \\\\\"hi\\\\\", C:\",active"));
		assertTrue(lines[2], lines[2].matches("  1,e\\d+,scroll bar,\"\",focused selected"));
		assertEquals("nextOffset: 2", lines[3]);
		assertEquals("next: " + page.nextRef, lines[4]);
	}

	@Test
	public void emptyToonPage() {
		assertEquals("lines[0]{depth,ref,role,name,states}:\n", new SnapshotPage(0, 5, false, "nothing", true).finish(null));
	}
}
