package com.fsodomka.structurediff.review;

import junit.framework.TestCase;

public class ReviewTextTest extends TestCase {
	public void testLeadStopsAtTheFirstSentence() {
		assertEquals("Reads the record", ReviewText.lead("Reads the record. Then it moves the marker."));
	}

	public void testLeadStopsAtALongClauseBeforeAColon() {
		assertEquals("A live record's lines may have drifted before it arrived (parallel edits, imports added above)",
				ReviewText.lead("A live record's lines may have drifted before it arrived (parallel edits, imports added above): verify the lines."));
	}

	public void testLeadStopsAtASemicolon() {
		assertEquals("openFileEditor was also deprecated on 263",
				ReviewText.lead("openFileEditor was also deprecated on 263; navigate the descriptor instead."));
	}

	public void testLeadKeepsAShortLabelBeforeAColon() {
		assertEquals("Fix: use the cached service", ReviewText.lead("Fix: use the cached service."));
	}

	public void testLeadKeepsTheFindingPrefixAndDotsInsideNames() {
		assertEquals("#4 [PLAUSIBLE] getInstance() in a Foo.Bar may run off the EDT",
				ReviewText.lead("#4 [PLAUSIBLE] getInstance() in a Foo.Bar may run off the EDT. More text."));
	}

	public void testLeadStopsAtAConjunctionClauseOfALongSentence() {
		assertEquals("#4 [PLAUSIBLE] getInstance() in a BulkFileListener may construct the service off the EDT",
				ReviewText.lead("#4 [PLAUSIBLE] getInstance() in a BulkFileListener may construct the service off the EDT, and the constructor attaches Swing listeners."));
	}

	public void testLeadKeepsAShortSentenceWithAClause() {
		assertEquals("It reads the record, and moves the marker", ReviewText.lead("It reads the record, and moves the marker."));
	}

	public void testLeadUsesTheFirstLineOnly() {
		assertEquals("First line", ReviewText.lead("First line\nSecond line. More"));
	}

	public void testLeadShortensAnEndlessSentence() {
		String lead = ReviewText.lead("word ".repeat(100));
		assertTrue(lead, lead.endsWith("...") && lead.length() <= 160);
	}

	public void testAnAbbreviationDoesNotEndTheLead() {
		assertEquals("Use e.g. a cache", ReviewText.lead("Use e.g. a cache. More text."));
	}
}
