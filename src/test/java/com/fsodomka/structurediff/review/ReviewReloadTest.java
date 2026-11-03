package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.settings.StructureDiffSettings;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.HeavyPlatformTestCase;
import com.intellij.testFramework.PlatformTestUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Reload reads the review files from disk, not the IDE's copy: a file written outside the IDE whose change the file
 * watcher missed. A heavy test, as a light project's directory is in memory and never stale.
 */
public class ReviewReloadTest extends HeavyPlatformTestCase {
	private static String sarif(String message) {
		return """
				{"version": "2.1.0", "runs": [{"results": [{"ruleId": "CORRECTNESS-1", "message": {"text": "%s"},
				  "locations": [{"physicalLocation": {"artifactLocation": {"uri": "A.txt"}, "region": {"startLine": 2}}}]}]}]}
				""".formatted(message);
	}

	private List<String> shown() {
		return ReviewFindingsService.getInstance(getProject()).placements().stream()
				.map(placement -> placement.finding().title()).toList();
	}

	public void testReloadReadsAReviewChangedOutsideTheIde() throws Exception {
		// the project directory, which the reviews are looked up in, as the test project has no VCS roots
		Path dir = PlatformTestUtil.getOrCreateProjectBaseDir(getProject()).toNioPath();
		Files.writeString(dir.resolve("A.txt"), "class A {\n\tint ttl = 5;\n}\n");
		Path review = dir.resolve(StructureDiffSettings.DEFAULT_REVIEWS_DIR).resolve("20261102-120000-review.sarif");
		Files.createDirectories(review.getParent());
		Files.writeString(review, sarif("Before."), StandardCharsets.UTF_8);
		LocalFileSystem.getInstance().refreshAndFindFileByNioFile(review);
		ReviewFindingsService service = ReviewFindingsService.getInstance(getProject());
		service.reload();
		waitFor(() -> shown().equals(List.of("Before.")));
		assertEquals(List.of("Before."), shown());

		// written behind the IDE's back, as when the file watcher misses the change; tests run without a watcher
		Files.writeString(review, sarif("After."), StandardCharsets.UTF_8);
		service.reload();
		waitFor(() -> shown().equals(List.of("After.")));
		assertEquals(List.of("After."), shown());
	}

	/** Dispatches events until {@code condition} holds or 10 seconds passed; the VFS refresh finishes asynchronously. */
	private static void waitFor(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 10_000;
		do {
			NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
			PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
			if (condition.getAsBoolean()) {
				return;
			}
			Thread.sleep(20);
		}
		while (System.currentTimeMillis() < deadline);
	}
}
