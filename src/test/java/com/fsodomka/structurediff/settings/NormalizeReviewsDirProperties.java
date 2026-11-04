package com.fsodomka.structurediff.settings;

import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.junit.Test;

import java.util.List;

import static com.fsodomka.structurediff.PropertyChecks.forAll;
import static com.fsodomka.structurediff.settings.StructureDiffSettings.normalizeReviewsDir;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Properties of {@link StructureDiffSettings#normalizeReviewsDir}, checked with jetCheck. Inputs are glued from tokens that
 * matter to it: separators, {@code .}, {@code ..}, {@code .git} in any case, drive colons and blanks.
 */
public class NormalizeReviewsDirProperties {
	private static final Generator<String> INPUTS = Generator.listsOf(IntDistribution.uniform(0, 8),
					Generator.sampledFrom("reviews", "a", "b c", ".github", ".git", ".GiT", ".", "..", "...", "/", "//", "\\", ":", "C:", " ", "\t"))
			.map(tokens -> String.join("", tokens));

	private record Clean(List<String> segments, String dir) {
	}

	/** Plain segments joined by any separator, with the decorations normalizing strips. */
	private static final Generator<Clean> CLEAN = Generator.from(env -> {
		List<String> segments = env.generate(Generator.listsOf(IntDistribution.uniform(1, 4),
				Generator.sampledFrom("reviews", "build", "a", "b c", ".github", "git", ".gitx")));
		StringBuilder dir = new StringBuilder(env.generate(Generator.sampledFrom("", " ", "./", ".\\")));
		for (int i = 0; i < segments.size(); i++) {
			if (i > 0) {
				dir.append(env.generate(Generator.sampledFrom("/", "\\", "//", "/./", "\\\\")));
			}
			dir.append(segments.get(i));
		}
		dir.append(env.generate(Generator.sampledFrom("", "/", "\\", " ", "/ ")));
		return new Clean(segments, dir.toString());
	});

	@Test
	public void resultIsANonEmptyRelativePathOfPlainSegments() {
		forAll(INPUTS, dir -> {
			String result = normalizeReviewsDir(dir);
			if (result == null) {
				return;
			}
			String message = "'" + dir + "' -> '" + result + "'";
			assertFalse(message, result.isEmpty() || result.contains("\\") || result.contains(":"));
			for (String segment : result.split("/", -1)) {
				assertFalse(message, segment.isEmpty() || segment.equals(".") || segment.equals("..") || segment.equalsIgnoreCase(".git"));
			}
		});
	}

	@Test
	public void normalizingIsIdempotent() {
		forAll(INPUTS, dir -> {
			String result = normalizeReviewsDir(dir);
			if (result != null) {
				assertEquals("'" + dir + "'", result, normalizeReviewsDir(result));
			}
		});
	}

	@Test
	public void backslashesAreSlashes() {
		forAll(INPUTS, dir -> assertEquals("'" + dir + "'", normalizeReviewsDir(dir), normalizeReviewsDir(dir.replace('/', '\\'))));
	}

	@Test
	public void cleanPathsAreKeptWhateverTheirSeparators() {
		forAll(CLEAN, clean -> assertEquals("'" + clean.dir + "'", String.join("/", clean.segments), normalizeReviewsDir(clean.dir)));
	}
}
