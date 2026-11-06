package com.fsodomka.idepilot;

import java.util.regex.Pattern;

/** Writing values in TOON (Token-Oriented Object Notation) tables with the comma delimiter. */
final class Toon {
	/** Strings that would read as a number, boolean or null unquoted. */
	private static final Pattern LITERAL = Pattern.compile("-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?|true|false|null");

	private Toon() {
	}

	/** {@code text} as a table cell: as is when unambiguous, otherwise quoted with \\ \" \n \r \t escapes. */
	static String value(String text) {
		if (!needsQuotes(text)) {
			return text;
		}
		StringBuilder quoted = new StringBuilder("\"");
		for (char c : text.toCharArray()) {
			switch (c) {
				case '"' -> quoted.append("\\\"");
				case '\\' -> quoted.append("\\\\");
				case '\n' -> quoted.append("\\n");
				case '\r' -> quoted.append("\\r");
				case '\t' -> quoted.append("\\t");
				default -> quoted.append(c);
			}
		}
		return quoted.append('"').toString();
	}

	private static boolean needsQuotes(String text) {
		if (text.isEmpty() || !text.equals(text.strip()) || text.startsWith("-") || LITERAL.matcher(text).matches()) {
			return true;
		}
		for (char c : text.toCharArray()) {
			if (",:\"\\[]{}#".indexOf(c) >= 0 || Character.isISOControl(c)) {
				return true;
			}
		}
		return false;
	}
}
