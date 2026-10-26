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
import java.util.Map;
import java.util.TreeMap;

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
		return diff(BEFORE, after);
	}

	private FileDiff diff(String before, String after) {
		// a local path without a VFS file, which the content revisions do not need
		FilePath path = VcsUtil.getFilePath("/project/A.java", false);
		Change change = new Change(new SimpleContentRevision(before, path, "1"), new SimpleContentRevision(after, path, "2"));
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

	/** A changed signature keeps the before key, which the member history continues with. */
	public void testBeforeKeyOfAChangedSignature() {
		MemberDiff changed = member(diff(BEFORE.replace("void b() {", "void b(int y) {")), "b(int): void");

		assertEquals(METHOD_KEY, changed.beforeKey());
		assertEquals(CLASS_KEY, member(diff(BEFORE.replace("int x = 1;", "int x = 2;")), CLASS_KEY).beforeKey());
	}

	/** The before key carries the {@code #n} of a repeated label, which the after key need not have. */
	public void testBeforeKeyOfARepeatedLabel() {
		String before = BEFORE.replace("void a() {}", "void c() {}\n\tvoid c() {}");
		String after = BEFORE.replace("void a() {}", "void c() {}\n\tvoid c(int y) {}");

		assertEquals("c(): void#2", member(diff(before, after), "c(int): void").beforeKey());
	}

	/** The status of each member of the class, by key; a renamed member is one modified member with its old key. */
	private Map<String, String> statuses(String before, String after) {
		Map<String, String> result = new TreeMap<>();
		for (MemberDiff m : member(diff(before, after), CLASS_KEY).children()) {
			result.put(m.key(), m.status().getId() + " " + m.beforeKey());
		}
		return result;
	}

	public void testRenameWithTheSameTextIsModified() {
		MemberDiff renamed = member(diff(BEFORE.replace("void b() {", "void c() {")), "c(): void");

		assertEquals(FileStatus.MODIFIED, renamed.status());
		assertEquals(METHOD_KEY, renamed.beforeKey());
		assertEquals("b(): void", renamed.beforeLabel());
	}

	/** A recursive call renamed with the method is part of the rename. */
	public void testRenameOfARecursiveMethodIsModified() {
		String before = BEFORE.replace("int x = 1;", "b();");
		MemberDiff renamed = member(diff(before, before.replace("b()", "c()")), "c(): void");

		assertEquals(METHOD_KEY, renamed.beforeKey());
	}

	public void testRenameWithABodyEditIsRemovedAndAdded() {
		assertEquals(Map.of("b(): void", "DELETED b(): void", "c(): void", "ADDED c(): void"),
				statuses(BEFORE, BEFORE.replace("void b() { int x = 1; }", "void c() { int x = 2; }")));
	}

	/** Two removed members with the same text: which one was renamed is unknown, so neither is paired. */
	public void testAmbiguousRenameIsRemovedAndAdded() {
		String before = BEFORE.replace("void a() {}", "void a() {}\n\tvoid d() {}");
		String after = BEFORE.replace("void a() {}", "void e() {}");

		assertEquals(Map.of("a(): void", "DELETED a(): void", "d(): void", "DELETED d(): void", "e(): void", "ADDED e(): void"),
				statuses(before, after));
	}
}
