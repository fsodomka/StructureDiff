package com.fsodomka.structurediff.settings;

import junit.framework.TestCase;

public class StructureDiffSettingsTest extends TestCase {
	public void testNormalizeReviewsDir() {
		// kept paths are checked by NormalizeReviewsDirProperties; these are the rejections and the cases it once failed on
		assertNull(StructureDiffSettings.normalizeReviewsDir(""));
		assertNull(StructureDiffSettings.normalizeReviewsDir("/abs"));
		assertNull(StructureDiffSettings.normalizeReviewsDir("C:/abs"));
		assertNull(StructureDiffSettings.normalizeReviewsDir("../up"));
		assertNull(StructureDiffSettings.normalizeReviewsDir("a/../b"));
		assertNull(StructureDiffSettings.normalizeReviewsDir(".git"));
		assertNull(StructureDiffSettings.normalizeReviewsDir(".GIT/reviews"));
		assertNull(StructureDiffSettings.normalizeReviewsDir("."));
		assertNull(StructureDiffSettings.normalizeReviewsDir("./."));
		assertEquals("reviews", StructureDiffSettings.normalizeReviewsDir("reviews /"));
		assertEquals("a/b", StructureDiffSettings.normalizeReviewsDir("a / b"));
		assertEquals("...", StructureDiffSettings.normalizeReviewsDir("... //."));
	}
}
