package com.fsodomka.structurediff;

import com.intellij.openapi.vcs.FileStatus;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

/** {@link ChangeFlashes#newFlashes} is checked by {@link ChangeFlashesProperties}. */
public class ChangeFlashesTest extends BasePlatformTestCase {
	/** A node that appeared grows in the treemap; one that only changed is shown at full size from the start. */
	public void testAppearedNodeGrowsChangedNodeDoesNot() {
		ChangeFlashes.Flash changed = new ChangeFlashes.Flash(FileStatus.MODIFIED, false, null, -1, 100);
		ChangeFlashes.Flash appeared = new ChangeFlashes.Flash(FileStatus.ADDED, true, null, -1, 100);

		assertEquals(1.0, changed.grown(100));
		assertEquals(0.0, appeared.grown(100));
		double halfway = appeared.grown(100 + ChangeFlashes.GROW_MILLIS / 2);
		assertTrue("eases out: past half at half time, " + halfway, halfway > 0.5 && halfway < 1);
		assertEquals(1.0, appeared.grown(100 + ChangeFlashes.GROW_MILLIS));
	}
}
