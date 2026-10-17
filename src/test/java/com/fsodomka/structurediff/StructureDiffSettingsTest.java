package com.fsodomka.structurediff;

import junit.framework.TestCase;

public class StructureDiffSettingsTest extends TestCase {
	public void testNormalizeReviewsDir() {
		assertEquals("reviews", StructureDiffSettings.normalizeReviewsDir(" reviews/ "));
		assertEquals("build/reviews", StructureDiffSettings.normalizeReviewsDir("build\\reviews"));
		assertEquals("build/reviews", StructureDiffSettings.normalizeReviewsDir("./build//reviews"));
		assertNull(StructureDiffSettings.normalizeReviewsDir(""));
		assertNull(StructureDiffSettings.normalizeReviewsDir("/abs"));
		assertNull(StructureDiffSettings.normalizeReviewsDir("C:/abs"));
		assertNull(StructureDiffSettings.normalizeReviewsDir("../up"));
		assertNull(StructureDiffSettings.normalizeReviewsDir("a/../b"));
		assertEquals("a/b", StructureDiffSettings.normalizeReviewsDir("a/./b"));
		assertNull(StructureDiffSettings.normalizeReviewsDir(".git"));
		assertNull(StructureDiffSettings.normalizeReviewsDir(".GIT/reviews"));
		assertEquals(".github/reviews", StructureDiffSettings.normalizeReviewsDir(".github/reviews"));
	}
}
