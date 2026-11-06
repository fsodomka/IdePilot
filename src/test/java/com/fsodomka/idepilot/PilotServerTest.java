package com.fsodomka.idepilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.After;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class PilotServerTest {
	@After
	public void clearProperty() {
		System.clearProperty(PilotSettings.TOKEN_PROPERTY);
	}

	@Test
	public void bearerToken() {
		assertTrue(PilotServer.authorized("Bearer abc", "abc"));
		assertTrue(PilotServer.authorized("  Bearer abc \r", "abc"));
		assertFalse(PilotServer.authorized("Bearer abd", "abc"));
		assertFalse(PilotServer.authorized("bearer abc", "abc"));
		assertFalse(PilotServer.authorized("abc", "abc"));
		assertFalse(PilotServer.authorized(null, "abc"));
		assertFalse(PilotServer.authorized("Bearer null", null));
	}

	@Test
	public void tokenOverride() {
		assertNull(PilotSettings.tokenOverride());
		System.setProperty(PilotSettings.TOKEN_PROPERTY, "  ");
		assertNull(PilotSettings.tokenOverride());
		System.setProperty(PilotSettings.TOKEN_PROPERTY, " secret ");
		assertEquals("secret", PilotSettings.tokenOverride());
	}

	@Test
	public void initializeEchoesProtocolVersion() {
		JsonObject params = new JsonObject();
		params.addProperty("protocolVersion", "2025-03-26");
		JsonObject result = PilotServer.dispatch("initialize", params).getAsJsonObject();
		assertEquals("2025-03-26", result.get("protocolVersion").getAsString());
		assertEquals("ide-pilot", result.getAsJsonObject("serverInfo").get("name").getAsString());
		assertTrue(result.getAsJsonObject("capabilities").has("tools"));
		assertEquals("2025-06-18", PilotServer.dispatch("initialize", new JsonObject()).getAsJsonObject().get("protocolVersion").getAsString());
	}

	@Test
	public void ping() {
		assertEquals(new JsonObject(), PilotServer.dispatch("ping", new JsonObject()));
	}

	@Test
	public void toolsList() {
		JsonArray tools = PilotServer.dispatch("tools/list", new JsonObject()).getAsJsonObject().getAsJsonArray("tools");
		Set<String> names = new HashSet<>();
		for (JsonElement tool : tools) {
			JsonObject t = tool.getAsJsonObject();
			names.add(t.get("name").getAsString());
			assertEquals("object", t.getAsJsonObject("inputSchema").get("type").getAsString());
		}
		assertEquals(Set.of("pilot_snapshot", "pilot_click", "pilot_type", "pilot_log", "pilot_action", "pilot_open",
				"pilot_wait_for", "pilot_evaluate", "pilot_screenshot", "pilot_window"), names);
		JsonObject click = tools.asList().stream().map(JsonElement::getAsJsonObject)
				.filter(t -> t.get("name").getAsString().equals("pilot_click")).findFirst().orElseThrow();
		assertEquals("ref", click.getAsJsonObject("inputSchema").getAsJsonArray("required").get(0).getAsString());
	}

	@Test
	public void clickOptions() {
		assertEquals(java.awt.event.MouseEvent.BUTTON1, UiClick.button(null));
		assertEquals(java.awt.event.MouseEvent.BUTTON3, UiClick.button("Right"));
		assertThrows(IllegalArgumentException.class, () -> UiClick.button("back"));
		assertEquals(0, UiClick.modifiers(" "));
		assertEquals(java.awt.event.InputEvent.CTRL_DOWN_MASK | java.awt.event.InputEvent.SHIFT_DOWN_MASK,
				UiClick.modifiers("ctrl+Shift"));
		assertThrows(IllegalArgumentException.class, () -> UiClick.modifiers("hyper"));
		JsonObject args = new JsonObject();
		args.addProperty("ref", "e1");
		args.addProperty("clickCount", 5);
		JsonObject call = new JsonObject();
		call.addProperty("name", "pilot_click");
		call.add("arguments", args);
		JsonObject result = PilotServer.dispatch("tools/call", call).getAsJsonObject();
		assertTrue(result.get("isError").getAsBoolean());
		assertTrue(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("clickCount"));
	}

	@Test
	public void windowSize() {
		assertEquals(new java.awt.Dimension(1280, 800), UiWindow.size(" 1280 x 800 "));
		assertThrows(IllegalArgumentException.class, () -> UiWindow.size("1280,800"));
		assertThrows(IllegalArgumentException.class, () -> UiWindow.size("50x50"));
		assertThrows(IllegalArgumentException.class, () -> UiWindow.window(null, "800x600", true));
	}

	@Test
	public void unknownMethodFails() {
		assertThrows(IllegalArgumentException.class, () -> PilotServer.dispatch("nope", new JsonObject()));
	}

	@Test
	public void toolErrorsAreResultsNotExceptions() {
		JsonObject unknown = call("pilot_nope", new JsonObject());
		assertTrue(unknown.get("isError").getAsBoolean());
		assertEquals("Unknown tool pilot_nope", text(unknown));

		JsonObject arguments = new JsonObject();
		arguments.addProperty("key", "ctrl NoSuchKey");
		JsonObject badKey = call("pilot_type", arguments);
		assertTrue(badKey.get("isError").getAsBoolean());
		assertTrue(text(badKey), text(badKey).startsWith("Unknown key"));

		JsonObject empty = call("pilot_type", new JsonObject());
		assertTrue(empty.get("isError").getAsBoolean());
		assertEquals("Give text, key or both", text(empty));
	}

	private static JsonObject call(String name, JsonObject arguments) {
		JsonObject params = new JsonObject();
		params.addProperty("name", name);
		params.add("arguments", arguments);
		return PilotServer.dispatch("tools/call", params).getAsJsonObject();
	}

	private static String text(JsonObject result) {
		JsonArray content = result.getAsJsonArray("content");
		return content.get(content.size() - 1).getAsJsonObject().get("text").getAsString();
	}
}
