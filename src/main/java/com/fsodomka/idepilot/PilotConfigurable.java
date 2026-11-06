package com.fsodomka.idepilot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.JBIntSpinner;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextArea;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nls;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.StringSelection;
import java.util.function.Supplier;

/** Settings | Tools | IDE Pilot: generate the token to paste into the agent's MCP settings, and the port. */
public final class PilotConfigurable implements Configurable {
	private JPanel panel;
	private final JBLabel status = new JBLabel();
	private final JBTextField token = new JBTextField();
	private final JButton generate = new JButton("Generate");
	private final JBTextArea command = new JBTextArea(3, 60);
	private final JBIntSpinner port = new JBIntSpinner(PilotServer.DEFAULT_PORT, 1024, 65535);

	@Override
	public @Nls String getDisplayName() {
		return "IDE Pilot";
	}

	@Override
	public JComponent createComponent() {
		token.setEditable(false);
		command.setEditable(false);
		command.setLineWrap(true);
		generate.addActionListener(e -> generate());
		port.setEnabled(!PilotSettings.portOverridden());
		panel = FormBuilder.createFormBuilder()
				.addLabeledComponent("Server:", status)
				.addLabeledComponent("Token:", row(token, generate, copy(token::getText)))
				.addComponentToRightColumn(hint("Paste the token into your agent's MCP settings as the header "
						+ "\"Authorization: Bearer <token>\". Generate replaces the token: agents using the old one get 401. "
						+ "Without a token the server is off."))
				.addLabeledComponent("Claude Code:", row(command, copy(command::getText)))
				.addLabeledComponent("Port:", port)
				.addComponentFillVertically(new JPanel(), 0)
				.getPanel();
		return panel;
	}

	@Override
	public boolean isModified() {
		return port.getNumber() != PilotSettings.port();
	}

	@Override
	public void apply() {
		if (!PilotSettings.portOverridden()) {
			PilotSettings.setPort(port.getNumber());
		}
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			PilotServer.restart();
			show(PilotSettings.token());
		});
	}

	@Override
	public void reset() {
		port.setNumber(PilotSettings.port());
		ApplicationManager.getApplication().executeOnPooledThread(() -> show(PilotSettings.token()));
	}

	private void generate() {
		if (PilotSettings.tokenOverride() != null) {
			return;
		}
		if (!token.getText().isEmpty() && Messages.showYesNoDialog(panel, "Agents using the current token will get 401 until "
				+ "you paste the new one. Generate a new token?", "IDE Pilot", null) != Messages.YES) {
			return;
		}
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			String generated = PilotSettings.generateToken();
			PilotServer.restart();
			show(generated);
		});
	}

	/** Shows {@code current} and the server state; call off the EDT, after reading the token. */
	private void show(String current) {
		String url = PilotServer.runningUrl();
		ApplicationManager.getApplication().invokeLater(() -> {
			boolean overridden = PilotSettings.tokenOverride() != null;
			token.setText(current == null ? "" : current);
			generate.setEnabled(!overridden);
			generate.setToolTipText(overridden ? "Set by -D" + PilotSettings.TOKEN_PROPERTY : null);
			status.setText(url != null ? "Running at " + url + (overridden ? " (token from -D" + PilotSettings.TOKEN_PROPERTY + ")" : "")
					: current == null ? "Off: generate a token" : "Off: cannot listen on port " + PilotSettings.port() + ", see idea.log");
			command.setText(current == null ? "" : "claude mcp add --scope user --transport http idepilot "
					+ PilotServer.url(PilotSettings.port()) + " --header \"Authorization: Bearer " + current + "\"");
		}, ModalityState.any()); // only updates this page's fields; the page's own modality is unknown before the dialog shows
	}

	/** {@code main} filling the width, with {@code buttons} on its right. */
	private static JPanel row(JComponent main, JComponent... buttons) {
		JPanel right = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		for (JComponent button : buttons) {
			right.add(button);
		}
		JPanel row = new JPanel(new BorderLayout());
		row.add(main, BorderLayout.CENTER);
		row.add(right, BorderLayout.EAST);
		return row;
	}

	private static JButton copy(Supplier<String> text) {
		JButton copy = new JButton("Copy");
		copy.addActionListener(e -> {
			if (!text.get().isEmpty()) {
				CopyPasteManager.getInstance().setContents(new StringSelection(text.get()));
			}
		});
		return copy;
	}

	private static JBLabel hint(String text) {
		JBLabel hint = new JBLabel("<html><body style='width: 420px'>" + StringUtil.escapeXmlEntities(text) + "</body></html>");
		hint.setComponentStyle(UIUtil.ComponentStyle.SMALL);
		hint.setForeground(UIUtil.getContextHelpForeground());
		return hint;
	}
}
