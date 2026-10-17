package com.fsodomka.structurediff;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsDirectoryMapping;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class BranchChangeSourceTest extends BasePlatformTestCase {
	private Path root;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		root = Files.createTempDirectory("branch-source");
	}

	@Override
	protected void tearDown() throws Exception {
		try {
			ProjectLevelVcsManager.getInstance(getProject()).setDirectoryMappings(List.of());
			FileUtil.delete(root);
		}
		finally {
			super.tearDown();
		}
	}

	public void testBranchCommitsAndUncommittedChangesSinceMergeBase() throws Exception {
		git("init", "-b", "main");
		// a global core.autocrlf would turn the revisions into CRLF
		git("config", "core.autocrlf", "false");
		write("Shared.txt", "a\n");
		write("Edited.txt", "1\n");
		commit("base");
		git("checkout", "-b", "feature");
		write("Committed.txt", "branch\n");
		write("Edited.txt", "2\n");
		commit("on the branch");
		git("checkout", "main");
		write("MainOnly.txt", "main\n");
		commit("on main after the fork");
		git("checkout", "feature");
		write("Edited.txt", "3\n");
		write("Shared.txt", "b\n");

		VirtualFile rootFile = map();
		BranchChangeSource source = new BranchChangeSource(getProject());
		// repository lookup and git run off the EDT, as in the panel's refresh; waiting keeps dispatching EDT events
		Collection<Change> changes = PlatformTestUtil.waitForFuture(ApplicationManager.getApplication().executeOnPooledThread(() -> {
			GitRepository repository = GitRepositoryManager.getInstance(getProject()).getRepositoryForRoot(rootFile);
			assertNotNull("Git repository not registered", repository);
			repository.update();
			assertEquals("feature", repository.getCurrentBranchName());
			return source.changes();
		}), TimeUnit.MINUTES.toMillis(1));

		Map<String, Change> byName = changes.stream().collect(Collectors.toMap(c -> ChangesUtil.getFilePath(c).getName(), c -> c));
		assertEquals(changes.toString(), 3, changes.size());
		assertEquals(Change.Type.NEW, byName.get("Committed.txt").getType());
		// committed 1 -> 2 on the branch, then 3 uncommitted: one change from the merge base
		Change edited = byName.get("Edited.txt");
		assertEquals("1\n", edited.getBeforeRevision().getContent());
		assertEquals("3\n", edited.getAfterRevision().getContent());
		assertEquals(Change.Type.MODIFICATION, byName.get("Shared.txt").getType());
		assertEquals("vs main", source.title());
	}

	/** Maps the root to Git; on the EDT, which refreshing and mapping need. */
	private VirtualFile map() {
		VirtualFile rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root);
		assertNotNull(rootFile);
		VfsUtil.markDirtyAndRefresh(false, true, true, rootFile);
		ProjectLevelVcsManager.getInstance(getProject()).setDirectoryMappings(List.of(new VcsDirectoryMapping(rootFile.getPath(), "Git")));
		return rootFile;
	}

	private void write(String name, String text) throws IOException {
		Files.writeString(root.resolve(name), text, StandardCharsets.UTF_8);
	}

	private void commit(String message) throws Exception {
		git("add", "-A");
		git("-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-q", "-m", message);
	}

	private void git(String... args) throws Exception {
		List<String> command = new java.util.ArrayList<>(List.of("git"));
		command.addAll(List.of(args));
		Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertEquals(String.join(" ", command) + "\n" + output, 0, process.waitFor());
	}
}
