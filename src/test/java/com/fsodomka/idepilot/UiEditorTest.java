package com.fsodomka.idepilot;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class UiEditorTest {
	@Test
	public void range() {
		assertArrayEquals(new int[]{1, 10}, UiEditor.range(null, 10));
		assertArrayEquals(new int[]{1, 1}, UiEditor.range(null, 0)); // an empty document has one line
		assertArrayEquals(new int[]{3, 3}, UiEditor.range("3", 10));
		assertArrayEquals(new int[]{2, 5}, UiEditor.range(" 2 - 5 ", 10));
		assertArrayEquals(new int[]{8, 10}, UiEditor.range("8-40", 10)); // the end is clipped
		assertThrows(IllegalArgumentException.class, () -> UiEditor.range("0", 10));
		assertThrows(IllegalArgumentException.class, () -> UiEditor.range("11", 10));
		assertThrows(IllegalArgumentException.class, () -> UiEditor.range("5-2", 10));
		assertThrows(IllegalArgumentException.class, () -> UiEditor.range("a-b", 10));
	}

	@Test
	public void position() {
		assertArrayEquals(new int[]{2, 0}, UiEditor.position("3", 10));
		assertArrayEquals(new int[]{11, 4}, UiEditor.position(" 12 : 5 ", 12));
		assertArrayEquals(new int[]{0, 0}, UiEditor.position("1", 0));
		assertThrows(IllegalArgumentException.class, () -> UiEditor.position("0", 10));
		assertThrows(IllegalArgumentException.class, () -> UiEditor.position("11", 10));
		assertThrows(IllegalArgumentException.class, () -> UiEditor.position("3:0", 10));
		assertThrows(IllegalArgumentException.class, () -> UiEditor.position("3,5", 10));
		// not a NumberFormatException: too many digits fails the pattern
		assertEquals("caret must be LINE or LINE:COLUMN, e.g. 12:5",
				assertThrows(IllegalArgumentException.class, () -> UiEditor.position("99999999999", 10)).getMessage());
		assertEquals("caret 2 outside lines 1-1", assertThrows(IllegalArgumentException.class, () -> UiEditor.position("2", 0)).getMessage());
	}
}
