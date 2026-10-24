package com.fsodomka.structurediff;

import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.SimpleContentRevision;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.vcsUtil.VcsUtil;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;

/** The change id tells a member modified again from one that is unchanged since the previous refresh. */
public class StructureDiffComputerTest extends BasePlatformTestCase {
	private static final String BEFORE = """
			class A {
				void a() {}

				void b() { int x = 1; }
			}
			""";

	/** Keys the structure view gives the class and the method, see {@link #member}. */
	private static final String CLASS_KEY = "A";
	private static final String METHOD_KEY = "b(): void";

	/** The members of {@code after}, compared to {@link #BEFORE}. */
	private FileDiff diff(String after) {
		// a local path without a VFS file, which the content revisions do not need
		FilePath path = VcsUtil.getFilePath("/project/A.java", false);
		Change change = new Change(new SimpleContentRevision(BEFORE, path, "1"), new SimpleContentRevision(after, path, "2"));
		FileDiff loaded = StructureDiffComputer.load(change, new HashMap<>(), null);
		return ReadAction.compute(() -> StructureDiffComputer.withMembers(getProject(), loaded));
	}

	/** The member with {@code key} anywhere in the file's members. */
	private static MemberDiff member(FileDiff file, String key) {
		Deque<MemberDiff> members = new ArrayDeque<>(file.members());
		while (!members.isEmpty()) {
			MemberDiff member = members.remove();
			if (member.key().equals(key)) {
				return member;
			}
			members.addAll(member.children());
		}
		throw new AssertionError("No member " + key + " in " + file.members());
	}

	public void testChangeIdChangesWhenAModifiedMemberIsEditedAgain() {
		MemberDiff first = member(diff(BEFORE.replace("int x = 1;", "int x = 2;")), METHOD_KEY);
		MemberDiff second = member(diff(BEFORE.replace("int x = 1;", "int x = 3;")), METHOD_KEY);

		// both edits change one line: status, label and line counts are the same, only the content differs
		assertEquals(FileStatus.MODIFIED, second.status());
		assertEquals(first.label(), second.label());
		assertEquals(first.linesAdded(), second.linesAdded());
		assertEquals(first.linesRemoved(), second.linesRemoved());
		assertTrue(first.changeId() != second.changeId());
		// the container covers its children: a change in a child is a change of the container
		assertTrue(member(diff(BEFORE.replace("int x = 1;", "int x = 2;")), CLASS_KEY).changeId()
				!= member(diff(BEFORE.replace("int x = 1;", "int x = 3;")), CLASS_KEY).changeId());
	}

	public void testChangeIdIsStableForTheSameContent() {
		String after = BEFORE.replace("int x = 1;", "int x = 2;");

		FileDiff first = diff(after);
		FileDiff again = diff(after);

		assertEquals(member(first, METHOD_KEY).changeId(), member(again, METHOD_KEY).changeId());
		assertEquals(member(first, CLASS_KEY).changeId(), member(again, CLASS_KEY).changeId());
	}

	/** An edit above a member moves its offsets but not its content; it must not look changed. */
	public void testChangeIdIgnoresOffsetsMovedByAnotherMember() {
		MemberDiff first = member(diff(BEFORE.replace("int x = 1;", "int x = 2;")), METHOD_KEY);
		String after = BEFORE
				.replace("void a() {}", "void a() {\n\t\tint y = 0;\n\t}")
				.replace("int x = 1;", "int x = 2;");
		MemberDiff moved = member(diff(after), METHOD_KEY);

		assertTrue(first.afterOffset() != moved.afterOffset());
		assertEquals(first.changeId(), moved.changeId());
	}
}
