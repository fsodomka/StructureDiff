package com.fsodomka.structurediff.history;

import com.fsodomka.structurediff.history.HistoryRow.Kind;
import com.fsodomka.structurediff.history.MemberHistory.Batch;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.RepositoryLocation;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.SimpleContentRevision;
import com.intellij.openapi.vcs.history.VcsFileRevisionEx;
import com.intellij.openapi.vcs.history.VcsRevisionNumber;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

/** The walk over fake revisions, newest first, finds the commits that changed one member. */
public class MemberHistoryTest extends BasePlatformTestCase {
	private static final String PATH = "/project/A.java";
	private static final String V1 = """
			class A {
				void a() {}

				void b() { int x = 1; }
			}
			""";
	private static final List<String> B = List.of("A", "b(): void");

	private int revisions;

	/** The walk loads contents and parses in cancellable read actions, which are not allowed on the EDT. */
	@Override
	protected boolean runInDispatchThread() {
		return false;
	}

	private Batch walk(List<String> keyPath, int maxPairs, FakeRevision... newestFirst) {
		return MemberHistory.walk(getProject(), List.of(newestFirst), VcsUtil.getFilePath(PATH, false), keyPath, 0, maxPairs,
				null, row -> {
				}, MemberHistory.newCache());
	}

	private FakeRevision rev(String text) {
		return rev(PATH, text);
	}

	private FakeRevision rev(String path, @Nullable String text) {
		return new FakeRevision(++revisions, VcsUtil.getFilePath(path, false), text);
	}

	private static List<Kind> kinds(Batch batch) {
		return batch.rows().stream().map(HistoryRow::kind).toList();
	}

	public void testBodyEditIsModifiedAndAnotherMembersEditIsNoRow() {
		FakeRevision v1 = rev(V1);
		FakeRevision v2 = rev(V1.replace("int x = 1;", "int x = 2;"));
		FakeRevision v3 = rev(V1.replace("int x = 1;", "int x = 2;").replace("void a() {}", "void a() { a(); }"));

		Batch batch = walk(B, 50, v3, v2, v1);

		assertEquals(List.of(Kind.MODIFIED, Kind.ADDED), kinds(batch));
		assertSame(v2, batch.rows().getFirst().newer());
		assertEquals("+1 -1", batch.rows().getFirst().detail());
		assertEquals(-1, batch.nextIndex());
	}

	public void testSignatureChangeTracksTheOldKey() {
		FakeRevision v1 = rev(V1);
		FakeRevision v2 = rev(V1.replace("void b() {", "void b(int y) {"));
		FakeRevision v3 = rev(V1.replace("void b() { int x = 1; }", "void b(int y) { int x = 2; }"));

		Batch batch = walk(List.of("A", "b(int): void"), 50, v3, v2, v1);

		assertEquals(List.of(Kind.MODIFIED, Kind.SIGNATURE, Kind.ADDED), kinds(batch));
		assertEquals(List.of("A", "b(): void"), batch.rows().get(1).olderKeyPath());
		assertEquals(List.of("A", "b(): void"), batch.rows().get(2).newerKeyPath());
	}

	public void testPathChangeWithUnchangedMemberIsMoved() {
		FakeRevision v1 = rev(V1);
		FakeRevision v2 = rev("/project/billing/A.java", V1);
		FakeRevision v3 = rev("/project/billing/A.java", V1.replace("void a() {}", "void a() { a(); }"));

		Batch batch = walk(B, 50, v3, v2, v1);

		assertEquals(List.of(Kind.MOVED, Kind.ADDED), kinds(batch));
		assertEquals("/project/A.java → /project/billing/A.java", batch.rows().getFirst().detail());
	}

	/** A signature change in the commit that moved the file stays in the moved row's detail. */
	public void testPathChangeKeepsASignatureChangeInTheDetail() {
		FakeRevision v1 = rev(V1);
		FakeRevision v2 = rev("/project/billing/A.java", V1.replace("void b() {", "void b(int y) {"));

		Batch batch = walk(List.of("A", "b(int): void"), 50, v2, v1);

		assertEquals(List.of(Kind.MOVED, Kind.ADDED), kinds(batch));
		assertEquals("/project/A.java → /project/billing/A.java   b(): void → b(int): void   +1 -1",
				batch.rows().getFirst().detail());
	}

	/** A rename with the same text is followed back to the old name. */
	public void testNameChangeIsRenamed() {
		FakeRevision v1 = rev(V1);
		FakeRevision v2 = rev(V1.replace("void b() {", "void c() {"));

		Batch batch = walk(List.of("A", "c(): void"), 50, v2, v1);

		assertEquals(List.of(Kind.RENAMED, Kind.ADDED), kinds(batch));
		assertEquals("b → c", batch.rows().getFirst().detail());
		assertEquals(B, batch.rows().getFirst().olderKeyPath());
	}

	/** A walk loads each revision once into the cache it is given, so the next batch and the diff reuse them. */
	public void testWalkFillsTheGivenCache() {
		FakeRevision v1 = rev(V1);
		FakeRevision v2 = rev(V1.replace("int x = 1;", "int x = 2;"));
		java.util.Map<String, String> cache = MemberHistory.newCache();

		MemberHistory.walk(getProject(), List.of(v2, v1), VcsUtil.getFilePath(PATH, false), B, 0, 50, null, row -> {
		}, cache);

		assertEquals(2, cache.size());
	}

	public void testCommitAddingTheMemberEndsTheWalk() {
		FakeRevision v1 = rev("class A {}\n");
		FakeRevision v2 = rev("class A {\n\tvoid a() {}\n}\n");
		FakeRevision v3 = rev(V1);

		Batch batch = walk(B, 50, v3, v2, v1);

		assertEquals(List.of(Kind.ADDED), kinds(batch));
		assertSame(v3, batch.rows().getFirst().newer());
		assertEquals(-1, batch.nextIndex());
	}

	/** A member added since the latest commit: one row at once, instead of walking every revision. */
	public void testMemberNotInTheLatestCommitEndsTheWalk() {
		FakeRevision v1 = rev(V1);
		FakeRevision v2 = rev(V1.replace("int x = 1;", "int x = 2;"));

		Batch batch = walk(List.of("A", "c(): void"), 50, v2, v1);

		assertEquals(List.of(Kind.END), kinds(batch));
		assertEquals("not in the latest commit", batch.rows().getFirst().detail());
	}

	/** A key whose name, but not signature, is in the latest commit ends at once instead of walking every revision. */
	public void testKeyMatchingOnlyByNameIsNotInTheLatestCommit() {
		FakeRevision v1 = rev(V1);
		FakeRevision v2 = rev(V1.replace("int x = 1;", "int x = 2;"));

		Batch batch = walk(List.of("A", "b(int): void"), 50, v2, v1);

		assertEquals(List.of(Kind.END), kinds(batch));
		assertEquals("not in the latest commit", batch.rows().getFirst().detail());
	}

	/** A signature changed locally is tracked from its committed key, so the commits before it are found. */
	public void testLocalSignatureChangeContinuesWithTheCommittedKey() {
		FakeRevision v1 = rev(V1);
		FakeRevision v2 = rev(V1.replace("int x = 1;", "int x = 2;"));
		FilePath path = VcsUtil.getFilePath(PATH, false);
		String working = V1.replace("void b() { int x = 1; }", "void b(int y) { int x = 2; }");
		Change local = new Change(new SimpleContentRevision(V1.replace("int x = 1;", "int x = 2;"), path, "2"),
				new SimpleContentRevision(working, path, "working"));

		List<String> committed = MemberHistory.committedKeyPath(getProject(), local, List.of("A", "b(int): void"));

		assertEquals(B, committed);
		assertEquals(List.of(Kind.MODIFIED, Kind.ADDED), kinds(walk(committed, 50, v2, v1)));
	}

	public void testContentFailingToLoadEndsTheWalk() {
		FakeRevision v1 = rev(PATH, null);
		FakeRevision v2 = rev(V1);

		Batch batch = walk(B, 50, v2, v1);

		assertEquals(List.of(Kind.END), kinds(batch));
		assertEquals("content unavailable", batch.rows().getFirst().detail());
	}

	public void testMaxPairsStopsAndReturnsTheNextIndex() {
		FakeRevision v1 = rev(V1);
		FakeRevision v2 = rev(V1.replace("void b() {", "void b(int y) {"));
		FakeRevision v3 = rev(V1.replace("void b() { int x = 1; }", "void b(int y) { int x = 2; }"));

		Batch first = walk(List.of("A", "b(int): void"), 1, v3, v2, v1);

		assertEquals(List.of(Kind.MODIFIED), kinds(first));
		assertEquals(1, first.nextIndex());
		Batch rest = MemberHistory.walk(getProject(), List.of(v3, v2, v1), VcsUtil.getFilePath(PATH, false), first.keyPath(),
				first.nextIndex(), 50, null, row -> {
				}, MemberHistory.newCache());
		assertEquals(List.of(Kind.SIGNATURE, Kind.ADDED), kinds(rest));
	}

	private static final class FakeRevision extends VcsFileRevisionEx {
		private final int number;
		private final FilePath path;
		private final String text;

		FakeRevision(int number, FilePath path, @Nullable String text) {
			this.number = number;
			this.path = path;
			this.text = text;
		}

		@Override
		public byte @NotNull [] loadContent() throws IOException {
			if (text == null) {
				throw new IOException("missing object");
			}
			return text.getBytes(StandardCharsets.UTF_8);
		}

		@Override
		@Deprecated
		public byte[] getContent() throws IOException {
			return loadContent();
		}

		@Override
		public @NotNull FilePath getPath() {
			return path;
		}

		@Override
		public @NotNull VcsRevisionNumber getRevisionNumber() {
			return new VcsRevisionNumber.Int(number);
		}

		@Override
		public Date getRevisionDate() {
			return new Date(number * 1000L);
		}

		@Override
		public Date getAuthorDate() {
			return getRevisionDate();
		}

		@Override
		public String getAuthor() {
			return "author";
		}

		@Override
		public String getAuthorEmail() {
			return null;
		}

		@Override
		public String getCommitterName() {
			return null;
		}

		@Override
		public String getCommitterEmail() {
			return null;
		}

		@Override
		public String getCommitMessage() {
			return "commit " + number;
		}

		@Override
		public String getBranchName() {
			return null;
		}

		@Override
		public RepositoryLocation getChangedRepositoryPath() {
			return null;
		}

		@Override
		public boolean isDeleted() {
			return false;
		}
	}
}
