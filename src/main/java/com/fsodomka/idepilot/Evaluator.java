package com.fsodomka.idepilot;

import com.intellij.ide.impl.ProjectUtil;
import com.intellij.ide.script.IdeScriptEngine;
import com.intellij.ide.script.IdeScriptEngineManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;
import org.jetbrains.annotations.NotNull;

import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.sun.jdi.VirtualMachine;

import javax.script.ScriptEngine;
import java.awt.Component;
import java.io.IOException;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs a Groovy script inside the IDE, like the IDE Scripting Console. */
public final class Evaluator {
	private static final int MAX_OUTPUT_CHARS = 100_000;

	private Evaluator() {
	}

	/**
	 * Evaluates {@code script} on the UI thread by default, or on a pooled thread when {@code background} is true. With
	 * {@code readAction}, the background script runs under a read action. It binds {@code project} (ref's project, else
	 * the focused or first open project) and {@code component} ({@code ref}'s component).
	 * The source is {@code script} or the UTF-8 {@code file} (relative to the project's base path); both modes also bind
	 * {@code state} (the project's map kept between calls) and {@code disposable} (disposed before the next run of the same
	 * {@code scope}, by default the file's base name). {@code reset} first disposes that scope, or with {@code resetAll}
	 * every scope and the state; with neither script nor file, that is all it does.
	 */
	static String eval(String script, String file, String scope, String reset, boolean resetAll, String ref, String session,
					   boolean background, boolean readAction, double seconds) {
		if (ref != null && session != null) {
			throw new IllegalArgumentException("Give ref or session, not both");
		}
		if (background && session != null) {
			throw new IllegalArgumentException("background cannot be used with session; session scripts run on the debugger thread");
		}
		if (readAction && !background) {
			throw new IllegalArgumentException("readAction requires background=true");
		}
		if (script != null && file != null) {
			throw new IllegalArgumentException("Give script or file, not both");
		}
		boolean resetting = reset != null || resetAll;
		if (script == null && file == null && !resetting) {
			throw new IllegalArgumentException("Give script or file");
		}
		Component component = ref == null ? null : UiRefs.component(ref);
		Project project = Edt.compute(() -> session != null ? debugSession(session).getProject() : project(component));
		if (project == null && (scope != null || resetting)) {
			throw new IllegalStateException("scope and reset need an open project");
		}
		Path path = file == null ? null : path(file, project);
		String source;
		try {
			source = path == null ? script : Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new IllegalArgumentException("Cannot read " + path + ": " + e, e);
		}
		String runScope = scope != null || path == null ? scope : baseName(path);
		EvalScopes scopes = project == null ? null : EvalScopes.of(project);
		String resetText = !resetting ? null : Edt.compute(() -> scopes.reset(resetAll ? null : reset));
		if (source == null) {
			return resetText;
		}
		Disposable disposable = scopes == null ? null : Edt.compute(() -> scopes.renew(runScope));
		Map<String, Object> state = scopes == null ? null : scopes.state;
		IdeScriptEngine engine = IdeScriptEngineManager.getInstance().getEngineByFileExtension("groovy", null);
		if (engine == null) {
			throw new IllegalStateException("No Groovy script engine; available: "
					+ IdeScriptEngineManager.getInstance().getEngineInfos().stream().map(info -> info.languageName).toList());
		}
		// A unique internal filename ties delayed Swing stack frames to this scope. Keep the user-facing source name for errors.
		String displayName = path == null ? "script.groovy" : path.toString();
		String scriptName = "pilotEval_" + java.util.UUID.randomUUID().toString().replace("-", "") + ".groovy";
		engine.setBinding(ScriptEngine.FILENAME, scriptName);
		CapturedOutput output = new CapturedOutput();
		engine.setStdOut(output);
		engine.setStdErr(output);
		if (scopes != null && runScope != null) {
			Edt.compute(() -> {
				scopes.trackPaint(runScope, scriptName, displayName);
				return null;
			});
		}
		engine.setBinding("state", state);
		engine.setBinding("disposable", disposable);
		try {
		if (session != null) {
			try {
					return output.withResult(String.valueOf(Evaluator.<Object>onDebuggerThread(session, seconds, debuggee -> {
					engine.setBinding("project", debuggee.session().getProject());
					engine.setBinding("session", debuggee.session());
					engine.setBinding("debugProcess", debuggee.debugProcess());
					engine.setBinding("vm", debuggee.vm());
						return OutputCapture.withScriptOutput(output, () -> engine.eval(source));
					})));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted", e);
			}
		}
			Callable<Object> evaluation = () -> {
				engine.setBinding("project", project);
					engine.setBinding("component", component);
				return OutputCapture.withScriptOutput(output, () -> engine.eval(source));
			};
			if (background) {
				return output.withResult(String.valueOf(runBackground(evaluation, readAction, seconds)));
			}
			return output.withResult(String.valueOf(Edt.await(evaluation, Edt.modality(component), (long) (seconds * 1000),
				"No result after " + seconds + " s; the script may still run, or a dialog blocks it",
				cause -> new IllegalStateException(cause.getClass().getName() + ": " + cause.getMessage(), cause))));
		} catch (IllegalStateException e) {
			String printed = output.finish();
			// Only a paint that throws inside the call reaches here (e.g. a visible frame's paintImmediately). In the IDE's
			// frame a repaint runs after eval returns, so its failure is reported by the next screenshot or UI wait, not by
			// this result: do not try to attach it here, since the script's side effects are already done. See
			// docs/specs/2026-11-13-evaluate-state-and-files.md, 4.13 follow-up.
			EvalScopes.PaintFailure paintFailure = EvalScopes.capturePaintFailure(e);
			if (paintFailure != null) {
				String message = paintFailure.location() + " — " + paintFailure.message() + "; " + paintFailure.hint();
				throw new IllegalStateException(withOutput(printed, message), e);
			}
			String at = scriptLine(e, scriptName, displayName);
			String message = e.getMessage() == null ? e.toString() : e.getMessage().replace(scriptName, displayName);
			message = at == null ? message : message + " (at " + at + ")";
			throw new IllegalStateException(withOutput(printed, message), e);
		} finally {
			output.finish();
		}
	}

	/** Printed output, then the script's result or error; preserves line order and caps retained output. */
	private static String withOutput(String printed, String result) {
		if (printed.isEmpty()) {
			return result;
		}
		if (result.isEmpty()) {
			return printed;
		}
		return printed + (printed.endsWith("\n") ? "" : "\n") + result;
	}

	/** The output of one script run. Both streams share this bounded writer, preserving their order. */
	private static final class CapturedOutput extends Writer {
		private final StringBuilder text = new StringBuilder();
		private boolean truncated;
		private boolean finished;

		String withResult(String result) {
			return withOutput(finish(), result);
		}

		synchronized String finish() {
			if (!finished) {
				finished = true;
				if (truncated) {
					text.append("\n...[output truncated after ").append(MAX_OUTPUT_CHARS).append(" characters]");
				}
			}
			return text.toString();
		}

		@Override
		public synchronized void write(char @NotNull [] chars, int offset, int length) {
			if (finished || length == 0) {
				return;
			}
			int accepted = Math.min(length, MAX_OUTPUT_CHARS - text.length());
			text.append(chars, offset, accepted);
			if (accepted < length) {
				truncated = true;
			}
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
			finish();
		}
	}

	/** Creates a read-only Groovy predicate runner bound to the focused project's context and state. */
	static Condition condition(String expression) {
		if (expression == null || expression.isBlank()) {
			throw new IllegalArgumentException("Give a non-empty evaluate condition");
		}
		Project project = Edt.compute(() -> project(null));
		Map<String, Object> state = project == null ? null : EvalScopes.of(project).state;
		IdeScriptEngine engine = IdeScriptEngineManager.getInstance().getEngineByFileExtension("groovy", null);
		if (engine == null) {
			throw new IllegalStateException("No Groovy script engine; available: "
				+ IdeScriptEngineManager.getInstance().getEngineInfos().stream().map(info -> info.languageName).toList());
		}
		String name = "pilot_wait_for-evaluate.groovy";
		Disposable disposable = Disposer.newDisposable("pilot_wait_for evaluate");
		engine.setBinding(ScriptEngine.FILENAME, name);
		engine.setBinding("project", project);
		engine.setBinding("component", null);
		engine.setBinding("state", state);
		engine.setBinding("disposable", disposable);
		return new Condition(engine, expression, name, disposable);
	}

	/** Repeatedly evaluates one predicate on a pooled thread with a read action held for each poll. */
	static final class Condition implements AutoCloseable {
		private final IdeScriptEngine engine;
		private final String source;
		private final String name;
		private final Disposable disposable;

		private Condition(IdeScriptEngine engine, String source, String name, Disposable disposable) {
			this.engine = engine;
			this.source = source;
			this.name = name;
			this.disposable = disposable;
		}

		boolean test(double seconds) {
			try {
				return truthy(runBackground(() -> engine.eval(source), true, seconds));
			} catch (IllegalStateException e) {
				String at = scriptLine(e, name, name);
				throw at == null ? e : new IllegalStateException(e.getMessage() + " (at " + at + ")", e);
			}
		}

		@Override
		public void close() {
			Edt.compute(() -> {
				Disposer.dispose(disposable);
				return null;
			});
		}
	}

	/** Runs a script on a pooled thread, optionally holding a read action, and waits up to {@code seconds}. */
	private static Object runBackground(Callable<Object> evaluation, boolean readAction, double seconds) {
		var app = ApplicationManager.getApplication();
		// Not Callable: executeOnPooledThread(Callable) logs a failure as an IDE error and returns null, hiding compile errors
		// and turning the expected interruption of a timeout into an error balloon
		CompletableFuture<Object> result = new CompletableFuture<>();
		Future<?> task = app.executeOnPooledThread(() -> {
			try {
				result.complete(readAction ? app.runReadAction((ThrowableComputable<Object, Exception>) evaluation::call) : evaluation.call());
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		});
		try {
			return result.get((long) (seconds * 1000), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			task.cancel(true);
			throw new IllegalStateException("No result after " + seconds + " s; cancellation requested, but the script may still run if it ignores interruption");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw new IllegalStateException(cause.getClass().getName() + ": " + cause.getMessage(), cause);
		} catch (InterruptedException e) {
			task.cancel(true);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted", e);
		}
	}

	/** {@code file} as an absolute path: a relative one against {@code project}'s base path. */
	private static Path path(String file, Project project) {
		Path path = Path.of(file);
		if (path.isAbsolute()) {
			return path.normalize();
		}
		if (project == null || project.getBasePath() == null) {
			throw new IllegalArgumentException("No project to resolve the relative file " + file + " against; give an absolute path");
		}
		return Path.of(project.getBasePath()).resolve(path).normalize();
	}

	/** The file name without its extension: the default scope of a script run from a file. */
	private static String baseName(Path path) {
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	/** The displayed source name and line of the first stack frame in this generated script, else null. */
	private static String scriptLine(Throwable e, String scriptName, String displayName) {
		String name = Path.of(scriptName).getFileName().toString();
		for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
			for (StackTraceElement frame : t.getStackTrace()) {
				if (frame.getLineNumber() > 0 && frame.getFileName() != null && Path.of(frame.getFileName()).getFileName().toString().equals(name)) {
					return displayName + ":" + frame.getLineNumber();
				}
			}
		}
		return null;
	}

	/** What a task on the debugger thread gets: the session, the Java DebugProcessImpl and the debuggee's JDI VirtualMachine. */
	public record Debuggee(XDebugSession session, Object debugProcess, VirtualMachine vm) {
	}

	/** A task to run on the debugger thread. */
	public interface DebuggerTask<T> {
		T run(Debuggee debuggee) throws Exception;
	}

	/**
	 * Runs {@code task} on the debugger manager thread of the Java debug session whose name contains {@code session}
	 * (case-insensitive; of several, the one not stopped), and returns its result. JDI calls must run on that thread.
	 * Reflection keeps the plugin free of a Java plugin dependency.
	 */
	public static <T> T onDebuggerThread(String session, double seconds, DebuggerTask<T> task) throws InterruptedException {
		XDebugSession debugSession = Edt.compute(() -> debugSession(session));
		CompletableFuture<T> result = new CompletableFuture<>();
		try {
			Object javaSession;
			try {
				javaSession = debugSession.getDebugProcess().getClass().getMethod("getDebuggerSession").invoke(debugSession.getDebugProcess());
			} catch (NoSuchMethodException e) {
				throw new IllegalArgumentException("Debug session \"" + debugSession.getSessionName() + "\" is not a Java debug session ("
						+ debugSession.getDebugProcess().getClass().getName() + ")", e);
			}
			Object debugProcess = javaSession.getClass().getMethod("getProcess").invoke(javaSession);
			Object managerThread = debugProcess.getClass().getMethod("getManagerThread").invoke(debugProcess);
			ClassLoader loader = managerThread.getClass().getClassLoader();
			Class<?> priorityClass = Class.forName("com.intellij.debugger.impl.PrioritizedTask$Priority", true, loader);
			@SuppressWarnings({"unchecked", "rawtypes"})
			Object normal = Enum.valueOf((Class<Enum>) priorityClass, "NORMAL");
			Runnable runnable = () -> {
				try {
					Object proxy = debugProcess.getClass().getMethod("getVirtualMachineProxy").invoke(debugProcess);
					VirtualMachine vm = (VirtualMachine) proxy.getClass().getMethod("getVirtualMachine").invoke(proxy);
					result.complete(task.run(new Debuggee(debugSession, debugProcess, vm)));
				} catch (Throwable e) {
					result.completeExceptionally(e instanceof InvocationTargetException ite ? ite.getCause() : e);
				}
			};
			managerThread.getClass().getMethod("schedule", priorityClass, Runnable.class).invoke(managerThread, normal, runnable);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("The Java debugger's API changed: " + e, e);
		}
		try {
			return result.get((long) (seconds * 1000), TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new IllegalStateException("No result after " + seconds + " s; the task may still run on the debugger thread, "
					+ "which stalls the debug session (stepping, breakpoints, variables) until it ends");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw new IllegalStateException(cause.getClass().getName() + ": " + cause.getMessage(), cause);
		}
	}

	/** Whether a debug session not stopped has a name containing {@code name}. Call on the EDT. */
	public static boolean debugging(String name) {
		for (Project project : ProjectManager.getInstance().getOpenProjects()) {
			for (XDebugSession s : XDebuggerManager.getInstance(project).getDebugSessions()) {
				if (!s.isStopped() && RunConsoles.named(s.getSessionName(), name)) {
					return true;
				}
			}
		}
		return false;
	}

	/** The debug session whose name contains {@code name}; of several, the one not stopped. Call on the EDT. */
	private static XDebugSession debugSession(String name) {
		List<XDebugSession> matching = new ArrayList<>();
		List<String> all = new ArrayList<>();
		for (Project project : ProjectManager.getInstance().getOpenProjects()) {
			for (XDebugSession s : XDebuggerManager.getInstance(project).getDebugSessions()) {
				all.add(s.getSessionName());
				if (RunConsoles.named(s.getSessionName(), name)) {
					matching.add(s);
				}
			}
		}
		List<XDebugSession> live = matching.stream().filter(s -> !s.isStopped()).toList();
		if (live.size() == 1) {
			return live.getFirst();
		}
		if (live.isEmpty()) {
			throw new IllegalArgumentException(matching.isEmpty()
					? "No debug session named like \"" + name + "\"; debug sessions: " + all
					: "The debug session named like \"" + name + "\" has stopped");
		}
		throw new IllegalArgumentException("Several debug sessions named like \"" + name + "\": "
				+ live.stream().map(XDebugSession::getSessionName).toList());
	}

	/** The project of {@code component}, else of the last focused frame, else the first open one. */
	static Project project(Component component) {
		Project project = component == null ? null : ProjectUtil.getProjectForComponent(component);
		if (project == null) {
			IdeFrame frame = IdeFocusManager.getGlobalInstance().getLastFocusedFrame();
			project = frame == null ? null : frame.getProject();
		}
		if (project != null && project.isDisposed()) {
			project = null; // the last focused frame may be that of a closed project
		}
		if (project != null) {
			return project;
		}
		return Arrays.stream(ProjectManager.getInstance().getOpenProjects()).findFirst().orElse(null);
	}

	/** Groovy truth for a script's last value: null, false, 0, and empty strings, collections and maps are false. */
	private static boolean truthy(Object value) {
		return switch (value) {
			case null -> false;
			case Boolean b -> b;
			case Number n -> n.doubleValue() != 0;
			case CharSequence s -> !s.isEmpty();
			case java.util.Collection<?> c -> !c.isEmpty();
			case Map<?, ?> m -> !m.isEmpty();
			default -> true;
		};
	}
}
