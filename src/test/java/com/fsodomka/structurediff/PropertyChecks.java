package com.fsodomka.structurediff;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.PropertyChecker;
import org.jetbrains.jetCheck.PropertyFalsified;

import java.util.function.Consumer;

/** Runs jetCheck properties so that a failure reports its shrunk counterexample. */
public final class PropertyChecks {
	private PropertyChecks() {
	}

	/**
	 * Checks {@code check} on generated values; it fails by throwing, such as by a failed assertion, and returns to skip a value.
	 * The test report shows the cause of {@link PropertyFalsified} rather than its message, which holds the shrunk
	 * counterexample and how to replay it, so this rethrows the message alone (it includes the cause's trace).
	 */
	public static <T> void forAll(Generator<T> generator, Consumer<T> check) {
		try {
			PropertyChecker.forAll(generator, value -> {
				check.accept(value);
				return true;
			});
		} catch (PropertyFalsified e) {
			throw new AssertionError(e.getMessage());
		}
	}
}
