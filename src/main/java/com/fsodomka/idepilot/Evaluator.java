package com.fsodomka.idepilot;

import com.intellij.ide.script.IdeScriptEngine;
import com.intellij.ide.script.IdeScriptEngineManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.ide.impl.ProjectUtil;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.IdeFrame;

import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;

import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
	static String eval(String script, String ref, String session, double seconds) {
		if (ref != null && session != null) {
			throw new IllegalArgumentException("Give ref or session, not both");
		}
		Component component = ref == null ? null : UiSnapshot.component(ref);
		IdeScriptEngine engine = IdeScriptEngineManager.getInstance().getEngineByFileExtension("groovy", null);
		if (engine == null) {
			throw new IllegalStateException("No Groovy script engine; available: "
					+ IdeScriptEngineManager.getInstance().getEngineInfos().stream().map(info -> info.languageName).toList());
		}
		if (session != null) {
			try {
				return inDebuggee(engine, script, session, seconds);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException("The Java debugger's API changed: " + e, e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted", e);
			}
		}
		return String.valueOf(Edt.await(() -> {
					engine.setBinding("project", project(component));
					engine.setBinding("component", component);
					return engine.eval(script);
				}, Edt.modality(component), (long) (seconds * 1000),
				"No result after " + seconds + " s; the script may still run, or a dialog blocks it",
				cause -> new IllegalStateException(cause.getClass().getName() + ": " + cause.getMessage(), cause)));
	}

	/**
	 * Evaluates {@code script} on the debugger manager thread of the Java debug session whose name contains
	 * {@code session} (case-insensitive; of several, the one not stopped), binding {@code project}, {@code session}
	 * (the XDebugSession), {@code debugProcess} (the Java DebugProcessImpl) and {@code vm} (the debuggee's JDI
	 * VirtualMachine). JDI calls must run on that thread. Reflection keeps the plugin free of a Java plugin dependency.
	 */
	private static String inDebuggee(IdeScriptEngine engine, String script, String session, double seconds)
			throws ReflectiveOperationException, InterruptedException {
		XDebugSession debugSession = Edt.compute(() -> debugSession(session));
		Object javaSession;
		try {
			javaSession = debugSession.getDebugProcess().getClass().getMethod("getDebuggerSession").invoke(debugSession.getDebugProcess());
		} catch (NoSuchMethodException e) {
			throw new IllegalArgumentException("Debug session \"" + debugSession.getSessionName() + "\" is not a Java debug session ("
					+ debugSession.getDebugProcess().getClass().getName() + ")", e);
		}
		// missing from here on: the debugger's API changed, reported by the caller
		Object debugProcess = javaSession.getClass().getMethod("getProcess").invoke(javaSession);
		Object managerThread = debugProcess.getClass().getMethod("getManagerThread").invoke(debugProcess);
		ClassLoader loader = managerThread.getClass().getClassLoader();
		Class<?> priorityClass = Class.forName("com.intellij.debugger.impl.PrioritizedTask$Priority", true, loader);
		@SuppressWarnings({"unchecked", "rawtypes"})
		Object normal = Enum.valueOf((Class<Enum>) priorityClass, "NORMAL");
		CompletableFuture<Object> result = new CompletableFuture<>();
		Runnable task = () -> {
			try {
				Object proxy = debugProcess.getClass().getMethod("getVirtualMachineProxy").invoke(debugProcess);
				engine.setBinding("project", debugSession.getProject());
				engine.setBinding("session", debugSession);
				engine.setBinding("debugProcess", debugProcess);
				engine.setBinding("vm", proxy.getClass().getMethod("getVirtualMachine").invoke(proxy));
				result.complete(engine.eval(script));
			} catch (Throwable e) {
				result.completeExceptionally(e instanceof InvocationTargetException ite ? ite.getCause() : e);
			}
		};
		managerThread.getClass().getMethod("schedule", priorityClass, Runnable.class).invoke(managerThread, normal, task);
		try {
			return String.valueOf(result.get((long) (seconds * 1000), TimeUnit.MILLISECONDS));
		} catch (TimeoutException e) {
			throw new IllegalStateException("No result after " + seconds + " s; the script may still run on the debugger thread, "
					+ "which stalls the debug session (stepping, breakpoints, variables) until it ends");
		} catch (ExecutionException e) {
			Throwable cause = e.getCause();
			throw new IllegalStateException(cause.getClass().getName() + ": " + cause.getMessage(), cause);
		}
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
}
