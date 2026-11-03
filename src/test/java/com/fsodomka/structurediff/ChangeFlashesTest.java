package com.fsodomka.structurediff;

import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.*;

public class ChangeFlashesTest extends BasePlatformTestCase {
	private static MemberDiff member(String key, FileStatus status, long changeId) {
		return new MemberDiff(key, key, key, key, null, status, 0, 0, 1, 1, true, changeId, List.of());
	}

	private static MemberDiff member(String key, long changeId) {
		return member(key, FileStatus.MODIFIED, changeId);
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

	/** A member modified again keeps its status; its new change id still makes it flash. */
	public void testModifiedMemberFlashesAgainWhenItsContentChanged() {
		assertTrue(ChangeFlashes.changedSince(member("m", 1), member("m", 2)));
		assertFalse(ChangeFlashes.changedSince(member("m", 2), member("m", 2)));
	}

	public void testFlashesWhenTheStatusChanges() {
		MemberDiff added = member("m", FileStatus.ADDED, 1);

		assertTrue(ChangeFlashes.changedSince(member("m", 1), added));
		assertTrue(ChangeFlashes.changedSince(added, member("m", 1)));
	}

	/** A file that stays modified is not a change: every member edit would also flash the file. */
	public void testModifiedFileDoesNotFlashWhenOnlyItsContentChanged() {
		FileDiff before = file("/p/a.java", FileStatus.MODIFIED, "void a() {}", "void a() { int x = 1; }");
		FileDiff after = file("/p/a.java", FileStatus.MODIFIED, "void a() {}", "void a() { int x = 2; }");

		assertFalse(ChangeFlashes.changedSince(before, after));
		assertTrue(ChangeFlashes.changedSince(file("/p/a.java", FileStatus.ADDED), file("/p/a.java")));
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

		assertEquals(Set.of(b), ChangeFlashes.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0).keySet());
		assertEquals(Set.of(a, b), ChangeFlashes.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(), 0).keySet());
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

		Map<List<String>, ChangeFlashes.Flash> flashes =
				ChangeFlashes.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(), 100);

		assertFalse(flashes.get(a).appeared());
		assertEquals(1.0, flashes.get(a).grown(100));
		assertTrue(flashes.get(b).appeared());
		assertEquals(0.0, flashes.get(b).grown(100));
		double halfway = flashes.get(b).grown(100 + ChangeFlashes.GROW_MILLIS / 2);
		assertTrue("eases out: past half at half time, " + halfway, halfway > 0.5 && halfway < 1);
		assertEquals(1.0, flashes.get(b).grown(100 + ChangeFlashes.GROW_MILLIS));
	}

	/** A status change updates a running flash instead of being skipped with the other content changes. */
	public void testStatusChangeFlashesWhileItsNodeIsRunning() {
		DefaultMutableTreeNode oldRoot = new DefaultMutableTreeNode();
		DefaultMutableTreeNode oldA = add(add(oldRoot, file("/p/a.java")), member("a", FileStatus.ADDED, 1));
		DefaultMutableTreeNode newRoot = new DefaultMutableTreeNode();
		add(add(newRoot, file("/p/a.java")), member("a", FileStatus.MODIFIED, 1));
		List<String> a = DiffTreeNodes.keyOf(oldA);

		Map<List<String>, ChangeFlashes.Flash> flashes =
				ChangeFlashes.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0);

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

		Map<List<String>, ChangeFlashes.Flash> flashes =
				ChangeFlashes.newFlashes(nodes(oldRoot), nodes(newRoot), Set.of(a), 0);

		assertEquals(Set.of(a), flashes.keySet());
		assertSame(oldA.getUserObject(), flashes.get(a).vanished());
	}
}
