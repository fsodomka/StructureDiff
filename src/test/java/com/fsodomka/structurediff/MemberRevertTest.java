package com.fsodomka.structurediff;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class MemberRevertTest extends BasePlatformTestCase {
	private static final String BEFORE = """
			class A {
				void a() {}

				void b() { int x = 1; }

				void c() {}
			}
			""";

	private static MemberDiff member(String label, FileStatus status) {
		return new MemberDiff(label, label, label, null, status, 0, 0, 1, 1, false, List.of());
	}

	private String revert(String after, String current, FileStatus status, String method) {
		FileDiff file = new FileDiff(null, "A.java", JavaFileType.INSTANCE, FileStatus.MODIFIED, BEFORE, after, 1, 1, null);
		List<MemberDiff> path = List.of(member("A", FileStatus.MODIFIED), member(method + "()", status));
		MemberRevert.Result result = ReadAction.compute(() ->
				MemberRevert.compute(getProject(), file, "A.java", JavaFileType.INSTANCE, current, path));
		if (result.error() != null) {
			return "error: " + result.error();
		}
		MemberRevert.Edit edit = result.edit();
		return current.substring(0, edit.start()) + edit.text() + current.substring(edit.end());
	}

	public void testRevertsModifiedMember() {
		String after = BEFORE.replace("int x = 1;", "int x = 2;");
		assertEquals(BEFORE, revert(after, after, FileStatus.MODIFIED, "b"));
	}

	public void testDeletesAddedMember() {
		String after = BEFORE.replace("\tvoid c() {}\n", "\tvoid c() {}\n\n\tvoid d() {}\n");
		assertEquals(BEFORE, revert(after, after, FileStatus.ADDED, "d"));
	}

	public void testRestoresRemovedMemberAfterPreviousSibling() {
		String after = BEFORE.replace("\tvoid b() { int x = 1; }\n\n", "");
		assertEquals(BEFORE, revert(after, after, FileStatus.DELETED, "b"));
	}

	public void testRestoresRemovedFirstMemberBeforeNextSibling() {
		String after = BEFORE.replace("\tvoid a() {}\n\n", "");
		assertEquals(BEFORE, revert(after, after, FileStatus.DELETED, "a"));
	}

	public void testRefusesWhenMemberChangedSinceRefresh() {
		String after = BEFORE.replace("int x = 1;", "int x = 2;");
		String current = BEFORE.replace("int x = 1;", "int x = 3;");
		assertTrue(revert(after, current, FileStatus.MODIFIED, "b").startsWith("error: The member changed"));
	}

	public void testRefusesWithoutSurvivingNeighbor() {
		String after = "class A {\n}\n";
		assertTrue(revert(after, after, FileStatus.DELETED, "b").startsWith("error: No neighbor"));
	}
}
