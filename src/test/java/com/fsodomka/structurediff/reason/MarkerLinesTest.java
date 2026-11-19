package com.fsodomka.structurediff.reason;

import junit.framework.TestCase;

import java.util.List;
import java.util.function.Function;

public class MarkerLinesTest extends TestCase {
	private static List<List<MarkerLines>> runs(MarkerLines... lines) {
		return MarkerLines.runs(List.of(lines), Function.identity());
	}

	/** The records of one reason in the session that prompted this: a block, the same block again, a line in it. */
	public void testOverlappingAndNestedRangesFormOneRun() {
		MarkerLines block = new MarkerLines(319, 348);
		MarkerLines part = new MarkerLines(319, 332);
		MarkerLines tail = new MarkerLines(341, 348);

		assertEquals(List.of(List.of(block, part, tail)), runs(block, part, tail));
	}

	public void testTouchingRangesMergeButAGapKeepsThemApart() {
		assertEquals(1, runs(new MarkerLines(1, 3), new MarkerLines(4, 6)).size());
		assertEquals(2, runs(new MarkerLines(1, 3), new MarkerLines(5, 6)).size());
	}

	public void testRunsAreByFirstLineAndEachRunStartsWithItsEarliestRange() {
		MarkerLines late = new MarkerLines(50, 60);
		MarkerLines early = new MarkerLines(2, 4);
		MarkerLines inside = new MarkerLines(52, 52);

		assertEquals(List.of(List.of(early), List.of(late, inside)), runs(late, early, inside));
	}

	/** A long range keeps later short ones in its run, though they start after a shorter one ended. */
	public void testTheRunEndsAtItsFurthestRange() {
		assertEquals(1, runs(new MarkerLines(1, 100), new MarkerLines(2, 3), new MarkerLines(50, 51)).size());
	}

	public void testNoRangesNoRuns() {
		assertTrue(runs().isEmpty());
	}
}
