package com.fsodomka.structurediff;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vfs.VirtualFile;
import git4idea.GitBranch;
import git4idea.GitRevisionNumber;
import git4idea.branch.GitBranchesCollection;
import git4idea.changes.GitChangeUtils;
import git4idea.history.GitHistoryUtils;
import git4idea.repo.GitRepository;
import git4idea.repo.GitRepositoryChangeListener;
import git4idea.repo.GitRepositoryManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Changes of the branch since it forked from main, plus the uncommitted changes, as one change per file from the merge base
 * to the working tree, like a merge request shows them. Repositories without main or master show their uncommitted changes.
 */
final class BranchChangeSource implements ChangeSource {
	private static final Logger LOG = Logger.getInstance(BranchChangeSource.class);
	/** Base branches in order of preference; the remote-tracking branch is what a merge request compares to, even with a stale local one. */
	private static final List<String> BASES = List.of("origin/main", "main", "origin/master", "master");
	private final Project project;
	private final LocalChangeSource local;
	/** Merge base per repository root, for the HEAD and base hashes it was computed from. */
	private final Map<VirtualFile, MergeBase> mergeBases = new ConcurrentHashMap<>();
	private final Set<VirtualFile> warnedNoBase = ConcurrentHashMap.newKeySet();

	private record MergeBase(String key, String revision) {
	}

	BranchChangeSource(Project project) {
		this.project = project;
		local = new LocalChangeSource(project);
	}

	@Override
	public Collection<Change> changes() {
		List<Change> changes = new ArrayList<>();
		Set<VirtualFile> diffed = new HashSet<>();
		for (GitRepository repository : GitRepositoryManager.getInstance(project).getRepositories()) {
			VirtualFile root = repository.getRoot();
			String mergeBase = mergeBase(repository);
			if (mergeBase == null) {
				continue;
			}
			try {
				changes.addAll(GitChangeUtils.getDiffWithWorkingDir(project, root, mergeBase, null, false));
				diffed.add(root);
			}
			catch (VcsException e) {
				LOG.warn("Cannot diff " + root.getPath() + " with " + mergeBase, e);
			}
		}
		// the rest shows its uncommitted changes, as in the Uncommitted mode
		ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(project);
		for (Change change : local.changes()) {
			if (!diffed.contains(vcsManager.getVcsRootFor(ChangesUtil.getFilePath(change)))) {
				changes.add(change);
			}
		}
		return changes;
	}

	/** Merge base of HEAD and the base branch; null without a base branch, commits or common history. */
	private String mergeBase(GitRepository repository) {
		VirtualFile root = repository.getRoot();
		GitBranch base = baseBranch(repository);
		String head = repository.getCurrentRevision();
		if (base == null || head == null) {
			if (base == null && warnedNoBase.add(root)) {
				LOG.warn("No " + String.join(", ", BASES) + " in " + root.getPath() + "; showing its uncommitted changes");
			}
			return null;
		}
		String key = head + " " + repository.getBranches().getHash(base);
		MergeBase cached = mergeBases.get(root);
		if (cached != null && cached.key().equals(key)) {
			return cached.revision();
		}
		try {
			GitRevisionNumber revision = GitHistoryUtils.getMergeBase(project, root, base.getName(), head);
			if (revision == null) {
				return null;
			}
			mergeBases.put(root, new MergeBase(key, revision.asString()));
			return revision.asString();
		}
		catch (VcsException e) {
			LOG.warn("Cannot find the merge base of " + base.getName() + " in " + root.getPath(), e);
			return null;
		}
	}

	private static GitBranch baseBranch(GitRepository repository) {
		GitBranchesCollection branches = repository.getBranches();
		for (String name : BASES) {
			GitBranch branch = name.contains("/") ? branches.findRemoteBranch(name) : branches.findLocalBranch(name);
			if (branch != null) {
				return branch;
			}
		}
		return null;
	}

	/** {@code vs origin/main}, or null when no repository has a base branch; reads in-memory repository state only. */
	String title() {
		String bases = GitRepositoryManager.getInstance(project).getRepositories().stream()
				.map(BranchChangeSource::baseBranch).filter(Objects::nonNull).map(GitBranch::getName)
				.distinct().collect(Collectors.joining(", "));
		return bases.isEmpty() ? null : "vs " + bases;
	}

	@Override
	public List<FilePath> unversioned() {
		return local.unversioned();
	}

	@Override
	public boolean hasUnversioned() {
		return true;
	}

	/** Also on repository changes: a commit, checkout or fetch moves HEAD or the base without touching the working tree. */
	@Override
	public void subscribe(Runnable onChange, Disposable parent) {
		local.subscribe(onChange, parent);
		project.getMessageBus().connect(parent).subscribe(GitRepository.GIT_REPO_CHANGE,
				(GitRepositoryChangeListener) repository -> onChange.run());
	}

	/** A commit, checkout or fetch; not edits, which only update what is shown. */
	@Override
	public void subscribeReplacing(Runnable onReplace, Disposable parent) {
		project.getMessageBus().connect(parent).subscribe(GitRepository.GIT_REPO_CHANGE,
				(GitRepositoryChangeListener) repository -> onReplace.run());
	}

	@Override
	public boolean highlightChanges() {
		return local.highlightChanges();
	}
}
