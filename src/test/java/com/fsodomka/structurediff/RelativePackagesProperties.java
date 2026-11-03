package com.fsodomka.structurediff;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.fsodomka.structurediff.PropertyChecks.forAll;
import static org.junit.Assert.assertEquals;

/**
 * Properties of {@link PackageNames#relativePackages(java.util.Collection, Map, char)}, checked with jetCheck against a
 * model working on whole segments. Names come from a few segments, so they often share a prefix of several segments, and
 * one name may be a prefix of another or the shared package itself.
 */
public class RelativePackagesProperties {
	private static final List<String> SEGMENTS = List.of("com", "acme", "app", "ui");

	private record Input(char separator, List<String> names) {
	}

	private static final Generator<Input> INPUTS = Generator.from(env -> {
		char separator = env.generate(Generator.sampledFrom('.', '/'));
		List<String> names = env.generate(Generator.listsOf(IntDistribution.uniform(0, 6),
				Generator.listsOf(IntDistribution.uniform(0, 4), Generator.sampledFrom(SEGMENTS))
						.map(segments -> String.join(String.valueOf(separator), segments))));
		return new Input(separator, names);
	});

	@Test
	public void namesLoseTheLongestPackageAllNamesWithASeparatorShare() {
		forAll(INPUTS, input -> {
			Map<String, String> names = new HashMap<>();
			List<String> paths = new ArrayList<>();
			for (int i = 0; i < input.names.size(); i++) {
				paths.add("p" + i);
				names.put("p" + i, input.names.get(i));
			}
			PackageNames.relativePackages(paths, names, input.separator);
			List<String> actual = paths.stream().map(names::get).toList();
			assertEquals(input.toString(), expected(input.names, input.separator), actual);
		});
	}

	/**
	 * The model: the longest run of leading segments shared by every name with a separator, removed with its separator from
	 * every name that is it or starts with it; names without a separator do not decide it.
	 */
	private static List<String> expected(List<String> names, char separator) {
		String sep = String.valueOf(separator);
		List<String[]> decisive = names.stream().filter(n -> n.contains(sep)).map(n -> n.split(java.util.regex.Pattern.quote(sep), -1)).toList();
		if (decisive.isEmpty()) {
			return names;
		}
		int shared = decisive.getFirst().length;
		for (String[] segments : decisive) {
			int i = 0;
			while (i < Math.min(shared, segments.length) && segments[i].equals(decisive.getFirst()[i])) {
				i++;
			}
			shared = i;
		}
		if (shared == 0) {
			return names;
		}
		String common = String.join(sep, List.of(decisive.getFirst()).subList(0, shared));
		return names.stream().map(n -> n.equals(common) ? "" : n.startsWith(common + sep) ? n.substring(common.length() + 1) : n).toList();
	}
}
