package com.fsodomka.idepilot;

import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The server's token and port. The token is kept in the IDE's password store; the system property
 * {@code idepilot.token} overrides it, e.g. for a runIde sandbox. The port is the system property {@code idepilot.port},
 * else the setting.
 */
final class PilotSettings {
	static final String TOKEN_PROPERTY = "idepilot.token";
	private static final String PORT_PROPERTY = "idepilot.port";
	private static final String PORT_KEY = "com.fsodomka.idepilot.port";
	private static final CredentialAttributes TOKEN =
			new CredentialAttributes(CredentialAttributesKt.generateServiceName("IDE Pilot", "MCP token"));

	private PilotSettings() {
	}

	/** The token from {@code -Didepilot.token}, or null. */
	static String tokenOverride() {
		String token = System.getProperty(TOKEN_PROPERTY);
		return token == null || token.isBlank() ? null : token.strip();
	}

	/** The token in effect, or null when none was generated. May be slow: the password store. */
	static String token() {
		String override = tokenOverride();
		return override != null ? override : PasswordSafe.getInstance().getPassword(TOKEN);
	}

	/** Generates and stores a new token, replacing the old one, which then stops working. May be slow. */
	static String generateToken() {
		byte[] bytes = new byte[24];
		new SecureRandom().nextBytes(bytes);
		String token = HexFormat.of().formatHex(bytes);
		PasswordSafe.getInstance().set(TOKEN, new Credentials("token", token));
		return token;
	}

	static boolean portOverridden() {
		return Integer.getInteger(PORT_PROPERTY) != null;
	}

	static int port() {
		Integer override = Integer.getInteger(PORT_PROPERTY);
		return override != null ? override : PropertiesComponent.getInstance().getInt(PORT_KEY, PilotServer.DEFAULT_PORT);
	}

	static void setPort(int port) {
		PropertiesComponent.getInstance().setValue(PORT_KEY, port, PilotServer.DEFAULT_PORT);
	}
}
