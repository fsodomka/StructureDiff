package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.fsodomka.structurediff.reason.ChangeKind;
import com.fsodomka.structurediff.reason.EditReason;
import com.fsodomka.structurediff.reason.EditReasonMembers.MemberReason;
import com.fsodomka.structurediff.reason.EditReasonsService;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.fsodomka.structurediff.review.ReviewSeverity;
import com.intellij.icons.AllIcons;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.ui.RowIcon;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class MemberSignalsTest extends BasePlatformTestCase {
	private static MemberDiff member(String key, FileStatus status, long changeId) {
		return new MemberDiff(key, key, key, key, null, status, 0, 0, 1, 1, true, changeId, List.of());
	}

	private static MemberDiff member(String key, long changeId) {
		return member(key, FileStatus.MODIFIED, changeId);
	}

	private static MemberDiff member(String key) {
		return member(key, 0);
	}

	private static FileDiff file(String path) {
		return file(path, FileStatus.MODIFIED);
	}

	private static FileDiff file(String path, FileStatus status) {
		return file(path, status, "", "");
	}

	private static FileDiff file(String path, FileStatus status, String beforeText, String afterText) {
		return new FileDiff(null, path, null, status, beforeText, afterText, 1, 1, List.of());
	}

	private static MemberFinding finding(String title, String... keyPath) {
		return finding(ReviewSeverity.WARNING, title, keyPath);
	}

	private static MemberFinding finding(ReviewSeverity severity, String title, String... keyPath) {
		return new MemberFinding(List.of(keyPath), title, severity, 0, null);
	}

	private static MemberDiagnostic diagnostic(HighlightSeverity severity, String message, String... keyPath) {
		return new MemberDiagnostic(List.of(keyPath), message, severity, 0);
	}

	private static DefaultMutableTreeNode add(DefaultMutableTreeNode parent, Object userObject) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(userObject);
		parent.add(node);
		return node;
	}

	private static List<String> titles(List<MemberFinding> findings) {
		return findings.stream().map(MemberFinding::title).toList();
	}

	public void testFindingCountsBySeverityInShownFiles() {
		Map<String, List<MemberFinding>> findings = Map.of(
				"/p/a.xml", List.of(finding(ReviewSeverity.ERROR, "e", "root"), finding("w1", "root"), finding("w2")),
				"/p/b.xml", List.of(finding(ReviewSeverity.WEAK_WARNING, "weak")),
				"/p/notShown.xml", List.of(finding(ReviewSeverity.ERROR, "x"), finding("y")));

		int[] counts = MemberSignals.findingCounts(List.of(file("/p/a.xml"), file("/p/b.xml"), file("/p/c.xml")), findings);

		assertEquals(1, counts[ReviewSeverity.ERROR.ordinal()]);
		assertEquals(2, counts[ReviewSeverity.WARNING.ordinal()]);
		assertEquals(1, counts[ReviewSeverity.WEAK_WARNING.ordinal()]);
		// notShown.xml's findings are not counted
		assertEquals(ReviewSeverity.values().length, counts.length);
	}

	public void testFindingsGoToTheInnermostShownMember() {
		// a.xml > root > a > b; c is not shown
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(file("/p/a.xml"));
		DefaultMutableTreeNode rootNode = add(fileNode, member("root"));
		DefaultMutableTreeNode aNode = add(rootNode, member("a"));
		DefaultMutableTreeNode bNode = add(aNode, member("b"));
		Map<String, List<MemberFinding>> findings = Map.of(
				"/p/a.xml", List.of(
						finding("inB", "root", "a", "b"),
						finding("inHiddenChildOfA", "root", "a", "hidden"),
						finding("inA", "root", "a"),
						finding("inHiddenC", "root", "c"),
						finding("outsideMembers")),
				"/p/other.xml", List.of(finding("otherFile", "root", "a")));

		assertEquals(List.of("inB", "inHiddenChildOfA", "inA", "inHiddenC", "outsideMembers"),
				titles(MemberSignals.findingsOf(fileNode, findings)));
		assertEquals(List.of("inHiddenC"), titles(MemberSignals.findingsOf(rootNode, findings)));
		assertEquals(List.of("inHiddenChildOfA", "inA"), titles(MemberSignals.findingsOf(aNode, findings)));
		assertEquals(List.of("inB"), titles(MemberSignals.findingsOf(bNode, findings)));
	}

	public void testDiagnosticsArePlacedAsFindings() {
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(file("/p/a.xml"));
		DefaultMutableTreeNode rootNode = add(fileNode, member("root"));
		DefaultMutableTreeNode aNode = add(rootNode, member("a"));
		Map<String, List<MemberDiagnostic>> diags = Map.of("/p/a.xml", List.of(
				diagnostic(HighlightSeverity.ERROR, "inA", "root", "a"),
				diagnostic(HighlightSeverity.WARNING, "inHidden", "root", "hidden"),
				diagnostic(HighlightSeverity.WARNING, "outside")));

		assertEquals(List.of("inA", "inHidden", "outside"),
				MemberSignals.placedOn(fileNode, diags, MemberDiagnostic::keyPath).stream().map(MemberDiagnostic::message).toList());
		assertEquals(List.of("inHidden"),
				MemberSignals.placedOn(rootNode, diags, MemberDiagnostic::keyPath).stream().map(MemberDiagnostic::message).toList());
		assertEquals(List.of("inA"),
				MemberSignals.placedOn(aNode, diags, MemberDiagnostic::keyPath).stream().map(MemberDiagnostic::message).toList());
	}

	public void testTooltipHasASectionPerSource() {
		List<MemberDiagnostic> diags = new ArrayList<>();
		for (int i = 0; i < MemberSignals.MAX_TOOLTIP_DIAGNOSTICS + 2; i++) {
			diags.add(diagnostic(HighlightSeverity.ERROR, "e" + i + " <x>"));
		}

		String html = MemberSignals.withSignals("lines", List.of(finding("CRITICAL · f")), diags);

		assertTrue(html, html.startsWith("<html>lines<br><br><b>Review</b><br>CRITICAL · f<br><br><b>IDE</b><br>Error · e0 &lt;x&gt;"));
		assertTrue(html, html.contains("e4"));
		assertFalse(html, html.contains("e5"));
		assertTrue(html, html.endsWith("<br>and 2 more</html>"));
		assertEquals("plain", MemberSignals.withSignals("plain", List.of(), List.of()));
		assertFalse(MemberSignals.withSignals("t", List.of(), diags).contains("Review"));
	}

	public void testTooltipListsEditReasonsFirst() {
		Instant time = Instant.parse("2026-11-15T12:00:00Z");
		// as the gutter hover names it: the day too, as the records span a week
		String hhmm = EditReason.when(time);
		List<MemberReason> reasons = List.of(
				new MemberReason(List.of(), "Keep <b> escaped.", false, false, time, null),
				new MemberReason(List.of(), null, true, false, time, null),
				new MemberReason(List.of(), "Loaded later.", false, true, time, null));

		String html = MemberSignals.withSignals("lines", List.of(finding("CRITICAL · f")), List.of(), reasons);

		assertTrue(html, html.startsWith("<html>lines<br><br><b>Why</b><br>" + hhmm + " · Keep &lt;b&gt; escaped.<br>"
				+ hhmm + " · <i>reason pending</i><br>" + hhmm + " · Loaded later. <i>(approximate)</i><br><br><b>Review</b>"));
	}

	public void testTooltipShortensLongReason() {
		String reason = "x".repeat(MemberSignals.MAX_TOOLTIP_REASON_LENGTH + 50);

		String html = MemberSignals.withSignals("lines", List.of(), List.of(),
				List.of(new MemberReason(List.of(), reason, false, false, Instant.now(), null)));

		assertTrue(html, html.contains(" · " + "x".repeat(MemberSignals.MAX_TOOLTIP_REASON_LENGTH - 1) + "…</html>"));
	}

	public void testWrappedBreaksBetweenWordsAndEscapes() {
		String word = "a".repeat(MemberSignals.TOOLTIP_REASON_LINE - 5);

		// the line is counted in characters of the text, not of its escaped form
		assertEquals(word + " &lt;b&gt;<br>&lt;i&gt;", MemberSignals.wrapped(word + " <b> <i>"));
		assertEquals("short words stay on one line", MemberSignals.wrapped("short words  stay on one line"));
	}

	public void testDistinctKeepsEachReasonOnceAndOnePending() {
		Instant older = Instant.parse("2026-11-15T12:00:00Z");
		Instant newer = older.plusSeconds(60);
		MemberReason a = new MemberReason(List.of(), "Same.", false, false, newer, null);
		MemberReason b = new MemberReason(List.of(), "Other.", false, false, newer, null);
		MemberReason pending = new MemberReason(List.of(), null, true, false, newer, null);

		List<MemberReason> shown = MemberSignals.distinct(List.of(a, b, new MemberReason(List.of(), "Same.", false, false, older, null),
				pending, new MemberReason(List.of(), null, true, false, older, null)));

		assertEquals(List.of(a, b, pending), shown);
		List<MemberReason> unique = List.of(a, b);
		assertSame(unique, MemberSignals.distinct(unique));
	}

	public void testSignalIconsFollowTheNodeIconReviewFirst() {
		Icon review = finding(ReviewSeverity.ERROR, "f").icon();
		Icon ide = diagnostic(HighlightSeverity.WARNING, "w").icon();
		assertEquals(AllIcons.Nodes.WarningIntroduction, ide);

		Icon both = MemberSignals.withSignalIcons(AllIcons.Nodes.Class,
				List.of(finding(ReviewSeverity.ERROR, "f")), List.of(diagnostic(HighlightSeverity.WARNING, "w")));
		assertEquals(List.of(AllIcons.Nodes.Class, review, ide), both instanceof RowIcon row ? row.getAllIcons() : both);
		assertSame(AllIcons.Nodes.Class, MemberSignals.withSignalIcons(AllIcons.Nodes.Class, List.of(), List.of()));
	}

	public void testNoFindingsOutsideAFile() {
		DefaultMutableTreeNode repoNode = new DefaultMutableTreeNode("repo");
		DefaultMutableTreeNode orphan = add(repoNode, member("root"));
		Map<String, List<MemberFinding>> findings = Map.of("/p/a.xml", List.of(finding("f", "root")));

		assertEquals(List.of(), MemberSignals.findingsOf(repoNode, findings));
		assertEquals(List.of(), MemberSignals.findingsOf(orphan, findings));
		assertEquals(List.of(), MemberSignals.findingsOf(new DefaultMutableTreeNode(file("/p/a.xml")), Map.of()));
	}

	/** A click on a node's mark shows its earliest reason, which its newest-first list holds last; unplaced ones don't count. */
	public void testFirstReasonIsTheEarliestPlacedOne() {
		Instant older = Instant.parse("2026-11-01T10:00:00Z");
		Instant newer = older.plusSeconds(60);
		EditReasonsService.Anchored placed = new EditReasonsService.Anchored(null, null, null, null, false);
		MemberReason unplaced = new MemberReason(List.of(), "Oldest, unplaced.", false, false, older.minusSeconds(60), null);
		MemberReason early = new MemberReason(List.of(), "Early.", false, false, older, null, false, placed);
		MemberReason late = new MemberReason(List.of(), "Late.", false, false, newer, null, false, placed);

		assertEquals(early, MemberReason.first(List.of(late, early, unplaced)));
		// one on changed code wins over an earlier one the snapshot doesn't show changed: only it can be tinted
		MemberReason changed = new MemberReason(List.of(), "Changed.", false, false, newer, ChangeKind.MODIFIED, false, placed);
		assertEquals(changed, MemberReason.first(List.of(changed, early)));
		assertNull(MemberReason.first(List.of(unplaced)));
	}
}
