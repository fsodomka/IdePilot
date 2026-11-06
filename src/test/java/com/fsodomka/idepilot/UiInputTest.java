package com.fsodomka.idepilot;

import org.junit.Test;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class UiInputTest {
	@Test
	public void plainKeys() {
		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), UiInput.keyStroke("Enter"));
		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), UiInput.keyStroke("Back_Space"));
		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), UiInput.keyStroke(" down "));
		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0), UiInput.keyStroke("F7"));
	}

	@Test
	public void modifiersBySpaceOrPlus() {
		KeyStroke expected = KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
		assertEquals(expected, UiInput.keyStroke("ctrl shift A"));
		assertEquals(expected, UiInput.keyStroke("ctrl+shift+a"));
		assertEquals(expected, UiInput.keyStroke("Control Shift a"));
	}

	@Test
	public void cmdIsMeta() {
		assertEquals(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.META_DOWN_MASK), UiInput.keyStroke("cmd S"));
	}

	@Test
	public void unknownKeyFails() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> UiInput.keyStroke("ctrl NoSuchKey"));
		assertTrue(e.getMessage(), e.getMessage().contains("Unknown key ctrl NoSuchKey"));
	}
}
