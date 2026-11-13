package com.fsodomka.idepilot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/** Runs code on the EDT and waits for its result. */
public final class Edt {
	private Edt() {
	}

	/**
	 * {@code task}'s result, computed on the EDT in any modality. A RuntimeException it throws is rethrown as is,
	 * not wrapped as invokeAndWait would.
	 */
	public static <T> T compute(Supplier<T> task) {
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<RuntimeException> failure = new AtomicReference<>();
		ApplicationManager.getApplication().invokeAndWait(() -> {
			try {
				result.set(task.get());
			} catch (RuntimeException e) {
				failure.set(e);
			}
		}, ModalityState.any());
		if (failure.get() != null) {
			throw failure.get();
		}
		return result.get();
	}

	/**
	 * {@code task}'s result, run later on the EDT in {@code modality}, waiting at most {@code millis}. Not invokeAndWait,
	 * so it can run in a dialog's modality; a timeout throws {@code timeoutMessage}, and a failure what
	 * {@code onFailure} makes of it.
	 */
	static <T> T await(Callable<T> task, ModalityState modality, long millis, String timeoutMessage,
			Function<Throwable, RuntimeException> onFailure) {
		CompletableFuture<T> result = new CompletableFuture<>();
		ApplicationManager.getApplication().invokeLater(() -> {
			try {
				result.complete(task.call());
			} catch (Throwable e) {
				result.completeExceptionally(e);
			}
		}, modality);
		try {
			return result.get(millis, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new IllegalStateException(timeoutMessage);
		} catch (ExecutionException e) {
			throw onFailure.apply(e.getCause());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted");
		}
	}

	/**
	 * The modality to run a ref's work in: that of {@code component}'s dialog; without one, the focused component's,
	 * else the innermost open modal dialog's (the IDE may be inactive, with no focus owner), else non-modal. Code
	 * queued in a modality below an open modal dialog's would wait until the dialog closes.
	 */
	static ModalityState modality(Component component) {
		Component context = component != null ? component : KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
		// on the EDT, current() is the innermost open modal entity's state, whatever order its windows were created in
		return context == null ? compute(ModalityState::current) : ModalityState.stateForComponent(context);
	}
}
