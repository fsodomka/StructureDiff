package com.fsodomka.structurediff;

import com.intellij.openapi.diff.impl.patch.PatchReader;
import com.intellij.openapi.diff.impl.patch.TextFilePatch;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.SimpleContentRevision;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.vcsUtil.VcsUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DiffExporterTest extends BasePlatformTestCase {
	private Path root;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		root = Files.createTempDirectory("diff-exporter");
	}

	@Override
	protected void tearDown() throws Exception {
		try {
			FileUtil.delete(root);
		}
		finally {
			super.tearDown();
		}
	}

	private FilePath path(String relative) {
		return VcsUtil.getFilePath(root.resolve(relative).toString(), false);
	}

	public void testWritesChangesAsUnifiedDiff() throws Exception {		FilePath modified = path("src/Modified.txt");
		FilePath added = path("src/Added.txt");
		FilePath deleted = path("Deleted.txt");
		FilePath earlierDiff = path("reviews/20261015-090000-changes.diff");
		List<Change> changes = List.of(
				new Change(new SimpleContentRevision("a\nb\nc\n", modified, "1"), new SimpleContentRevision("a\nB\nc\n", modified, "2")),
				new Change(null, new SimpleContentRevision("new\n", added, "2")),
				new Change(new SimpleContentRevision("old\n", deleted, "1"), null),
				// an unversioned earlier export: left out
				new Change(null, new SimpleContentRevision("diff\n", earlierDiff, "2")));

		Path file = DiffExporter.export(getProject(), root, "reviews", changes, LocalDateTime.of(2026, 10, 15, 9, 5, 7));

		assertEquals(root.resolve("reviews").resolve("20261015-090507-changes.diff"), file);
		try (Stream<Path> files = Files.list(file.getParent())) {
			assertEquals("no temporary file left", Set.of(".gitignore", "20261015-090507-changes.diff"),
					files.map(p -> p.getFileName().toString()).collect(Collectors.toSet()));
		}
		// a new reviews/ ignores itself
		assertEquals("*\n", Files.readString(file.resolveSibling(".gitignore"), StandardCharsets.UTF_8));
		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text, text.contains("\n-b\n+B\n"));
		// paths are relative to the root
		Map<String, TextFilePatch> patches = new PatchReader(text).readTextPatches().stream()
				.collect(Collectors.toMap(p -> p.getAfterName() != null ? p.getAfterName() : p.getBeforeName(), p -> p));
		assertEquals(Set.of("src/Modified.txt", "src/Added.txt", "Deleted.txt"), patches.keySet());
		assertTrue(patches.get("src/Added.txt").isNewFile());
		assertTrue(patches.get("Deleted.txt").isDeletedFile());}

	public void testNumbersExportsInTheSameSecond() throws Exception {
		List<Change> changes = List.of(new Change(null, new SimpleContentRevision("new\n", path("Added.txt"), "2")));
		LocalDateTime now = LocalDateTime.of(2026, 10, 15, 9, 5, 7);

		Path first = DiffExporter.export(getProject(), root, "reviews", changes, now);
		Path second = DiffExporter.export(getProject(), root, "reviews", changes, now);
		Path third = DiffExporter.export(getProject(), root, "reviews", changes, now);

		assertEquals("20261015-090507-changes.diff", first.getFileName().toString());
		assertEquals("20261015-090507-changes-2.diff", second.getFileName().toString());
		assertEquals("20261015-090507-changes-3.diff", third.getFileName().toString());
		assertTrue(Files.exists(first));
	}

	public void testKeepsAnExistingReviewsDirectoryUnignored() throws Exception {
		Files.createDirectories(root.resolve("reviews"));
		FilePath added = path("Added.txt");

		DiffExporter.export(getProject(), root, "reviews", List.of(new Change(null, new SimpleContentRevision("new\n", added, "2"))),
				LocalDateTime.of(2026, 10, 15, 9, 5, 7));

		assertFalse(Files.exists(root.resolve("reviews").resolve(".gitignore")));
	}
}
