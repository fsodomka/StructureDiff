package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.StructureDiffSettings;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.DocumentListener;
import com.intellij.openapi.fileEditor.FileDocumentManagerListener;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsRoot;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The review findings currently shown: the newest SARIF file in the reviews directory (see {@link StructureDiffSettings})
 * of each repository root, or one opened from elsewhere, without the dismissed findings. A newer review file replaces
 * the previous one of its root. EDT only, except parsing.
 */
@Service(Service.Level.PROJECT)
@State(name = "StructureDiffReviewFindings", storages = @Storage(StoragePathMacros.WORKSPACE_FILE))
public final class ReviewFindingsService implements PersistentStateComponent<ReviewFindingsService.State>, Disposable {
	/** The skill writes {@code .sarif}; {@code .sarif.json} is the Qodana convention. */
	private static final List<String> SARIF_SUFFIXES = List.of(".sarif", ".sarif.json");
	private static final Logger LOG = Logger.getInstance(ReviewFindingsService.class);

	public static final class State {
		/**
		 * Fingerprints of dismissed findings, which start with their repository root; they stay dismissed in later reviews.
		 * Not named {@code dismissed}: that held fingerprints without the root, which are dropped.
		 */
		public Set<String> dismissedFindings = new LinkedHashSet<>();
		/** Paths of review files removed with Clear All; not loaded again on startup. */
		public Set<String> cleared = new LinkedHashSet<>();
	}

	/**
	 * A loaded review file; review file names start with a sortable timestamp. {@code external} when it was opened from
	 * outside the reviews directory: it is not watched.
	 */
	record Review(VirtualFile root, VirtualFile file, List<Finding> findings, boolean external) {
	}

	private final Project project;
	private final ReviewHighlighter highlighter;
	private final Map<VirtualFile, Review> reviews = new LinkedHashMap<>();
	private State state = new State();
	/** See {@link FindingMembers}; by file path, accessed from pooled threads. */
	private final Map<String, FindingMembers.CachedKeyPaths> keyPathCache = new ConcurrentHashMap<>();

	public ReviewFindingsService(Project project) {
		this.project = project;
		highlighter = new ReviewHighlighter(project);
		// a reload from disk replaces the whole text, which collapses the highlights
		ApplicationManager.getApplication().getMessageBus().connect(this).subscribe(FileDocumentManagerListener.TOPIC,
				new FileDocumentManagerListener() {
					@Override
					public void fileContentReloaded(@NotNull VirtualFile file, @NotNull Document document) {
						if (highlighter.replaceIn(file)) {
							changed();
						}
					}
				});
		// deleting the reviewed code collapses its highlight: the finding is possibly fixed, until the code is back
		EditorFactory.getInstance().getEventMulticaster().addDocumentListener(new DocumentListener() {
			@Override
			public void documentChanged(@NotNull DocumentEvent event) {
				if (highlighter.update(event)) {
					changed();
				}
			}
		}, this);
	}

	public static ReviewFindingsService getInstance(Project project) {
		return project.getService(ReviewFindingsService.class);
	}

	Collection<Review> reviews() {
		return reviews.values();
	}

	Map<String, FindingMembers.CachedKeyPaths> keyPathCache() {
		return keyPathCache;
	}

	Collection<Placement> placements() {
		return highlighter.placements();
	}

	/** The file name without its SARIF suffix, or null if {@code fileName} is not a SARIF file. */
	static @Nullable String reviewName(String fileName) {
		for (String suffix : SARIF_SUFFIXES) {
			if (fileName.endsWith(suffix) && fileName.length() > suffix.length()) {
				return fileName.substring(0, fileName.length() - suffix.length());
			}
		}
		return null;
	}

	/** Whether {@code file} is a review file directly in the reviews directory of a repository root. */
	boolean isReviewFile(VirtualFile file) {
		return watchedRoot(file) != null;
	}

	/** The repository root whose reviews directory directly contains the review file {@code file}, or null. */
	private @Nullable VirtualFile watchedRoot(VirtualFile file) {
		VirtualFile dir = file.getParent();
		if (reviewName(file.getName()) == null || dir == null) {
			return null;
		}
		for (VirtualFile root : roots()) {
			if (dir.equals(reviewsDir(root))) {
				return root;
			}
		}
		return null;
	}

	/** The innermost repository root containing {@code file}, else the project directory; findings resolve against it. */
	private @Nullable VirtualFile containingRoot(VirtualFile file) {
		VirtualFile best = null;
		for (VirtualFile root : roots()) {
			if (VfsUtilCore.isAncestor(root, file, false) && (best == null || VfsUtilCore.isAncestor(best, root, true))) {
				best = root;
			}
		}
		return best != null ? best : ProjectUtil.guessProjectDir(project);
	}

	private static @Nullable VirtualFile reviewsDir(VirtualFile root) {
		VirtualFile dir = root.findFileByRelativePath(StructureDiffSettings.getInstance().reviewsDir());
		return dir != null && dir.isDirectory() ? dir : null;
	}

	/** Shows the newest reviews of the changed reviews directory instead of the shown ones. */
	public void reviewsDirChanged() {
		reviews.clear();
		highlighter.removeAll();
		changed();
		// keeps the cleared reviews cleared
		loadNewest();
	}

	/** Loads the newest review of each root, unless it was cleared. */
	void loadNewest() {
		roots().forEach(this::loadNewest);
	}

	private void loadNewest(VirtualFile root) {
		VirtualFile newest = newestReview(root);
		if (newest != null && !state.cleared.contains(newest.getPath())) {
			load(newest, false, false);
		}
	}

	/** Loads the newest review of each root again, also cleared ones. */
	void reload() {
		state.cleared.clear();
		loadNewest();
	}

	/**
	 * Parses {@code file} in the background and shows it instead of the shown review of its root; unless that one is
	 * newer and {@code replaceNewer} is false. A later load of the same file cancels this one if it has not been applied
	 * yet, so an older content cannot be applied last when one write brings several events.
	 */
	void load(VirtualFile file, boolean replaceNewer, boolean showToolWindow) {
		VirtualFile root = watchedRoot(file);
		if (root != null) {
			load(root, file, replaceNewer, showToolWindow, false);
		}
	}

	/**
	 * Shows the review file {@code file}, which need not be in a reviews directory, instead of the shown review of the
	 * repository root containing it; its paths resolve against that root, or against the project directory when no root
	 * contains it. Returns false only when there is neither.
	 */
	boolean loadExternal(VirtualFile file) {
		VirtualFile root = watchedRoot(file);
		boolean external = root == null;
		if (external) {
			root = containingRoot(file);
		}
		if (root == null) {
			return false;
		}
		load(root, file, true, false, external);
		return true;
	}

	private void load(VirtualFile root, VirtualFile file, boolean replaceNewer, boolean showToolWindow, boolean external) {
		ReadAction.nonBlocking(() -> parse(root, file))
				.finishOnUiThread(ModalityState.defaultModalityState(),
						findings -> apply(root, file, findings, replaceNewer, showToolWindow, external))
				.coalesceBy(this, file)
				.expireWith(this)
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	void dismiss(Finding finding) {
		state.dismissedFindings.add(finding.fingerprint());
		// also the other findings with the fingerprint: they are dismissed as well
		for (Placement placement : List.copyOf(highlighter.placements())) {
			if (placement.finding().fingerprint().equals(finding.fingerprint())) {
				highlighter.remove(placement.finding());
			}
		}
		changed();
	}

	/** Shows a dismissed finding again, also in later reviews. */
	void restore(Finding finding) {
		state.dismissedFindings.remove(finding.fingerprint());
		for (Review review : reviews.values()) {
			highlighter.place(review.root(),
					review.findings().stream().filter(other -> other.fingerprint().equals(finding.fingerprint())).toList());
		}
		changed();
	}

	boolean isDismissed(Finding finding) {
		return state.dismissedFindings.contains(finding.fingerprint());
	}

	/** The dismissed findings of the shown reviews; they are not highlighted. */
	List<Placement> dismissedPlacements() {
		List<Placement> dismissed = new ArrayList<>();
		for (Review review : reviews.values()) {
			for (Finding finding : review.findings()) {
				if (isDismissed(finding)) {
					VirtualFile file = ReviewHighlighter.resolve(review.root(), finding.path());
					dismissed.add(new Placement(finding, review.root(), file,
							file == null ? ReviewAnchor.Status.MISSING_FILE : ReviewAnchor.Status.EXACT, null));
				}
			}
		}
		return dismissed;
	}

	/**
	 * Drops the shown reviews whose file was deleted, or moved or renamed so it is no longer a review file of its root
	 * (external ones only when deleted), and shows the newest remaining review of that root instead, unless it was cleared.
	 * Also marks the findings of deleted reviewed files missing.
	 */
	void dropRemoved() {
		if (highlighter.dropDeleted()) {
			changed();
		}
		for (Review review : List.copyOf(reviews.values())) {
			VirtualFile file = review.file();
			if (!file.isValid() || !review.external() && !review.root().equals(watchedRoot(file))) {
				reviews.remove(review.root());
				highlighter.removeRoot(review.root());
				changed();
				if (review.root().isValid()) {
					loadNewest(review.root());
				}
			}
		}
	}

	/** Removes all findings until a new review file appears or Reload. */
	void clearAll() {
		for (Review review : reviews.values()) {
			state.cleared.add(review.file().getPath());
		}
		reviews.clear();
		highlighter.removeAll();
		changed();
	}

	private void apply(VirtualFile root, VirtualFile file, @Nullable List<Finding> findings, boolean replaceNewer,
					   boolean showToolWindow, boolean external) {
		Review current = reviews.get(root);
		// an external review's name is not a timestamp: any review of the reviews directory replaces it
		if (findings == null) {
			// a review the user picked; the watcher's loads stay silent, a file may be half written
			if (replaceNewer) {
				NotificationGroupManager.getInstance().getNotificationGroup("Structure Diff")
						.createNotification(StringUtil.escapeXmlEntities("Cannot read " + file.getPresentableUrl() + " as SARIF"),
								NotificationType.WARNING)
						.notify(project);
			}
			return;
		}
		if (!replaceNewer && current != null && !current.external()
				&& current.file().getName().compareTo(file.getName()) > 0) {
			return;
		}
		highlighter.removeRoot(root);
		reviews.put(root, new Review(root, file, findings, external));
		state.cleared.remove(file.getPath());
		highlighter.place(root, findings.stream().filter(finding -> !isDismissed(finding)).toList());
		changed();
		if (showToolWindow) {
			ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(ReviewFindingsToolWindowFactory.ID);
			if (toolWindow != null) {
				toolWindow.show();
			}
		}
	}

	private static @Nullable List<Finding> parse(VirtualFile root, VirtualFile file) {
		try {
			return SarifReader.read(VfsUtilCore.loadText(file), root.getPath());
		} catch (Exception e) {
			// also an incomplete file while the skill is still writing it; its next change event loads it again
			LOG.warn("Cannot read review file " + file.getPath(), e);
			return null;
		}
	}

	/** Repository roots with their review files, newest first; roots without reviews are left out. */
	Map<VirtualFile, List<VirtualFile>> reviewFiles() {
		Map<VirtualFile, List<VirtualFile>> files = new LinkedHashMap<>();
		for (VirtualFile root : roots()) {
			List<VirtualFile> reviews = reviewFiles(root);
			if (!reviews.isEmpty()) {
				files.put(root, reviews);
			}
		}
		return files;
	}

	/** Whether {@code review} is the newest review of its root. */
	boolean isLatest(Review review) {
		return review.file().equals(newestReview(review.root()));
	}

	private List<VirtualFile> roots() {
		Set<VirtualFile> roots = new LinkedHashSet<>();
		for (VcsRoot root : ProjectLevelVcsManager.getInstance(project).getAllVcsRoots()) {
			roots.add(root.getPath());
		}
		VirtualFile projectDir = ProjectUtil.guessProjectDir(project);
		if (projectDir != null) {
			roots.add(projectDir);
		}
		return List.copyOf(roots);
	}

	private static @Nullable VirtualFile newestReview(VirtualFile root) {
		List<VirtualFile> files = reviewFiles(root);
		return files.isEmpty() ? null : files.getFirst();
	}

	/** Review files of {@code root}, newest first: their names start with a sortable timestamp. */
	private static List<VirtualFile> reviewFiles(VirtualFile root) {
		VirtualFile dir = reviewsDir(root);
		if (dir == null) {
			return List.of();
		}
		return Arrays.stream(dir.getChildren())
				.filter(child -> !child.isDirectory() && reviewName(child.getName()) != null)
				.sorted(Comparator.comparing(VirtualFile::getName).reversed())
				.toList();
	}

	private void changed() {
		project.getMessageBus().syncPublisher(ReviewFindingsListener.TOPIC).findingsChanged();
	}

	@Override
	public State getState() {
		return state;
	}

	@Override
	public void loadState(@NotNull State state) {
		this.state = state;
	}

	@Override
	public void dispose() {
		highlighter.removeAll();
	}
}
