package com.fsodomka.idepilot;

import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerEx;
import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfo.IntentionActionDescriptor;
import com.intellij.codeInsight.daemon.impl.ShowIntentionsPass;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.codeInsight.intention.impl.ShowIntentionActionsHandler;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.ScrollType;
import com.intellij.openapi.editor.SelectionModel;
import com.intellij.openapi.editor.impl.EditorComponentImpl;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.wm.IdeFocusManager;
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

	record Request(String ref, String lines, String caret, String select, boolean problems, boolean actions, String apply) {
	}

	static String editor(Request request) {
		Component component = request.ref() == null ? null : UiRefs.component(request.ref());
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
		if (request.caret() != null && request.select() != null) {
			throw new IllegalArgumentException("Give caret or select, not both");
		}
		if (request.caret() != null || request.select() != null) {
			editor.getCaretModel().removeSecondaryCarets();
			if (request.caret() != null) {
				editor.getCaretModel().moveToOffset(offset(document, position(request.caret(), document.getLineCount(), "caret")));
				editor.getSelectionModel().removeSelection();
			} else {
				int[] range = selection(document, request.select());
				// the caret goes to TO, so that a range given backwards is selected backwards, as with Shift+Up
				editor.getCaretModel().moveToOffset(range[1]);
				editor.getCaretModel().getPrimaryCaret().setSelection(Math.min(range[0], range[1]), Math.max(range[0], range[1]));
			}
			editor.getScrollingModel().scrollToCaret(ScrollType.CENTER);
			// as clicking there would: actions run without a ref (pilot_action, pilot_type keys) then act at this caret,
			// not on the focused tool window's selection
			IdeFocusManager.getGlobalInstance().requestFocus(editor.getContentComponent(), true);
		}
		PsiFile file = project == null ? null : PsiDocumentManager.getInstance(project).getPsiFile(document);
		StringBuilder out = new StringBuilder(header(editor, file));
		if (request.apply() != null) {
			IntentionActionDescriptor descriptor = find(actions(editor, file), request.apply());
			IntentionAction action = descriptor.getAction();
			// the Alt+Enter list also offers the fixes of a problem elsewhere on the caret's line; like its popup, apply
			// those at the problem, not at the caret (Insert ';' would go to the caret)
			TextRange range = fixRange(project, document, editor.getCaretModel().getOffset(), action);
			int fixOffset = range != null && !range.containsOffset(editor.getCaretModel().getOffset()) ? range.getStartOffset() : -1;
			// later: the action may open a dialog or popup (rename, choose), which would block this request
			ApplicationManager.getApplication().invokeLater(
					() -> ShowIntentionActionsHandler.chooseActionAndInvoke(file, editor, action, action.getText(), fixOffset),
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
			Map<String, List<IntentionActionDescriptor>> groups = actions(editor, file);
			out.append("\nactions at the caret:");
			groups.forEach((group, list) -> list.forEach(a -> out.append("\n  ").append(a.getAction().getText()).append(" (").append(group).append(')')));
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
		StringBuilder header = new StringBuilder(file != null ? file.getName() : "(no file)")
				.append(" – ").append(document.getLineCount()).append(" lines, caret ")
				.append(at(document, editor.getCaretModel().getOffset()));
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
	private static Map<String, List<IntentionActionDescriptor>> actions(Editor editor, PsiFile file) {
		if (file == null) {
			throw new IllegalArgumentException("The editor has no file, so no actions");
		}
		ShowIntentionsPass.IntentionsInfo info = ProgressManager.getInstance().runProcessWithProgressSynchronously(
				() -> ApplicationManager.getApplication().runReadAction((Computable<ShowIntentionsPass.IntentionsInfo>) () -> ShowIntentionsPass.getActionsToShow(editor, file)),
				"Collecting Actions", true, file.getProject());
		Map<String, List<IntentionActionDescriptor>> groups = new LinkedHashMap<>();
		groups.put("error fix", List.copyOf(info.errorFixesToShow));
		groups.put("inspection fix", List.copyOf(info.inspectionFixesToShow));
		groups.put("intention", List.copyOf(info.intentionsToShow));
		return groups;
	}

	/** Where the problem on the caret's line that offers {@code action} as its fix is; null for an intention. */
	private static TextRange fixRange(Project project, Document document, int caret, IntentionAction action) {
		int line = document.getLineNumber(caret);
		TextRange[] found = new TextRange[1];
		DaemonCodeAnalyzerEx.processHighlights(document, project, null, document.getLineStartOffset(line), document.getLineEndOffset(line),
				(HighlightInfo info) -> {
					found[0] = info.findRegisteredQuickFix((descriptor, range) -> descriptor.getAction() == action ? range : null);
					return found[0] == null;
				});
		return found[0];
	}

	/** The action with exactly this text, else the only one equal ignoring case; else fails listing them. */
	private static IntentionActionDescriptor find(Map<String, List<IntentionActionDescriptor>> groups,
			String text) {
		List<IntentionActionDescriptor> all = groups.values().stream().flatMap(List::stream).toList();
		for (IntentionActionDescriptor descriptor : all) {
			if (descriptor.getAction().getText().equals(text)) {
				return descriptor;
			}
		}
		List<IntentionActionDescriptor> loose = all.stream()
				.filter(d -> d.getAction().getText().equalsIgnoreCase(text.trim())).toList();
		if (loose.size() == 1) {
			return loose.getFirst();
		}
		throw new IllegalArgumentException("No action " + text + " at the caret; actions: "
				+ (all.isEmpty() ? "(none)" : String.join(", ", all.stream().map(d -> d.getAction().getText()).toList())));
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
		return position(text, lineCount, "caret");
	}

	/** {@link #position(String, int)}, naming the argument {@code name} in its errors. */
	static int[] position(String text, int lineCount, String name) {
		Matcher m = POSITION.matcher(text);
		if (!m.matches()) {
			throw new IllegalArgumentException(name + " must be LINE or LINE:COLUMN, e.g. 12:5");
		}
		int line = Integer.parseInt(m.group(1));
		int column = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
		if (line < 1 || line > Math.max(lineCount, 1) || column < 1) {
			throw new IllegalArgumentException(name + " " + text + " outside lines 1-" + Math.max(lineCount, 1));
		}
		return new int[]{line - 1, column - 1};
	}

	/**
	 * The offsets of FROM and TO in "FROM-TO", each LINE or LINE:COLUMN (1-based, TO exclusive, as the header gives a
	 * selection); a column past the end of its line is its end.
	 */
	static int[] selection(Document document, String text) {
		String[] ends = text.split("-", -1);
		if (ends.length != 2) {
			throw new IllegalArgumentException("select must be FROM-TO, each LINE or LINE:COLUMN, e.g. 10:5-12:20");
		}
		int lineCount = document.getLineCount();
		// TO may be the line after the last, the end of the text, so that N-M selects through the last line too
		int[] to = position(ends[1], lineCount + 1, "select");
		return new int[]{offset(document, position(ends[0], lineCount, "select")),
				to[0] >= lineCount ? document.getTextLength() : offset(document, to)};
	}

	/**
	 * The offset of a 0-based line and column, the column counted in characters (a tab is one, as problems give
	 * them), not in the editor's columns; a column past the end of its line is its end.
	 */
	private static int offset(Document document, int[] position) {
		if (document.getLineCount() == 0) {
			return 0;
		}
		int start = document.getLineStartOffset(position[0]);
		return start + Math.min(position[1], document.getLineEndOffset(position[0]) - start);
	}

	/** LINE:COLUMN, 1-based, of an offset. */
	private static String at(Document document, int offset) {
		int line = document.getLineNumber(offset);
		return (line + 1) + ":" + (offset - document.getLineStartOffset(line) + 1);
	}
}
