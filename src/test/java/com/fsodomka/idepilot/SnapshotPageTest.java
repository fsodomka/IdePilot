package com.fsodomka.idepilot;

import org.junit.Test;

import javax.swing.JPanel;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** Paging and the "UI changed" check, over plain components, as headless tests have no windows. */
public class SnapshotPageTest {
	private final List<UiSnapshot.Target> targets = new ArrayList<>();

	{
		for (int i = 0; i < 5; i++) {
			targets.add(new UiSnapshot.Target(new JPanel()));
		}
	}

	@Test
	public void rowRange() {
		// without allRows: the shown rows, none when none show
		assertArrayEquals(new int[]{10, 20}, UiSnapshot.rowRange(500, 10, 20, false));
		assertArrayEquals(new int[]{0, -1}, UiSnapshot.rowRange(500, -1, -1, false));
		// allRows, at most 200 rows: all of them
		assertArrayEquals(new int[]{0, 149}, UiSnapshot.rowRange(150, 100, 110, true));
		assertArrayEquals(new int[]{0, -1}, UiSnapshot.rowRange(0, -1, -1, true));
		// more: 200 around the shown ones, clamped to the ends
		assertArrayEquals(new int[]{216, 415}, UiSnapshot.rowRange(500, 300, 330, true));
		assertArrayEquals(new int[]{0, 199}, UiSnapshot.rowRange(500, 5, 20, true));
		assertArrayEquals(new int[]{300, 499}, UiSnapshot.rowRange(500, 480, 499, true));
		assertArrayEquals(new int[]{0, 199}, UiSnapshot.rowRange(500, -1, -1, true));
	}

	private UiSnapshot.Page page(int offset, int limit) {
		UiSnapshot.Page page = new UiSnapshot.Page(offset, limit, false);
		for (int i = 0; i < targets.size(); i++) {
			page.add(1, "panel \"p" + i + "\"", targets.get(i), "");
		}
		return page;
	}

	@Test
	public void firstPageEndsWithNextOffsetAndRef() {
		UiSnapshot.Page first = page(0, 2);
		String text = first.finish(null);
		String[] lines = text.split("\n");
		assertEquals(3, lines.length);
		assertTrue(lines[0], lines[0].startsWith("  - panel \"p0\" [ref=e"));
		assertTrue(lines[1], lines[1].startsWith("  - panel \"p1\" [ref=e"));
		assertEquals("- ... more: pilot_snapshot offset=2 next=" + first.nextRef, lines[2]);
	}

	@Test
	public void nextPageContinuesFromTheGivenRef() {
		String next = page(0, 2).nextRef;
		String text = page(2, 2).finish(next);
		assertTrue(text, text.startsWith("  - panel \"p2\" [ref=" + next + "]"));
		assertTrue(text, text.contains("offset=4 next="));
	}

	@Test
	public void lastPageHasNoMoreLine() {
		String text = page(4, 2).finish(null);
		assertEquals(1, text.split("\n").length);
		assertTrue(text, text.contains("\"p4\""));
	}

	@Test
	public void pastTheEnd() {
		assertEquals("(no more lines)", page(5, 2).finish(null));
		assertEquals("(no visible windows)", new UiSnapshot.Page(0, 2, false).finish(null));
	}

	@Test
	public void findListsMatchesUnderTheirAncestorsOnce() {
		UiSnapshot.Target[] t = new UiSnapshot.Target[6];
		for (int i = 0; i < t.length; i++) {
			t[i] = new UiSnapshot.Target(new JPanel());
		}
		UiSnapshot.Page page = new UiSnapshot.Page(0, 100, false, "OK");
		page.add(0, "window \"Settings\"", t[0], "");
		page.add(1, "panel \"Left\"", t[1], "");
		page.add(2, "push button \"Cancel\"", t[2], "");
		page.add(1, "panel \"Buttons\"", t[3], "");
		page.add(2, "push button \"OK\"", t[4], "");
		page.add(2, "push button \"Ok then\"", t[5], " (focused)");
		String[] lines = page.finish(null).split("\n");
		assertEquals(4, lines.length);
		assertTrue(lines[0], lines[0].startsWith("- window \"Settings\""));
		assertTrue(lines[1], lines[1].startsWith("  - panel \"Buttons\""));
		assertTrue(lines[2], lines[2].startsWith("    - push button \"OK\""));
		assertTrue(lines[3], lines[3].startsWith("    - push button \"Ok then\""));
	}

	@Test
	public void findMatchesStatesAndPages() {
		UiSnapshot.Page first = new UiSnapshot.Page(0, 1, false, "(focused)");
		first.add(0, "window \"w\"", targets.get(0), "");
		first.add(1, "text \"a\"", targets.get(1), " (focused)");
		String text = first.finish(null);
		assertTrue(text, text.startsWith("- window \"w\""));
		assertTrue(text, text.contains("offset=1 next=" + first.nextRef + " with the same find"));
		assertEquals("(no line contains \"nothing\")", new UiSnapshot.Page(0, 5, false, "nothing").finish(null));
	}

	/** A window with two panels, each holding a matching and a non-matching button. */
	private UiSnapshot.Page findPage(int offset, int limit, List<UiSnapshot.Target> t) {
		UiSnapshot.Page page = new UiSnapshot.Page(offset, limit, false, "ok");
		page.add(0, "window \"w\"", t.get(0), "");
		page.add(1, "panel \"A\"", t.get(1), "");
		page.add(2, "push button \"OK a\"", t.get(2), "");
		page.add(2, "push button \"Cancel a\"", t.get(3), "");
		page.add(1, "panel \"B\"", t.get(4), "");
		page.add(2, "push button \"OK b\"", t.get(5), "");
		page.add(2, "push button \"Cancel b\"", t.get(6), "");
		return page;
	}

	@Test
	public void findPagesThroughMatchesAndTheirAncestors() {
		List<UiSnapshot.Target> t = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			t.add(new UiSnapshot.Target(new JPanel()));
		}
		// listed lines: window, panel A, OK a, panel B, OK b; page 1 breaks between OK a and panel B
		UiSnapshot.Page first = findPage(0, 3, t);
		String one = first.finish(null);
		assertTrue(one, one.contains("\"OK a\"") && !one.contains("panel \"B\""));
		String two = findPage(3, 3, t).finish(first.nextRef);
		String[] lines = two.split("\n");
		assertEquals(2, lines.length); // page 2 starts at panel B; the window above it was on page 1
		assertTrue(lines[0], lines[0].startsWith("  - panel \"B\" [ref=" + first.nextRef + "]"));
		assertTrue(lines[1], lines[1].startsWith("    - push button \"OK b\""));
	}

	@Test
	public void uiChangedFails() {
		String next = page(0, 2).nextRef;
		targets.add(0, new UiSnapshot.Target(new JPanel())); // a component appeared above the page
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> page(2, 2).finish(next));
		assertTrue(e.getMessage(), e.getMessage().contains("The UI changed since the previous page (line 3 is "));
		assertTrue(e.getMessage(), e.getMessage().contains("not " + next));
	}

	@Test
	public void uiShrankFails() {
		String next = page(0, 4).nextRef;
		targets.subList(0, 2).clear();
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> page(4, 2).finish(next));
		assertTrue(e.getMessage(), e.getMessage().contains("line 5 is gone"));
	}
}
