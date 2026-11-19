package com.fsodomka.structurediff;

import com.intellij.openapi.vcs.FileStatus;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/** The colors the tree, the treemap and the editor gutter share. */
public class StatusColorsTest extends BasePlatformTestCase {
	public void testDeletedIsRedNotThePlatformsGray() {
		assertSame(StatusColors.REMOVED, StatusColors.of(FileStatus.DELETED));
		assertEquals(FileStatus.ADDED.getColor(), StatusColors.of(FileStatus.ADDED));
		assertEquals(FileStatus.MODIFIED.getColor(), StatusColors.of(FileStatus.MODIFIED));
		assertNull(StatusColors.of(null));
	}
}
