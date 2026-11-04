package com.fsodomka.structurediff;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.Test;

import java.util.List;

import static com.fsodomka.structurediff.PropertyChecks.forAll;
import static org.junit.Assert.assertEquals;

/**
 * Properties of {@link DiffLabels#shorten}, checked with jetCheck against labels built together with their expected short
 * form: methods, fields and classes whose types nest generics, with parameter lists both short and over the cut length.
 */
public class DiffLabelsProperties {
	/** A type as a label shows it, and the same type without its type arguments. */
	private record Type(String full, String erased) {
	}

	/** A label and what {@link DiffLabels#shorten} should make of it. */
	private record Label(String label, String expected) {
	}

	private static final Generator<String> NAME = Generator.sampledFrom("T", "int", "String[]", "List", "Map", "Object...",
			"ProcessingEnvironmentConfiguration", "AbstractVeryLongTypeNameForTheCut");

	private static final Generator<Type> TYPE = types(3);

	/** Types with type arguments nested up to {@code depth} deep. */
	private static Generator<Type> types(int depth) {
		Generator<Type> plain = NAME.map(name -> new Type(name, name));
		if (depth == 0) {
			return plain;
		}
		Generator<Type> arguments = types(depth - 1);
		return Generator.frequency(3, plain, 1, Generator.from(env -> {
			String name = env.generate(NAME);
			List<Type> nested = env.generate(Generator.listsOf(IntDistribution.uniform(1, 3), arguments));
			return new Type(name + "<" + String.join(", ", nested.stream().map(Type::full).toList()) + ">", name);
		}));
	}

	private static final Generator<Label> LABELS = Generator.anyOf(
			// a method, with or without a return type
			Generator.from(env -> {
				String name = env.generate(Generator.sampledFrom("run", "process"));
				List<Type> parameters = env.generate(Generator.listsOf(IntDistribution.uniform(0, 5), TYPE));
				Type returned = env.generate(Generator.anyOf(Generator.constant(new Type("", "")), TYPE.map(t -> new Type(": " + t.full, ": " + t.erased))));
				String full = String.join(", ", parameters.stream().map(Type::full).toList());
				String erased = String.join(", ", parameters.stream().map(Type::erased).toList());
				// the cut keeps the first parameter when the erased list is over 59 characters
				String shortened = erased.length() > 59 && parameters.size() > 1 ? parameters.getFirst().erased + ", …" : erased;
				return new Label(name + "(" + full + ")" + returned.full, name + "(" + shortened + ")" + returned.erased);
			}),
			// a field
			Generator.from(env -> {
				Type type = env.generate(TYPE);
				return new Label("count: " + type.full, "count: " + type.erased);
			}),
			// a class
			TYPE.map(type -> new Label(type.full, type.erased)));

	@Test
	public void shortenErasesTypeArgumentsAndCutsLongParameterLists() {
		forAll(LABELS, l -> assertEquals(l.label, l.expected, DiffLabels.shorten(l.label)));
	}

	@Test
	public void shortenIsIdempotent() {
		forAll(LABELS, l -> {
			String once = DiffLabels.shorten(l.label);
			assertEquals(l.label, once, DiffLabels.shorten(once));
		});
	}
}
