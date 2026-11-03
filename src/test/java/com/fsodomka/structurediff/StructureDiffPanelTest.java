package com.fsodomka.structurediff;

import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.tree.DefaultMutableTreeNode;
import java.io.IOException;
import java.util.*;

public class StructureDiffPanelTest extends BasePlatformTestCase {
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

	private static DefaultMutableTreeNode add(DefaultMutableTreeNode parent, Object userObject) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(userObject);
		parent.add(node);
		return node;
	}

	private static Map<List<String>, DefaultMutableTreeNode> nodes(DefaultMutableTreeNode root) {
		Map<List<String>, DefaultMutableTreeNode> nodes = new HashMap<>();
		DiffTreeNodes.collectNodes(root, new ArrayList<>(), nodes);
		return nodes;
	}

	public void testContentStampChangesWithTheAfterTexts() {
		int stamp = StructureDiffPanel.contentStamp(List.of(file("/p/A.java", FileStatus.MODIFIED, "a", "b")));
		// the before text and the status do not change what the working copy uses
		assertEquals(stamp, StructureDiffPanel.contentStamp(List.of(file("/p/A.java", FileStatus.ADDED, "", "b"))));
		assertFalse(stamp == StructureDiffPanel.contentStamp(List.of(file("/p/A.java", FileStatus.MODIFIED, "a", "b2"))));
		assertFalse(stamp == StructureDiffPanel.contentStamp(List.of(file("/p/B.java", FileStatus.MODIFIED, "a", "b"))));
		assertFalse(stamp == StructureDiffPanel.contentStamp(List.of(file("/p/A.java", FileStatus.MODIFIED, "a", "b"), file("/p/B.java"))));
		assertFalse(stamp == StructureDiffPanel.contentStamp(List.of(file("/p/A.java", FileStatus.DELETED, "a", null))));
	}

	public void testExportRoots() throws IOException {
		VirtualFile root1 = myFixture.getTempDirFixture().findOrCreateDir("r1");
		VirtualFile root2 = myFixture.getTempDirFixture().findOrCreateDir("r2");
		VirtualFile root3 = myFixture.getTempDirFixture().findOrCreateDir("r3");
		FileDiff a = file("/r1/a.xml");
		FileDiff b = file("/r2/b.xml");
		FileDiff c = file("/r3/c.xml");
		FileDiff unversioned = file("/elsewhere/d.xml");
		Map<String, VirtualFile> vcsRoots = Map.of(a.path(), root1, b.path(), root2, c.path(), root3);

		assertEquals(List.of(), StructureDiffPanel.exportRoots(List.of(), vcsRoots, List.of()));
		assertEquals(List.of(), StructureDiffPanel.exportRoots(List.of(unversioned), vcsRoots, List.of()));
		// a single root needs no selection
		assertEquals(List.of(root1), StructureDiffPanel.exportRoots(List.of(a, unversioned), vcsRoots, List.of()));

		// several roots: all of them when nothing with a root is selected
		List<FileDiff> all = List.of(a, b, c);
		assertEquals(List.of(root1, root2, root3), StructureDiffPanel.exportRoots(all, vcsRoots, List.of()));
		assertEquals(List.of(root1, root2, root3),
				StructureDiffPanel.exportRoots(all, vcsRoots, List.of(new DefaultMutableTreeNode(unversioned))));
		// the selected repositories, in tree order, and only those
		assertEquals(List.of(root2, root3), StructureDiffPanel.exportRoots(all, vcsRoots, List.of(
				new DefaultMutableTreeNode(new DiffTreeNodes.RepoNode(root2)),
				new DefaultMutableTreeNode(new DiffTreeNodes.RepoNode(root3)))));
		// a file or member selects its own repository
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(b);
		DefaultMutableTreeNode memberNode = add(fileNode, member("root"));
		assertEquals(List.of(root2), StructureDiffPanel.exportRoots(all, vcsRoots, List.of(memberNode)));
		// several files of one repository are still one diff
		assertEquals(List.of(root2), StructureDiffPanel.exportRoots(all, vcsRoots, List.of(
				fileNode, new DefaultMutableTreeNode(b))));
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
		List<String> a = DiffTreeNodes.keyOf(oldA);
		List<String> b = DiffTreeNodes.keyOf(oldB);

		assertEquals(Set.of(b), StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0).keySet());
		assertEquals(Set.of(a, b), StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(), 0).keySet());
	}

	/** A node that appeared grows in the treemap; one that only changed is shown at full size from the start. */
	public void testAppearedNodeGrowsChangedNodeDoesNot() {
		DefaultMutableTreeNode oldRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode oldA = add(add(oldRoot, file("/p/a.java")), member("a", 1));
		DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode newFile = add(newRoot, file("/p/a.java"));
		add(newFile, member("a", 2));
		List<String> b = DiffTreeNodes.keyOf(add(newFile, member("b", FileStatus.ADDED, 1)));
		List<String> a = DiffTreeNodes.keyOf(oldA);

		Map<List<String>, StructureDiffPanel.Flash> flashes =
				StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(), 100);

		assertFalse(flashes.get(a).appeared());
		assertEquals(1.0, flashes.get(a).grown(100));
		assertTrue(flashes.get(b).appeared());
		assertEquals(0.0, flashes.get(b).grown(100));
		double halfway = flashes.get(b).grown(100 + StructureDiffPanel.GROW_MILLIS / 2);
		assertTrue("eases out: past half at half time, " + halfway, halfway > 0.5 && halfway < 1);
		assertEquals(1.0, flashes.get(b).grown(100 + StructureDiffPanel.GROW_MILLIS));
	}

	/** A status change updates a running flash instead of being skipped with the other content changes. */
	public void testStatusChangeFlashesWhileItsNodeIsRunning() {
		DefaultMutableTreeNode oldRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode oldA = add(add(oldRoot, file("/p/a.java")), member("a", FileStatus.ADDED, 1));
		DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode();
		add(add(newRoot, file("/p/a.java")), member("a", FileStatus.MODIFIED, 1));
		List<String> a = DiffTreeNodes.keyOf(oldA);

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
		List<String> a = DiffTreeNodes.keyOf(oldA);

		Map<List<String>, StructureDiffPanel.Flash> flashes =
				StructureDiffPanel.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0);

		assertEquals(Set.of(a), flashes.keySet());
		assertSame(oldA.getUserObject(), flashes.get(a).vanished());
	}

	private static MemberLinks.Link link(VirtualFile file, String... keys) {
		return new MemberLinks.Link(file, 0, 0, List.of(keys), List.of(keys), Collections.nCopies(keys.length, null), "", 1, false, false);
	}

	public void testRelatedNodesMatchMembersAndFallBackToCodeMembersOnly() {
		VirtualFile file = myFixture.addFileToProject("A.java", "").getVirtualFile();
		MemberDiff method = new MemberDiff("m()", "m()", "m()", "m()", null, FileStatus.MODIFIED, 0, 0, 1, 1, false, 0, List.of());
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(new FileDiff(null, file.getPath(), null, FileStatus.MODIFIED,
				"", "", 1, 1, List.of()));
		DefaultMutableTreeNode classNode = new DefaultMutableTreeNode(member("C", 1));
		DefaultMutableTreeNode methodNode = new DefaultMutableTreeNode(method);
		root.add(fileNode);
		fileNode.add(classNode);
		classNode.add(methodNode);
		VirtualFile other = myFixture.addFileToProject("B.java", "").getVirtualFile();

		assertEquals(Set.of(methodNode), StructureDiffPanel.relatedNodes(root,
				new MemberLinks.Links(List.of(link(file, "C", "m()")), false, List.of(), false)));
		assertEquals(Set.of(classNode), StructureDiffPanel.relatedNodes(root,
				new MemberLinks.Links(List.of(), false, List.of(link(file, "C")), false)));
		// a hidden nested member of a code member highlights the code member
		assertEquals(Set.of(methodNode), StructureDiffPanel.relatedNodes(root,
				new MemberLinks.Links(List.of(link(file, "C", "m()", "Runnable")), false, List.of(), false)));
		// an unchanged member of a class is not related to the class; a file outside the tree matches nothing
		assertEquals(Set.of(), StructureDiffPanel.relatedNodes(root,
				new MemberLinks.Links(List.of(link(file, "C", "unchanged()"), link(other, "B")), false, List.of(link(file)), false)));
	}
}
