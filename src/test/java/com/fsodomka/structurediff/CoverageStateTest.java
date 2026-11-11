package com.fsodomka.structurediff;

import com.fsodomka.structurediff.diff.FileDiff;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.ServiceContainerUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;

public class CoverageStateTest extends BasePlatformTestCase {
	private static final CoverageLines.Run A = new CoverageLines.Run("A", 1);
	private static final CoverageLines.Run B = new CoverageLines.Run("B", 2);

	/** A run that is switched by hand; {@code load} fails while {@code failure} is set. */
	private static final class FakeLines implements CoverageLines {
		volatile @Nullable Run run = A;
		volatile @Nullable RuntimeException failure;
		volatile @Nullable Data data = file -> null;
		final AtomicInteger loads = new AtomicInteger();

		@Override
		public @Nullable Run run() {
			return run;
		}

		@Override
		public @Nullable Data load() {
			loads.incrementAndGet();
			RuntimeException e = failure;
			if (e != null) {
				throw e;
			}
			return data;
		}

		@Override
		public void onChange(Runnable changed, Disposable parent) {
		}
	}

	private FakeLines lines;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		lines = new FakeLines();
		ServiceContainerUtil.registerOrReplaceServiceInstance(getProject(), CoverageLines.class, lines, getTestRootDisposable());
	}

	private CoverageState state(List<FileDiff> files) {
		return new CoverageState(getProject(), () -> files, () -> true, () -> {}, getTestRootDisposable());
	}

	private static void waitUntilDone(CoverageState state) {
		PlatformTestUtil.waitWithEventsDispatching("coverage still computing", () -> !state.computing(), 10);
	}

	public void testSwitchingBackToTheComputedRunEndsTheComputationInProgress() {
		CoverageState state = state(List.of());
		state.update();
		waitUntilDone(state);

		lines.run = B;
		state.update();
		assertNotNull("B computes", state.overlay());
		// back to A before B finishes: A's counts are still there
		lines.run = A;
		state.update();
		assertNull(state.overlay());
		// B's result comes in later and is dropped
		PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		assertNull(state.overlay());
	}

	public void testAFailedLoadEndsTheComputationAndTheNextUpdateTriesAgain() {
		lines.failure = new IllegalStateException("broken run file");
		CoverageState state = state(List.of());
		state.update();
		waitUntilDone(state);
		assertEquals(1, lines.loads.get());

		lines.failure = null;
		state.update();
		waitUntilDone(state);
		assertEquals("not taken as computed", 2, lines.loads.get());
	}

	public void testARunWithoutDataYetIsLoadedAgainOnTheNextUpdate() {
		CoverageLines.Data data = lines.data;
		lines.data = null;
		CoverageState state = state(List.of());
		state.update();
		waitUntilDone(state);
		assertEquals(1, lines.loads.get());

		// its data calculated: the same run and files
		lines.data = data;
		state.update();
		waitUntilDone(state);
		assertEquals(2, lines.loads.get());
	}

	public void testAFailedComputationEndsIt() throws IOException {
		Path dir = Files.createTempDirectory("coverage");
		VfsRootAccess.allowRootAccess(getTestRootDisposable(), dir.toString());
		Path source = Files.writeString(dir.resolve("A.java"), "class A {\n}\n");
		VirtualFile file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
		assertNotNull(file);
		lines.data = f -> {
			throw new IllegalStateException("broken class data");
		};
		CoverageState state = state(List.of(new FileDiff(null, file.getPath(), null, FileStatus.MODIFIED, "", "", 1, 1, List.of())));
		state.update();
		waitUntilDone(state);
	}

	public void testPercentIsZeroOrHundredOnlyWhenNoneOrAllAreCovered() {
		assertEquals(0, new CoverageState.Counts(0, 10, false).percent());
		assertEquals(100, new CoverageState.Counts(10, 10, false).percent());
		assertEquals(99, new CoverageState.Counts(995, 1000, false).percent());
		assertEquals(1, new CoverageState.Counts(1, 1000, false).percent());
		assertEquals(50, new CoverageState.Counts(1, 2, false).percent());
		assertEquals(67, new CoverageState.Counts(2, 3, false).percent());
	}

	public void testAggregateCountsEachLineOnItsMemberAndEveryContainer() {
		Map<List<String>, int[]> counts = CoverageState.aggregate(
				List.of(List.of("C", "a"), List.of("C", "a"), List.of("C", "b"), List.of("C"), List.of()),
				List.of(true, false, true, true, false));
		assertArrayEquals(new int[]{1, 2}, counts.get(List.of("C", "a")));
		assertArrayEquals(new int[]{1, 1}, counts.get(List.of("C", "b")));
		// a class's own lines and its members'
		assertArrayEquals(new int[]{3, 4}, counts.get(List.of("C")));
		// the whole file, lines outside any member too
		assertArrayEquals(new int[]{3, 5}, counts.get(List.of()));
		assertEquals(4, counts.size());
	}
}
