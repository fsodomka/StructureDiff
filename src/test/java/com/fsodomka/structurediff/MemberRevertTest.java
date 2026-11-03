package com.fsodomka.structurediff;

import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
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
		return new MemberDiff(label, label, label, label, null, status, 0, 0, 1, 1, false, 0, List.of());
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

	/** The before version is found by the member's before key, not by its name, which an overload shares. */
	public void testRevertsChangedSignatureNextToOverload() {
		String before = """
				class A {
					void b() {}

					void b(int x) { int y = 1; }
				}
				""";
		String after = before.replace("void b(int x)", "void b(int x, int z)");
		FileDiff file = new FileDiff(null, "A.java", JavaFileType.INSTANCE, FileStatus.MODIFIED, before, after, 1, 1, null);
		MemberDiff changed = new MemberDiff("b(int, int): void", "b(int): void", "b(int, int): void", "b(int): void", null,
				FileStatus.MODIFIED, 0, 0, 1, 1, false, 0, List.of());
		List<MemberDiff> path = List.of(member("A", FileStatus.MODIFIED), changed);
		MemberRevert.Result result = ReadAction.compute(() ->
				MemberRevert.compute(getProject(), file, "A.java", JavaFileType.INSTANCE, after, path));
		assertNull(result.error());
		MemberRevert.Edit edit = result.edit();
		assertEquals(before, after.substring(0, edit.start()) + edit.text() + after.substring(edit.end()));
	}

	/** A neighbor whose signature changed is no anchor: by name it would be an overload elsewhere, so the other neighbor is. */
	public void testRestoresRemovedMemberPastNeighborWithChangedSignature() {
		String before = """
				class A {
					void b() {}

					void b(int x) {}

					void d() {}

					void e() {}
				}
				""";
		String after = before.replace("void b(int x)", "void b(int x, int z)").replace("\tvoid d() {}\n\n", "");
		FileDiff file = new FileDiff(null, "A.java", JavaFileType.INSTANCE, FileStatus.MODIFIED, before, after, 1, 1, null);
		List<MemberDiff> path = List.of(member("A", FileStatus.MODIFIED), member("d(): void", FileStatus.DELETED));
		MemberRevert.Result result = ReadAction.compute(() ->
				MemberRevert.compute(getProject(), file, "A.java", JavaFileType.INSTANCE, after, path));
		assertNull(result.error());
		MemberRevert.Edit edit = result.edit();
		assertEquals(before.replace("void b(int x)", "void b(int x, int z)"),
				after.substring(0, edit.start()) + edit.text() + after.substring(edit.end()));
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
