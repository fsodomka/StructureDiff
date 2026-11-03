package com.fsodomka.structurediff;

import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class UsagesControllerTest extends BasePlatformTestCase {
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

	private static MemberLinks.Link link(VirtualFile file, String... keys) {
		return new MemberLinks.Link(file, 0, 0, List.of(keys), List.of(keys), Collections.nCopies(keys.length, null), "", 1, false, false);
	}

	public void testContentStampChangesWithTheAfterTexts() {
		int stamp = UsagesController.contentStamp(List.of(file("/p/A.java", FileStatus.MODIFIED, "a", "b")));
		// the before text and the status do not change what the working copy uses
		assertEquals(stamp, UsagesController.contentStamp(List.of(file("/p/A.java", FileStatus.ADDED, "", "b"))));
		assertFalse(stamp == UsagesController.contentStamp(List.of(file("/p/A.java", FileStatus.MODIFIED, "a", "b2"))));
		assertFalse(stamp == UsagesController.contentStamp(List.of(file("/p/B.java", FileStatus.MODIFIED, "a", "b"))));
		assertFalse(stamp == UsagesController.contentStamp(List.of(file("/p/A.java", FileStatus.MODIFIED, "a", "b"), file("/p/B.java"))));
		assertFalse(stamp == UsagesController.contentStamp(List.of(file("/p/A.java", FileStatus.DELETED, "a", null))));
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

		assertEquals(Set.of(methodNode), UsagesController.relatedNodes(root,
				new MemberLinks.Links(List.of(link(file, "C", "m()")), false, List.of(), false)));
		assertEquals(Set.of(classNode), UsagesController.relatedNodes(root,
				new MemberLinks.Links(List.of(), false, List.of(link(file, "C")), false)));
		// a hidden nested member of a code member highlights the code member
		assertEquals(Set.of(methodNode), UsagesController.relatedNodes(root,
				new MemberLinks.Links(List.of(link(file, "C", "m()", "Runnable")), false, List.of(), false)));
		// an unchanged member of a class is not related to the class; a file outside the tree matches nothing
		assertEquals(Set.of(), UsagesController.relatedNodes(root,
				new MemberLinks.Links(List.of(link(file, "C", "unchanged()"), link(other, "B")), false, List.of(link(file)), false)));
	}
}
