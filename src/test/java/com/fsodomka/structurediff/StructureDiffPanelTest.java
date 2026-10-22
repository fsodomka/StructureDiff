package com.fsodomka.structurediff;

import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.fsodomka.structurediff.review.ReviewSeverity;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.tree.DefaultMutableTreeNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class StructureDiffPanelTest extends BasePlatformTestCase {
	private static MemberDiff member(String key, FileStatus status, long changeId) {
		return new MemberDiff(key, key, key, null, status, 0, 0, 1, 1, true, changeId, List.of());
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

	private static DefaultMutableTreeNode add(DefaultMutableTreeNode parent, Object userObject) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(userObject);
		parent.add(node);
		return node;
	}

	private static Map<List<String>, DefaultMutableTreeNode> nodes(DefaultMutableTreeNode root) {
		Map<List<String>, DefaultMutableTreeNode> nodes = new HashMap<>();
		StructureDiffPanel.collectNodes(root, new ArrayList<>(), nodes);
		return nodes;
	}

	private static List<String> titles(List<MemberFinding> findings) {
		return findings.stream().map(MemberFinding::title).toList();
	}

	public void testFindingCountsBySeverityInShownFiles() {
		Map<String, List<MemberFinding>> findings = Map.of(
				"/p/a.xml", List.of(finding(ReviewSeverity.ERROR, "e", "root"), finding("w1", "root"), finding("w2")),
				"/p/b.xml", List.of(finding(ReviewSeverity.WEAK_WARNING, "weak")),
				"/p/notShown.xml", List.of(finding(ReviewSeverity.ERROR, "x"), finding("y")));

		int[] counts = StructureDiffPanel.findingCounts(List.of(file("/p/a.xml"), file("/p/b.xml"), file("/p/c.xml")), findings);

		assertEquals(1, counts[ReviewSeverity.ERROR.ordinal()]);
		assertEquals(2, counts[ReviewSeverity.WARNING.ordinal()]);
		assertEquals(1, counts[ReviewSeverity.WEAK_WARNING.ordinal()]);
		assertEquals(2, counts[counts.length - 1]);
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
				titles(StructureDiffPanel.findingsOf(fileNode, findings)));
		assertEquals(List.of("inHiddenC"), titles(StructureDiffPanel.findingsOf(rootNode, findings)));
		assertEquals(List.of("inHiddenChildOfA", "inA"), titles(StructureDiffPanel.findingsOf(aNode, findings)));
		assertEquals(List.of("inB"), titles(StructureDiffPanel.findingsOf(bNode, findings)));
	}

	public void testNoFindingsOutsideAFile() {
		DefaultMutableTreeNode repoNode = new DefaultMutableTreeNode("repo");
		DefaultMutableTreeNode orphan = add(repoNode, member("root"));
		Map<String, List<MemberFinding>> findings = Map.of("/p/a.xml", List.of(finding("f", "root")));

		assertEquals(List.of(), StructureDiffPanel.findingsOf(repoNode, findings));
		assertEquals(List.of(), StructureDiffPanel.findingsOf(orphan, findings));
		assertEquals(List.of(), StructureDiffPanel.findingsOf(new DefaultMutableTreeNode(file("/p/a.xml")), Map.of()));
	}

	public void testExportRoot() throws IOException {
		VirtualFile root1 = myFixture.getTempDirFixture().findOrCreateDir("r1");
		VirtualFile root2 = myFixture.getTempDirFixture().findOrCreateDir("r2");
		FileDiff a = file("/r1/a.xml");
		FileDiff b = file("/r2/b.xml");
		FileDiff unversioned = file("/elsewhere/c.xml");
		Map<String, VirtualFile> vcsRoots = Map.of(a.path(), root1, b.path(), root2);

		assertNull(StructureDiffPanel.exportRoot(List.of(), vcsRoots, null));
		assertNull(StructureDiffPanel.exportRoot(List.of(unversioned), vcsRoots, null));
		// a single root needs no selection
		assertEquals(root1, StructureDiffPanel.exportRoot(List.of(a, unversioned), vcsRoots, null));

		// several roots: the root of the selection
		List<FileDiff> both = List.of(a, b);
		assertNull(StructureDiffPanel.exportRoot(both, vcsRoots, null));
		assertEquals(root2, StructureDiffPanel.exportRoot(both, vcsRoots,
				new DefaultMutableTreeNode(new StructureDiffPanel.RepoNode(root2))));
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(a);
		DefaultMutableTreeNode memberNode = add(fileNode, member("root"));
		assertEquals(root1, StructureDiffPanel.exportRoot(both, vcsRoots, memberNode));
		assertNull(StructureDiffPanel.exportRoot(both, vcsRoots, new DefaultMutableTreeNode(unversioned)));
	}

	/** Keys of files and members, for flashes and the selection, stay the same when grouping by repository starts or stops. */
	public void testKeysIgnoreTheRepoNode() throws IOException {
		VirtualFile repo = myFixture.getTempDirFixture().findOrCreateDir("repo");
		DefaultMutableTreeNode flat = new DefaultMutableTreeNode();
		DefaultMutableTreeNode flatMember = add(add(flat, file("A.java")), member("m"));
		DefaultMutableTreeNode grouped = new DefaultMutableTreeNode();
		DefaultMutableTreeNode repoNode = add(grouped, new StructureDiffPanel.RepoNode(repo));
		DefaultMutableTreeNode groupedMember = add(add(repoNode, file("A.java")), member("m"));

		Map<List<String>, DefaultMutableTreeNode> flatNodes = new java.util.HashMap<>();
		StructureDiffPanel.collectNodes(flat, new java.util.ArrayList<>(), flatNodes);
		Map<List<String>, DefaultMutableTreeNode> groupedNodes = new java.util.HashMap<>();
		StructureDiffPanel.collectNodes(grouped, new java.util.ArrayList<>(), groupedNodes);
		List<String> key = StructureDiffPanel.keyOf(flatMember);
		assertEquals(key, StructureDiffPanel.keyOf(groupedMember));
		assertSame(flatMember, flatNodes.get(key));
		assertSame(groupedMember, groupedNodes.get(key));
		assertSame(groupedMember, StructureDiffPanel.find(grouped, key));
		assertSame(flatMember, StructureDiffPanel.find(flat, key));
		assertSame(repoNode, StructureDiffPanel.find(grouped, StructureDiffPanel.keyOf(repoNode)));
	}

	/** A member modified again keeps its status; its new change id still makes it flash. */
	public void testModifiedMemberFlashesAgainWhenItsContentChanged() {
		assertTrue(StructureDiffPanel.changedSince(member("m", 1), member("m", 2)));
		assertFalse(StructureDiffPanel.changedSince(member("m", 2), member("m", 2)));
	}

	public void testFlashesWhenTheStatusChanges() {
		MemberDiff added = member("m", FileStatus.ADDED, 1);

		assertTrue(StructureDiffPanel.changedSince(member("m", 1), added));
		assertTrue(StructureDiffPanel.changedSince(added, member("m", 1)));
	}

	/** A file that stays modified is not a change: every member edit would also flash the file. */
	public void testModifiedFileDoesNotFlashWhenOnlyItsContentChanged() {
		FileDiff before = file("/p/a.java", FileStatus.MODIFIED, "void a() {}", "void a() { int x = 1; }");
		FileDiff after = file("/p/a.java", FileStatus.MODIFIED, "void a() {}", "void a() { int x = 2; }");

		assertFalse(StructureDiffPanel.changedSince(before, after));
		assertTrue(StructureDiffPanel.changedSince(file("/p/a.java", FileStatus.ADDED), file("/p/a.java")));
	}

	/** Each node flashes on its own: a running flash suppresses its node only, other changed nodes still flash. */
	public void testRunningFlashSuppressesItsNodeOnly() {
		DefaultMutableTreeNode oldRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode oldFile = add(oldRoot, file("/p/a.java"));
		DefaultMutableTreeNode oldA = add(oldFile, member("a", 1));
		DefaultMutableTreeNode oldB = add(oldFile, member("b", 1));
		DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode newFile = add(newRoot, file("/p/a.java"));
		add(newFile, member("a", 2));
		add(newFile, member("b", 2));
		List<String> a = StructureDiffPanel.keyOf(oldA);
		List<String> b = StructureDiffPanel.keyOf(oldB);

		assertEquals(Set.of(b), StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0).keySet());
		assertEquals(Set.of(a, b), StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(), 0).keySet());
	}

	/** A status change updates a running flash instead of being skipped with the other content changes. */
	public void testStatusChangeFlashesWhileItsNodeIsRunning() {
		DefaultMutableTreeNode oldRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode oldA = add(add(oldRoot, file("/p/a.java")), member("a", FileStatus.ADDED, 1));
		DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode();
		add(add(newRoot, file("/p/a.java")), member("a", FileStatus.MODIFIED, 1));
		List<String> a = StructureDiffPanel.keyOf(oldA);

		Map<List<String>, StructureDiffPanel.Flash> flashes =
				StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0);

		assertEquals(Set.of(a), flashes.keySet());
		assertEquals(FileStatus.MODIFIED, flashes.get(a).status());
	}

	/** A removal flashes as a ghost even while the removed node's own flash runs. */
	public void testRemovalFlashesWhileItsNodeIsRunning() {
		DefaultMutableTreeNode oldRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode oldA = add(add(oldRoot, file("/p/a.java")), member("a", 1));
		DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode();
		add(newRoot, file("/p/a.java"));
		List<String> a = StructureDiffPanel.keyOf(oldA);

		Map<List<String>, StructureDiffPanel.Flash> flashes =
				StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0);

		assertEquals(Set.of(a), flashes.keySet());
		assertSame(oldA.getUserObject(), flashes.get(a).vanished());
	}
}
