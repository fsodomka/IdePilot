package com.fsodomka.idepilot;

import com.intellij.ide.script.IdeScriptEngine;
import com.intellij.ide.script.IdeScriptEngineManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;

import java.awt.Component;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a Groovy script inside the IDE, like the IDE Scripting Console. */
final class Evaluator {
	private Evaluator() {
	}

	/**
	 * Evaluates {@code script} on the UI thread, with the modality of {@code ref}'s dialog (else non-modal), binding
	 * {@code project} (ref's project, else the focused one, else the first open one) and {@code component} ({@code ref}'s component).
	 */
	static String eval(String script, String ref, double seconds) {
		Component component = ref == null ? null : UiSnapshot.component(ref);
		IdeScriptEngine engine = IdeScriptEngineManager.getInstance().getEngineByFileExtension("groovy", null);
		if (engine == null) {
			throw new IllegalStateException("No Groovy script engine; available: "
					+ IdeScriptEngineManager.getInstance().getEngineInfos().stream().map(info -> info.languageName).toList());
		}
		CompletableFuture<Object> result = new CompletableFuture<>();
		ApplicationManager.getApplication().invokeLater(() -> {
			try {
				engine.setBinding("project", project(component));
				engine.setBinding("component", component);
				result.complete(engine.eval(script));
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		}, component == null ? ModalityState.nonModal() : ModalityState.stateForComponent(component));
		try {
			return String.valueOf(result.get((long) (seconds * 1000), TimeUnit.MILLISECONDS));
		} catch (TimeoutException e) {
			throw new IllegalStateException("No result after " + seconds + " s; the script may still run, or a dialog blocks it");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw new IllegalStateException(cause.getClass().getName() + ": " + cause.getMessage(), cause);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted");
		}
	}

	/** The project of {@code component}, else of the last focused frame, else the first open one. */
	private static Project project(Component component) {
		Project project = component == null ? null : ProjectUtil.getProjectForComponent(component);
		if (project == null) {
			IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
			project = frame == null ? null : frame.getProject();
		}
		if (project != null) {
			return project;
		}
		return Arrays.stream(ProjectManager.getInstance().getOpenProjects()).findFirst().orElse(null);
	}
}
