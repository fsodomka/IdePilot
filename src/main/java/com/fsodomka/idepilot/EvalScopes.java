package com.fsodomka.idepilot;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What pilot_evaluate keeps per project between calls: the {@code state} map, and a {@code disposable} per scope that
 * the next run of the same scope disposes first. All of it goes when the project closes.
 */
@Service(Service.Level.PROJECT)
public final class EvalScopes implements Disposable {
	final Map<String, Object> state = new ConcurrentHashMap<>();
	private final Map<String, Disposable> scopes = new HashMap<>(); // EDT only
	private final Map<String, PaintRun> paintRuns = new HashMap<>(); // EDT only
	private Disposable root = Disposer.newDisposable(this, "pilot_evaluate");

	private static final Map<String, PaintRun> PAINT_RUNS = new ConcurrentHashMap<>();
	private static final Map<String, PaintFailure> PAINT_FAILURES = new ConcurrentHashMap<>();

	record PaintFailure(String scope, String location, String message, String scriptName) {
		String screenshotMessage() {
			return "Scope \"" + scope + "\" failed at " + location + " — " + message + "; " + hint();
		}

		/** Logged stacks name the generated script, not the scope or file. */
		String hint() {
			return "see pilot_log errors contains=\"" + scriptName + "\"";
		}
	}

	private record PaintRun(String scope, String scriptName, String displayName) {
	}

	static EvalScopes of(Project project) {
		return project.getService(EvalScopes.class);
	}

	/**
	 * A new Disposable for {@code scope}, after disposing that scope's previous one; with no scope, one disposed only
	 * by reset or when the project closes. Call on the EDT, since disposing may close windows.
	 */
	Disposable renew(String scope) {
		if (scope == null) {
			return Disposer.newDisposable(root, "pilot_evaluate run");
		}
		clearPaintRun(scope);
		Disposable previous = scopes.remove(scope);
		if (previous != null) {
			Disposer.dispose(previous);
		}
		Disposable next = Disposer.newDisposable(root, "pilot_evaluate " + scope);
		scopes.put(scope, next);
		return next;
	}

	/** Tie this run's generated Groovy filename to its named scope, so later Swing errors can be attributed. Call on the EDT. */
	void trackPaint(String scope, String scriptName, String displayName) {
		if (scope == null) {
			return;
		}
		clearPaintRun(scope);
		PaintRun run = new PaintRun(scope, scriptName, displayName);
		paintRuns.put(scope, run);
		PAINT_RUNS.put(scriptName, run);
	}

	/** Disposes {@code scope}'s Disposable, or with null every Disposable and the state; says what it did. Call on the EDT. */
	String reset(String scope) {
		if (scope != null) {
			clearPaintRun(scope);
			Disposable previous = scopes.remove(scope);
			if (previous == null) {
				return "No scope \"" + scope + "\"; scopes: " + scopes.keySet();
			}
			Disposer.dispose(previous);
			return "Disposed scope \"" + scope + "\"";
		}
		for (String name : java.util.List.copyOf(paintRuns.keySet())) {
			clearPaintRun(name);
		}
		int count = scopes.size();
		scopes.clear();
		Disposer.dispose(root);
		root = Disposer.newDisposable(this, "pilot_evaluate");
		state.clear();
		return "Disposed " + count + " scope(s) and cleared state";
	}

	@Override
	public void dispose() {
		for (String name : java.util.List.copyOf(paintRuns.keySet())) {
			clearPaintRun(name);
		}
		scopes.clear();
		state.clear();
	}

	private void clearPaintRun(String scope) {
		PaintRun run = paintRuns.remove(scope);
		if (run != null) {
			PAINT_RUNS.remove(run.scriptName(), run);
			PAINT_FAILURES.entrySet().removeIf(entry -> entry.getValue().scope().equals(scope));
		}
	}

	/** Remember a Swing paint/event exception when its stack points to one of the active scoped script runs. */
	static PaintFailure capturePaintFailure(Throwable thrown) {
		if (thrown == null) {
			return null;
		}
		for (Throwable cause = thrown; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			for (int i = 0; i < cause.getStackTrace().length; i++) {
				StackTraceElement frame = cause.getStackTrace()[i];
				String file = frame.getFileName();
				PaintRun run = file == null ? null : PAINT_RUNS.get(file);
				if (run == null || !isCallbackFrame(frame) || !calledFromSwing(cause.getStackTrace(), i)) {
					continue;
				}
				String message = cause.getMessage();
				if (message == null || message.isBlank()) {
					message = cause.toString();
				}
				PaintFailure failure = new PaintFailure(run.scope(), run.displayName() + ":" + frame.getLineNumber(), message, run.scriptName());
				for (Throwable stack = thrown; stack != null; stack = stack.getCause() == stack ? null : stack.getCause()) {
					for (StackTraceElement scriptFrame : stack.getStackTrace()) {
						if (file.equals(scriptFrame.getFileName()) && scriptFrame.getClassName().contains("$")) {
							PAINT_FAILURES.put(scriptFrame.getClassName(), failure);
						}
					}
				}
				return failure;
			}
		}
		return null;
	}

	/** A Swing/AWT frame below the script's: Swing called the script back, as opposed to the script's own closure failing. */
	private static boolean calledFromSwing(StackTraceElement[] stack, int scriptFrame) {
		for (int i = scriptFrame + 1; i < stack.length; i++) {
			String name = stack[i].getClassName();
			// the event queue itself runs every EDT script: only painting and listener dispatch show Swing calling back
			boolean queue = name.startsWith("java.awt.EventQueue") || name.startsWith("java.awt.EventDispatchThread")
				|| name.startsWith("java.awt.event.InvocationEvent");
			if (!queue && (name.startsWith("java.awt.") || name.startsWith("javax.swing."))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isCallbackFrame(StackTraceElement frame) {
		String method = frame.getMethodName();
		return frame.getClassName().contains("$") || method.equals("paint") || method.equals("paintComponent")
			|| method.equals("actionPerformed") || method.startsWith("mouse") || method.startsWith("key")
			|| method.startsWith("window") || method.startsWith("component") || method.equals("stateChanged")
			|| method.equals("focusGained") || method.equals("focusLost");
	}

	/** The remembered error for a failed custom component in this subtree, if any. */
	static PaintFailure failureFor(Component component) {
		if (component == null || PAINT_FAILURES.isEmpty()) {
			return null;
		}
		PaintFailure failure = PAINT_FAILURES.get(component.getClass().getName());
		if (failure != null) {
			return failure;
		}
		if (component instanceof Container container) {
			for (Component child : container.getComponents()) {
				failure = failureFor(child);
				if (failure != null) {
					return failure;
				}
			}
		}
		return null;
	}

	/** A visible failed custom component, for waits that inspect the UI. Call on the EDT. */
	static PaintFailure visiblePaintFailure() {
		for (Window window : Window.getWindows()) {
			if (window.isShowing()) {
				PaintFailure failure = failureFor(window);
				if (failure != null) {
					return failure;
				}
			}
		}
		return null;
	}
}
