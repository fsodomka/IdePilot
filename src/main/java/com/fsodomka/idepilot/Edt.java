package com.fsodomka.idepilot;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Runs code on the EDT and waits for its result. */
final class Edt {
	private Edt() {
	}

	/**
	 * {@code task}'s result, computed on the EDT in any modality. A RuntimeException it throws is rethrown as is,
	 * not wrapped as invokeAndWait would.
	 */
	static <T> T compute(Supplier<T> task) {
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
}
