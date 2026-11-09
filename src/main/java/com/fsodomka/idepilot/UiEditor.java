package com.fsodomka.idepilot;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass;
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.impl.EditorComponentImpl;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;

import javax.swing.SwingUtilities;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads an editor (text, caret, selection, highlights) and lists or applies its Alt+Enter actions. */
final class UiEditor {
	private static final int MAX_LINES = 300;
	private static final Pattern RANGE = Pattern.compile("\\s*(\\d{1,9})\\s*(?:-\\s*(\\d{1,9}))?\\s*");
	private static final Pattern POSITION = Pattern.compile("\\s*(\\d{1,9})\\s*(?::\\s*(\\d{1,9}))?\\s*");

	private UiEditor() {
	}

	record Request(String ref, String lines, String caret, boolean problems, boolean actions, String apply) {
	}

	static String editor(Request request) {
		Component component = request.ref() == null ? null : UiSnapshot.component(request.ref());
		// write-safe in the editor's modality: moving the caret and applying a fix change the editor
		return Edt.await(() -> run(component, request), Edt.modality(component), 30_000,
				"No result after 30 s; a dialog may block the editor",
				cause -> cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause.toString(), cause));
	}

	@SuppressWarnings("DialogTitleCapitalization") // the action's own text names its undo command, as Alt+Enter does
	private static String run(Component component, Request request) {
		// the ref itself must be (inside) an editor: a data context elsewhere resolves to the frame's selected editor
		Editor editor = component == null ? selected()
				: component instanceof EditorComponentImpl own ? own.getEditor()
				: SwingUtilities.getAncestorOfClass(EditorComponentImpl.class, component) instanceof EditorComponentImpl outer ? outer.getEditor()
				: null;
		if (editor == null) {
			throw new IllegalArgumentException(component == null ? "No text editor is open; use pilot_open"
					: "Ref " + request.ref() + " is not an editor");
		}
		Project project = editor.getProject();
		Document document = editor.getDocument();
		if (request.caret() != null) {
			int[] at = position(request.caret(), document.getLineCount());
			editor.getCaretModel().removeSecondaryCarets();
			editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(at[0], at[1]));
			editor.getSelectionModel().removeSelection();
			editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
		}
		PsiFile file = project == null ? null : PsiDocumentManager.getInstance(project).getPsiFile(document);
		StringBuilder out = new StringBuilder(header(editor, file));
		if (request.apply() != null) {
			IntentionAction action = find(actions(editor, file), request.apply());
			// later: the action may open a dialog or popup (rename, choose), which would block this request
			ApplicationManager.getApplication().invokeLater(
					() -> ShowIntentionActionsHandler.chooseActionAndInvoke(file, editor, action, action.getText()),
					Edt.modality(editor.getContentComponent()));
			return out.append("\nApplying ").append(action.getText()).append("; it may open a dialog or popup").toString();
		}
		if (request.lines() != null || !request.problems() && !request.actions()) {
			text(out, document, request.lines());
		}
		if (request.problems()) {
			problems(out, project, file, document);
		}
		if (request.actions()) {
			Map<String, List<IntentionAction>> groups = actions(editor, file);
			out.append("\nactions at the caret:");
			groups.forEach((group, list) -> list.forEach(a -> out.append("\n  ").append(a.getText()).append(" (").append(group).append(')')));
			if (groups.values().stream().allMatch(List::isEmpty)) {
				out.append(" (none)");
			}
		}
		return out.toString();
	}

	private static Editor selected() {
		Project project = Evaluator.project(null);
		return project == null ? null : FileEditorManager.getInstance(project).getSelectedTextEditor();
	}

	private static String header(Editor editor, PsiFile file) {
		Document document = editor.getDocument();
		LogicalPosition caret = editor.getCaretModel().getLogicalPosition();
		StringBuilder header = new StringBuilder(file != null ? file.getName() : "(no file)")
				.append(" – ").append(document.getLineCount()).append(" lines, caret ")
				.append(caret.line + 1).append(':').append(caret.column + 1);
		SelectionModel selection = editor.getSelectionModel();
		if (selection.hasSelection()) {
			header.append(", selection ").append(at(document, selection.getSelectionStart())).append('-')
					.append(at(document, selection.getSelectionEnd()));
		}
		return header.toString();
	}

	private static void text(StringBuilder out, Document document, String lines) {
		int[] range = range(lines, document.getLineCount());
		int first = range[0];
		int last = range[1];
		int shown = Math.min(last, first + MAX_LINES - 1);
		int width = String.valueOf(shown).length();
		for (int line = first; line <= shown; line++) {
			int start = document.getLineStartOffset(line - 1);
			int end = document.getLineEndOffset(line - 1);
			out.append('\n').append(String.format(Locale.ROOT, "%" + width + "d", line)).append("| ")
					.append(document.getCharsSequence(), start, end);
		}
		if (shown < last) {
			out.append("\n... lines ").append(shown + 1).append('-').append(last).append(" not shown; pass lines");
		}
	}

	private static void problems(StringBuilder out, Project project, PsiFile file, Document document) {
		List<String> found = new ArrayList<>();
		if (project != null) {
			DaemonCodeAnalyzerEx.processHighlights(document, project, HighlightSeverity.WEAK_WARNING, 0, document.getTextLength(),
					(HighlightInfo info) -> {
						if (info.getDescription() != null) {
							found.add(at(document, info.getStartOffset()) + " " + info.getSeverity().getName() + ": " + info.getDescription());
						}
						return true;
					});
		}
		boolean finished = project != null && file != null && DaemonCodeAnalyzerEx.getInstanceEx(project).isErrorAnalyzingFinished(file);
		out.append("\nproblems").append(finished ? "" : " (analysis not finished; pilot_wait_for idle, then ask again)").append(':');
		found.forEach(p -> out.append("\n  ").append(p));
		if (found.isEmpty()) {
			out.append(" (none)");
		}
	}

	/** The Alt+Enter actions at the caret, by kind, as the IDE would show them. */
	private static Map<String, List<IntentionAction>> actions(Editor editor, PsiFile file) {
		if (file == null) {
			throw new IllegalArgumentException("The editor has no file, so no actions");
		}
		ShowIntentionsPass.IntentionsInfo info = ProgressManager.getInstance().runProcessWithProgressSynchronously(
				() -> ApplicationManager.getApplication().runReadAction((Computable<ShowIntentionsPass.IntentionsInfo>) () -> ShowIntentionsPass.getActionsToShow(editor, file)),
				"Collecting Actions", true, file.getProject());
		Map<String, List<IntentionAction>> groups = new LinkedHashMap<>();
		groups.put("error fix", info.errorFixesToShow.stream().map(HighlightInfo.IntentionActionDescriptor::getAction).toList());
		groups.put("inspection fix", info.inspectionFixesToShow.stream().map(HighlightInfo.IntentionActionDescriptor::getAction).toList());
		groups.put("intention", info.intentionsToShow.stream().map(HighlightInfo.IntentionActionDescriptor::getAction).toList());
		return groups;
	}

	/** The action with exactly this text, else the only one equal ignoring case; else fails listing them. */
	private static IntentionAction find(Map<String, List<IntentionAction>> groups, String text) {
		List<IntentionAction> all = groups.values().stream().flatMap(List::stream).toList();
		for (IntentionAction action : all) {
			if (action.getText().equals(text)) {
				return action;
			}
		}
		List<IntentionAction> loose = all.stream().filter(a -> a.getText().equalsIgnoreCase(text.trim())).toList();
		if (loose.size() == 1) {
			return loose.getFirst();
		}
		throw new IllegalArgumentException("No action " + text + " at the caret; actions: "
				+ (all.isEmpty() ? "(none)" : String.join(", ", all.stream().map(IntentionAction::getText).toList())));
	}

	/** The 1-based first and last line to show from "N" or "N-M" (null: all), the last clipped to the document's. */
	static int[] range(String lines, int lineCount) {
		int count = Math.max(lineCount, 1);
		if (lines == null) {
			return new int[]{1, count};
		}
		Matcher m = RANGE.matcher(lines);
		if (!m.matches()) {
			throw new IllegalArgumentException("lines must be N or N-M, e.g. 10-40");
		}
		int first = Integer.parseInt(m.group(1));
		int last = m.group(2) == null ? first : Integer.parseInt(m.group(2));
		if (first < 1 || first > last || first > count) {
			throw new IllegalArgumentException("lines " + lines + " outside 1-" + count);
		}
		return new int[]{first, Math.min(last, count)};
	}

	/** 0-based line and column from "LINE" or "LINE:COLUMN" (1-based). */
	static int[] position(String text, int lineCount) {
		Matcher m = POSITION.matcher(text);
		if (!m.matches()) {
			throw new IllegalArgumentException("caret must be LINE or LINE:COLUMN, e.g. 12:5");
		}
		int line = Integer.parseInt(m.group(1));
		int column = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
		if (line < 1 || line > Math.max(lineCount, 1) || column < 1) {
			throw new IllegalArgumentException("caret " + text + " outside lines 1-" + Math.max(lineCount, 1));
		}
		return new int[]{line - 1, column - 1};
	}

	/** LINE:COLUMN, 1-based, of an offset. */
	private static String at(Document document, int offset) {
		int line = document.getLineNumber(offset);
		return (line + 1) + ":" + (offset - document.getLineStartOffset(line) + 1);
	}
}
