package com.fsodomka.structurediff;

import com.fsodomka.structurediff.ChangeFlashes.Flash;
import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.DiffTreeNodes.Ghost;
import com.fsodomka.structurediff.DiffTreeNodes.RepoNode;
import com.fsodomka.structurediff.ViewOptions.Option;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.fsodomka.structurediff.diff.RevisionContent;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.fsodomka.structurediff.review.ReviewFindingsListener;
import com.fsodomka.structurediff.review.ReviewFindingsToolWindowFactory;
import com.fsodomka.structurediff.review.ReviewSeverity;
import com.fsodomka.structurediff.settings.StructureDiffSettings;
import com.fsodomka.structurediff.source.ChangeSource;
import com.fsodomka.structurediff.source.CommitChangeSource;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.*;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.StatusText;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tree of changed files and their added/modified/removed members. Refreshes when its {@link ChangeSource} reports changes.
 */
final class StructureDiffPanel extends SimpleToolWindowPanel implements Disposable {
	private final Project project;
	private final ChangeSource source;
	private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode())) {
		/** Tree shows its busy icon only while focused; a commit is selected with the focus in the Git log. */
		@Override
		protected boolean shouldShowBusyIconIfNeeded() {
			return true;
		}
	};
	private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
	private final ViewOptions options = new ViewOptions();
	private final ChangeFlashes flashes = new ChangeFlashes(this::repaintViews, () -> show(this.files, false),
			() -> options.get(Option.TREEMAP));
	/**
	 * Background of the changed members that use or are used by the selected one: soft amber, like the editor's search
	 * result highlight, so it is not taken for the blue selection and sits well under the green, blue and grey status text.
	 */
	static final JBColor RELATED = new JBColor(0xFFEFC2, 0x6B5C1F);
	private final AtomicInteger generation = new AtomicInteger();
	private final SimpleColoredComponent summary = new SimpleColoredComponent();
	/** Finding counts per severity, after {@link #summary}; one icon each, which a {@link SimpleColoredComponent} cannot show. */
	private final JPanel findingCounts = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
	private volatile List<FileDiff> files = List.of();
	/** VCS root of each file path; files are grouped by root when there is more than one. */
	private Map<String, VirtualFile> vcsRoots = Map.of();
	/** The VCS root holding the project's directory, from the last refresh; see {@link Snapshot#projectRoot()}. */
	private @Nullable VirtualFile projectRoot;
	/** Contents of committed revisions, which never change; loading them is VCS I/O. */
	private final Map<String, String> revisionCache = new ConcurrentHashMap<>();
	private final JScrollPane treeScroll = ScrollPaneFactory.createScrollPane(tree);
	private final SelectionActions selection;
	private final MemberSignalState signals;
	private final UsagesPanel usagesPanel;
	private final UsagesController usages;
	/** The tree or treemap above the {@link #usagesPanel}; its proportion is saved under its key. */
	private final OnePixelSplitter usagesSplitter = new OnePixelSplitter(true, "structureDiff.usagesProportion", 0.7f);
	/** Set while {@link #show} replaces the tree, whose selection events are not the user's. */
	private boolean rebuilding;
	private final TreemapPanel treemap;
	/** A refresh is in progress, from the start until the first one finishes; shown by the treemap, the tree paints busy. */
	private boolean loading = true;
	/** Paths of the unversioned files in {@link #files}; they are loaded even when hidden, so the toggle needs no refresh. */
	private volatile Set<String> unversionedPaths = Set.of();
	/** Paths of the files in test sources, marked as such in the tree; looked up off the EDT, as the index may be slow. */
	private volatile Set<String> testPaths = Set.of();
	/** Package of each file in a source root, relative to the package all changed code shares; shown instead of its directory. */
	private Map<String, String> packages = Map.of();
	/** Whether the panel is showing; a hidden panel (other mode, closed tool window) does not refresh. */
	private volatile boolean showing;
	/** A refresh or findings update was skipped while hidden; done when the panel shows again. */
	private volatile boolean stale;

	/** @param extraActions added to the toolbar before Expand All and Collapse All */
	StructureDiffPanel(Project project, ChangeSource source, AnAction... extraActions) {
		super(true, true);
		this.project = project;
		this.source = source;
		selection = new SelectionActions(project, source, tree, this);
		signals = new MemberSignalState(project, source, () -> shownFiles(files), this::repaintViews, () -> {
			repaintViews();
			updateFindingCounts();
		}, this);
		usagesPanel = new UsagesPanel(project, selection::jumpToSource);
		Disposer.register(this, usagesPanel);
		usages = new UsagesController(project, usagesPanel, tree, this::repaintViews, this);
		treemap = new TreemapPanel(tree, DiffLabels::speedSearchText,
				node -> DiffTreeNodes.keyPart(node.getUserObject()), selection::jumpToSource, selection::openSelected,
				signals::findingsOf, signals::showFindings, signals::diagnosticsOf, signals::notAnalysed, signals::openDiagnostic,
				this::isTestFile, flashes::get, usages::isRelated, () -> this.loading, () -> tree.getEmptyText().getText());

		installTree();
		updateContent();
		selection.installPopupMenu(treemap);
		setToolbar(createToolbar(extraActions));
		subscribe();
		scheduleRefresh();
	}

	private void installTree() {
		tree.setRootVisible(false);
		// "Nothing to show" only once the first refresh finished
		tree.getEmptyText().clear().appendLine(AnimatedIcon.Default.INSTANCE, "Loading…",
				SimpleTextAttributes.REGULAR_ATTRIBUTES, null);
		tree.setCellRenderer(new Renderer());
		ToolTipManager.sharedInstance().registerComponent(tree);
		// matches the shown label, not the user object's toString (a FileDiff's holds the whole source text);
		// searches collapsed nodes too, expanding to the match
		TreeSpeedSearch.installOn(tree, true, path -> DiffLabels.speedSearchText((DefaultMutableTreeNode) path.getLastPathComponent()));
		// double-click and Enter edit the file like in the Project view; middle click shows the diff
		Trees.installOpenHandler(tree, selection::jumpToSource);
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				if (SwingUtilities.isMiddleMouseButton(e) && path != null) {
					tree.setSelectionPath(path);
					selection.openSelected();
					e.consume();
				}
				else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1 && path != null) {
					Signal signal = signalAt(path, e.getX());
					if (signal == Signal.REVIEW) {
						signals.showFindings(signals.findingsOf((DefaultMutableTreeNode) path.getLastPathComponent()));
						e.consume();
					}
					else if (signal == Signal.IDE) {
						signals.openDiagnostic((DefaultMutableTreeNode) path.getLastPathComponent());
						e.consume();
					}
				}
			}

			@Override
			public void mouseMoved(MouseEvent e) {
				// a hand over a severity icon, which opens its findings or the diagnostic
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				boolean onIcon = path != null && signalAt(path, e.getX()) != null;
				tree.setCursor(onIcon ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : null);
			}
		};
		tree.addMouseListener(mouse);
		tree.addMouseMotionListener(mouse);
		tree.addTreeSelectionListener(e -> {
			if (!rebuilding) {
				updateUsages();
			}
		});
	}

	/** The toolbar with the summary row below it. */
	private JComponent createToolbar(AnAction... extraActions) {
		// the treemap shows all levels, so there is nothing to expand or collapse
		DefaultTreeExpander expander = new DefaultTreeExpander(tree) {
			@Override
			public boolean canExpand() {
				return !options.get(Option.TREEMAP) && super.canExpand();
			}

			@Override
			public boolean canCollapse() {
				return !options.get(Option.TREEMAP) && super.canCollapse();
			}
		};
		DefaultActionGroup group = new DefaultActionGroup();
		group.add(new DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				scheduleRefresh();
			}
		});
		group.add(new DumbAwareAction("Export Diff for Review",
				"Save the shown changes to the reviews directory and copy the review skill command",
				AllIcons.ToolbarDecorator.Export) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				exportDiff();
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				boolean enabled = !exportRoots().isEmpty();
				e.getPresentation().setEnabled(enabled);
				e.getPresentation().setDescription(enabled || shownFiles(files).isEmpty()
						? "Save the shown changes to the reviews directory and copy the review skill command"
						: "No repository root to export to");
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		});
		group.add(options.toggle(Option.NESTED, "Show Nested Changes",
				"Show changes inside functions, components and object literals, not only inside classes", AllIcons.Actions.ShowAsTree,
				() -> show(files, false)));
		group.add(options.toggle(Option.UNVERSIONED, "Show Unversioned Files", null, AllIcons.Vcs.ShowUnversionedFiles,
				source::hasUnversioned, "Commits have no unversioned files", () -> show(files, false)));
		group.add(options.toggle(Option.FLASH, "Flash Changes",
				"Briefly highlight members and files that appeared, changed or disappeared", AllIcons.Actions.Highlighting,
				source::hasUnversioned, "Commits have no member changes", () -> {
					if (!options.get(Option.FLASH)) {
						// drops the running flashes and their ghosts
						flashes.clear();
						show(files, false);
					}
				}));
		group.add(options.toggle(Option.TREEMAP, "Show as Treemap",
				"Show the changes as nested rectangles sized by their changed lines", AllIcons.Actions.GroupByModule,
				this::updateContent));
		group.add(options.toggle(Option.USAGES, "Show Usages",
				"Show what the selected member uses and what uses it, and highlight the related changed members",
				AllIcons.Hierarchy.Supertypes, () -> !(source instanceof CommitChangeSource),
				"Usages need the working-tree version of the files, not a commit's", this::updateContent));
		group.addAll(extraActions);
		group.add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree));
		group.add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree));
		ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("StructureDiff", group, true);
		// the panel, not the tree, which is detached while the treemap shows
		toolbar.setTargetComponent(this);
		summary.setBorder(JBUI.Borders.empty(2, 8, 4, 8));
		findingCounts.setBorder(JBUI.Borders.empty(2, 0, 4, 8));
		findingCounts.setOpaque(false);
		findingCounts.setVisible(false);
		findingCounts.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		findingCounts.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				ReviewFindingsToolWindowFactory.show(project, List.of());
			}
		});
		JPanel summaryRow = new JPanel(new BorderLayout());
		summaryRow.setOpaque(false);
		summaryRow.add(summary, BorderLayout.WEST);
		summaryRow.add(findingCounts, BorderLayout.CENTER);
		JPanel top = new JPanel(new BorderLayout());
		top.add(toolbar.getComponent(), BorderLayout.NORTH);
		top.add(summaryRow, BorderLayout.SOUTH);
		return top;
	}

	/** Refreshes on changes of the source, the findings, the diagnostics and the panel's visibility. */
	private void subscribe() {
		// sources report changes from any thread; the refresh that finishes last clears it
		source.subscribeReplacing(() -> UIUtil.invokeLaterIfNeeded(() -> {
			tree.setPaintBusy(true);
			loading = true;
			treemap.repaint();
		}), this);
		source.subscribe(this::scheduleRefresh, this);
		project.getMessageBus().connect(this).<ReviewFindingsListener>subscribe(ReviewFindingsListener.TOPIC, () -> {
			if (showing) {
				signals.updateFindings();
			}
			else {
				stale = true;
			}
		});
		ApplicationManager.getApplication().getMessageBus().connect(this)
				.<StructureDiffSettings.DiagnosticLevelsListener>subscribe(StructureDiffSettings.DiagnosticLevelsListener.TOPIC, this::diagnosticsChanged);
		project.getMessageBus().connect(this).subscribe(DaemonCodeAnalyzer.DAEMON_EVENT_TOPIC, new DaemonCodeAnalyzer.DaemonListener() {
			@Override
			public void daemonFinished(@NotNull Collection<? extends FileEditor> fileEditors) {
				if (fileEditors.stream().anyMatch(editor -> editor.getFile() != null && isShownFile(editor.getFile()))) {
					diagnosticsChanged();
				}
			}
		});
		project.getMessageBus().connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, new FileEditorManagerListener() {
			@Override
			public void fileOpened(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
				if (isShownFile(file)) {
					diagnosticsChanged();
				}
			}

			@Override
			public void fileClosed(@NotNull FileEditorManager source, @NotNull VirtualFile file) {
				if (isShownFile(file)) {
					diagnosticsChanged();
				}
			}
		});
		addHierarchyListener(e -> {
			if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
				showing = isShowing();
				if (showing) {
					loadSettings();
				}
				if (showing && stale) {
					stale = false;
					// also updates the findings
					scheduleRefresh();
				}
			}
		});
	}

	private void repaintViews() {
		tree.repaint();
		treemap.repaint();
	}

	/**
	 * Picks up the toggles saved by another panel: the Uncommitted panel outlives mode switches, and the settings are
	 * shared by all projects. Package-private for {@code ViewOptionsTest}.
	 */
	void loadSettings() {
		Set<Option> changed = options.reload();
		if (changed.contains(Option.TREEMAP) || changed.contains(Option.USAGES)) {
			// later, not in the showing event: that comes from this panel's addNotify before its children's, so a child
			// swapped in now is added twice, and a splitter's divider leaks the listener of the first
			SwingUtilities.invokeLater(this::updateContent);
		}
		if (changed.contains(Option.FLASH) && !options.get(Option.FLASH)) {
			flashes.clear();
		}
		if (changed.contains(Option.NESTED) || changed.contains(Option.UNVERSIONED) || changed.contains(Option.FLASH)) {
			show(files, false);
		}
	}

	/** Called on any thread; while hidden, only marks the panel stale. */
	private void scheduleRefresh() {
		if (!showing) {
			stale = true;
			// shown meanwhile: the hierarchy listener may have read stale before it was set
			if (!showing) {
				return;
			}
		}
		alarm.cancelAllRequests();
		alarm.addRequest(this::refresh, 300);
	}

	/** Runs on a pooled thread. */
	private void refresh() {
		int current = generation.incrementAndGet();
		try {
			refresh(current);
		}
		catch (RuntimeException | Error e) {
			refreshFailed(current);
			throw e;
		}
	}

	/** Clears the busy state that only a successful latest refresh clears otherwise; the error itself is logged by the caller. */
	private void refreshFailed(int current) {
		UIUtil.invokeLaterIfNeeded(() -> {
			if (current == generation.get()) {
				tree.setPaintBusy(false);
				loading = false;
				tree.getEmptyText().setText("Could not load changes");
				treemap.repaint();
			}
		});
	}

	private void refresh(int current) {
		boolean highlight = source.highlightChanges();
		List<FilePath> unversioned = source.unversioned();
		Collection<Change> changes = source.changes();
		Map<String, FileDiff> previous = files.stream().collect(Collectors.toMap(FileDiff::path, f -> f, (a, b) -> a));
		// loading is VCS I/O, one process per revision; stop when a newer refresh started, e.g. another commit was selected
		List<FileDiff> loaded = new ArrayList<>(changes.size() + unversioned.size());
		for (Change change : changes) {
			if (current != generation.get()) {
				return;
			}
			loaded.add(RevisionContent.load(change, revisionCache, previous.get(ChangesUtil.getFilePath(change).getPath())));
		}
		for (FilePath path : unversioned) {
			if (current != generation.get()) {
				return;
			}
			loaded.add(RevisionContent.loadUnversioned(path, previous.get(path.getPath())));
		}
		loaded.sort(Comparator.comparing(FileDiff::path, String.CASE_INSENSITIVE_ORDER));
		Set<String> usedRevisions = changes.stream().flatMap(change -> Stream.of(change.getBeforeRevision(), change.getAfterRevision()))
				.map(RevisionContent::revisionKey).filter(Objects::nonNull).collect(Collectors.toSet());
		revisionCache.keySet().retainAll(usedRevisions);
		// the read action restarts after every write action (each keystroke); keep the files already parsed
		AtomicReferenceArray<FileDiff> computed = new AtomicReferenceArray<>(loaded.size());
		ReadAction.nonBlocking(() -> Snapshot.compute(project, loaded, previous, unversioned, computed))
				.inSmartMode(project)
				.expireWith(this)
				// cancels the computation of an older refresh, whose result would be discarded anyway
				.coalesceBy(this)
				.finishOnUiThread(ModalityState.any(), snapshot -> {
					if (current == generation.get()) {
						tree.setPaintBusy(false);
						loading = false;
						tree.getEmptyText().setText(StatusText.getDefaultEmptyText());
						files = snapshot.files();
						vcsRoots = snapshot.roots();
						projectRoot = snapshot.projectRoot();
						unversionedPaths = snapshot.unversioned();
						testPaths = snapshot.tests();
						packages = snapshot.packages();
						show(snapshot.files(), highlight);
						// the tree may show other members now, and edits may have moved the findings into other ones
						signals.updateFindings();
						signals.updateDiagnostics();
					}
				})
				.submit(AppExecutorUtil.getAppExecutorService())
				// a cancellation by coalesceBy comes with a newer generation, so only an error of the latest refresh clears it
				.onError(e -> refreshFailed(current));
	}

	/** Shows {@code loadedFiles} as a refresh that loaded them does, without a change source or VCS. */
	@TestOnly
	void showLoaded(List<FileDiff> loadedFiles) {
		files = loadedFiles;
		show(loadedFiles, false);
	}

	@TestOnly
	JTree tree() {
		return tree;
	}

	@TestOnly
	UsagesPanel usagesPanel() {
		return usagesPanel;
	}

	/** @param highlightChanges flashes nodes that appeared, changed or disappeared since the previous tree */
	private void show(List<FileDiff> loadedFiles, boolean highlightChanges) {
		List<FileDiff> allFiles = shownFiles(loadedFiles);
		DefaultMutableTreeNode oldRoot = (DefaultMutableTreeNode) tree.getModel().getRoot();
		boolean firstShow = oldRoot.getChildCount() == 0;
		Set<List<String>> collapsed = Trees.collapsed(tree, oldRoot, DiffTreeNodes::keyPart, DiffTreeNodes::isFile);
		Map<List<String>, DefaultMutableTreeNode> oldNodes = new LinkedHashMap<>();
		DiffTreeNodes.collectNodes(oldRoot, new ArrayList<>(), oldNodes);
		DefaultMutableTreeNode lead = selection.selectedNode();
		// after a refresh the whole selection comes back, the lead last, so Show Diff and Export still use the lead
		List<List<String>> selectedKeys = new ArrayList<>(selection.selectedNodes().stream().map(DiffTreeNodes::keyOf).toList());
		if (lead != null) {
			List<String> leadKey = DiffTreeNodes.keyOf(lead);
			selectedKeys.remove(leadKey);
			selectedKeys.add(leadKey);
		}

		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		boolean groupByRoot = DiffTreeNodes.groupByRoot(allFiles.stream().map(f -> vcsRoots.get(f.path())).filter(Objects::nonNull).toList(),
				projectRoot);
		Map<VirtualFile, DefaultMutableTreeNode> repoNodes = new LinkedHashMap<>();
		int[] total = new int[3];
		int linesAdded = 0;
		int linesRemoved = 0;
		for (FileDiff fullFile : allFiles) {
			FileDiff file = options.get(Option.NESTED) || fullFile.members() == null ? fullFile
					: fullFile.withMembers(fullFile.members().stream().map(DiffTreeNodes::prune).toList());
			linesAdded += file.linesAdded();
			linesRemoved += file.linesRemoved();
			if (file.members() != null) {
				DiffTreeNodes.count(file.members(), total);
			}
			DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(file);
			if (file.members() != null) {
				for (MemberDiff member : file.members()) {
					fileNode.add(DiffTreeNodes.memberNode(member));
				}
			}
			VirtualFile vcsRoot = groupByRoot ? vcsRoots.get(file.path()) : null;
			DefaultMutableTreeNode parent = vcsRoot == null ? root : repoNodes.computeIfAbsent(vcsRoot, r -> {
				DefaultMutableTreeNode repoNode = new DefaultMutableTreeNode(new RepoNode(r));
				root.add(repoNode);
				return repoNode;
			});
			parent.add(fileNode);
		}
		Map<List<String>, DefaultMutableTreeNode> newNodes = new HashMap<>();
		DiffTreeNodes.collectNodes(root, new ArrayList<>(), newNodes);
		Set<List<String>> started = flashes.update(oldNodes, newNodes, highlightChanges, options.get(Option.FLASH), root, repoNodes, vcsRoots);
		rebuilding = true;
		try {
			((DefaultTreeModel) tree.getModel()).setRoot(root);
			Trees.expandAll(tree);
			if (!firstShow) {
				Trees.restoreCollapsed(tree, root, collapsed, DiffTreeNodes::keyPart, DiffTreeNodes::isFile);
			}
			if (!selectedKeys.isEmpty()) {
				List<TreePath> selectedPaths = selectedKeys.stream().map(key -> DiffTreeNodes.find(root, key)).filter(Objects::nonNull)
						.map(node -> new TreePath(node.getPath())).toList();
				if (!selectedPaths.isEmpty()) {
					tree.setSelectionPaths(selectedPaths.toArray(TreePath[]::new));
				}
			}
		}
		finally {
			// else an exception above would leave the selection listener ignoring the user for good
			rebuilding = false;
		}
		updateUsages();
		if (!started.isEmpty()) {
			ChangeFlashes.revealFlash(tree, started.stream().map(key -> DiffTreeNodes.find(root, key)).filter(Objects::nonNull)
					.map(node -> new TreePath(node.getPath())).toList());
		}

		summary.clear();
		// clear() keeps the tooltip; appendLines sets it only when there are changed lines
		summary.setToolTipText(null);
		summary.append(allFiles.size() + (allFiles.size() == 1 ? " file" : " files"), SimpleTextAttributes.REGULAR_ATTRIBUTES);
		DiffLabels.appendCounts(summary, total, SimpleTextAttributes.STYLE_BOLD);
		DiffLabels.appendLines(summary, linesAdded, linesRemoved, SimpleTextAttributes.GRAYED_ATTRIBUTES);
		updateFindingCounts();
	}

	/** {@code loadedFiles} without the unversioned ones while they are hidden. */
	private List<FileDiff> shownFiles(List<FileDiff> loadedFiles) {
		return options.get(Option.UNVERSIONED) ? loadedFiles : loadedFiles.stream().filter(f -> !unversionedPaths.contains(f.path())).toList();
	}

	/** Writes the shown changes of {@link #exportRoots()} for review; see {@link DiffExport#export}. */
	private void exportDiff() {
		DiffExport.export(project, exportRoots(), shownFiles(files), vcsRoots);
	}

	/** Roots to export: the roots of the selection, else the roots of all shown files; empty when none is in a repository. */
	private List<VirtualFile> exportRoots() {
		return DiffExport.exportRoots(shownFiles(files), vcsRoots, selection.selectedNodes());
	}

	private boolean isShownFile(VirtualFile file) {
		String path = file.getPath();
		return files.stream().anyMatch(f -> f.path().equals(path));
	}

	/** The daemon's results or the open editors changed; updated now, or when the panel shows again. */
	private void diagnosticsChanged() {
		if (showing) {
			signals.updateDiagnostics();
		}
		else {
			stale = true;
		}
	}

	/** Shows the {@link MemberSignals#findingCounts} of the shown files; findings of other files are mentioned in the tooltip only. */
	private void updateFindingCounts() {
		int[] counts = MemberSignals.findingCounts(shownFiles(files), signals.findings());
		findingCounts.removeAll();
		List<String> texts = new ArrayList<>();
		for (ReviewSeverity severity : ReviewSeverity.values()) {
			int count = counts[severity.ordinal()];
			if (count > 0) {
				findingCounts.add(new JBLabel(String.valueOf(count), severity.icon, SwingConstants.LEADING));
				texts.add(severity.countText(count));
			}
		}
		findingCounts.setToolTipText(texts.isEmpty() ? null : "Review findings: " + String.join(", ", texts));
		findingCounts.setVisible(!texts.isEmpty());
		findingCounts.revalidate();
		findingCounts.repaint();
	}

	/** The icons after a node's own: the most severe review finding's, then the most severe diagnostic's. */
	enum Signal {REVIEW, IDE}

	/** The signal icon of the node at {@code path} that {@code x} is on, or null. */
	private Signal signalAt(TreePath path, int x) {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
		Rectangle bounds = tree.getPathBounds(path);
		if (bounds == null || node.getUserObject() instanceof Ghost) {
			return null;
		}
		List<MemberFinding> found = signals.findingsOf(node);
		List<MemberDiagnostic> diags = signals.diagnosticsOf(node);
		if (found.isEmpty() && diags.isEmpty()) {
			return null;
		}
		Component component = tree.getCellRenderer().getTreeCellRendererComponent(tree, node, tree.isPathSelected(path),
				tree.isExpanded(path), node.isLeaf(), tree.getRowForPath(path), false);
		if (!(component instanceof Renderer renderer) || renderer.getIcon() == null) {
			return null;
		}
		// the renderer paints its icon at ipad.left, the signal icons last in it, the diagnostic's after the finding's
		int end = bounds.x + renderer.getIpad().left + renderer.getIcon().getIconWidth();
		if (!diags.isEmpty()) {
			int start = end - diags.getFirst().icon().getIconWidth();
			if (x >= start && x < end) {
				return Signal.IDE;
			}
			end = start;
		}
		return !found.isEmpty() && x >= end - found.getFirst().icon().getIconWidth() && x < end ? Signal.REVIEW : null;
	}

	/** Whether {@code node} is a changed file in test sources. */
	private boolean isTestFile(DefaultMutableTreeNode node) {
		return node.getUserObject() instanceof FileDiff file && testPaths.contains(file.path());
	}


	/** Searches the usages of the selected member while they are shown. */
	private void updateUsages() {
		if (usagesShown()) {
			usages.update(selection.selectedNode(), files);
		}
	}

	/** Whether the {@link #usagesPanel} is shown: Show Usages is on, and the files are the working copy, not a commit's. */
	private boolean usagesShown() {
		return options.get(Option.USAGES) && !(source instanceof CommitChangeSource);
	}

	/** Shows the tree or the treemap, with the {@link #usagesPanel} below while {@link #usagesShown}. */
	private void updateContent() {
		JComponent main = options.get(Option.TREEMAP) ? treemap : treeScroll;
		if (!usagesShown()) {
			usagesSplitter.setFirstComponent(null);
			setContent(main);
			usages.cancel();
			return;
		}
		usagesSplitter.setFirstComponent(main);
		usagesSplitter.setSecondComponent(usagesPanel);
		setContent(usagesSplitter);
		updateUsages();
	}

	@Override
	public void dispose() {
		flashes.stop();
	}

	private final class Renderer extends ColoredTreeCellRenderer {
		@Override
		public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded,
										  boolean leaf, int row, boolean hasFocus) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
			Object item = node.getUserObject();
			boolean ghost = item instanceof Ghost;
			if (item instanceof Ghost(Object ghostItem)) {
				item = ghostItem;
			}
			Flash flash = flashes.get(node);
			// a removed node that vanished came back (removal undone): it flashes as added, not as gone
			boolean restored = ghost && flash != null && flash.status() == FileStatus.DELETED;
			Color background = flash == null ? null : flashBackground(flash, ghost, restored);
			if (background == null && !selected && usages.isRelated(node)) {
				background = RELATED;
			}
			if (item instanceof RepoNode(VirtualFile root)) {
				setIcon(AllIcons.Nodes.Module);
				append(root.getName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
				append("  " + root.getPresentableUrl(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				setToolTipText(null);
			}
			else if (item instanceof FileDiff file) {
				VirtualFile vcsRoot = vcsRoots.get(file.path());
				String basePath = vcsRoot != null ? vcsRoot.getPath() : StringUtil.notNullize(project.getBasePath());
				String path = DiffLabels.relativePath(file.path(), basePath);
				int slash = path.lastIndexOf('/');
				List<MemberFinding> found = ghost ? List.of() : signals.findingsOf(node);
				List<MemberDiagnostic> diags = ghost ? List.of() : signals.diagnosticsOf(node);
				setIcon(MemberSignals.withSignalIcons(withTestMark(file.fileType().getIcon(), file.path()), found, diags));
				append(path.substring(slash + 1), attributes(restored ? FileStatus.NOT_CHANGED : file.status(), ghost && !restored, background));
				if (file.members() != null && !ghost) {
					int[] counts = new int[3];
					DiffTreeNodes.count(file.members(), counts);
					DiffLabels.appendCounts(this, counts, SimpleTextAttributes.STYLE_SMALLER);
				}
				if (!ghost) {
					DiffLabels.appendLines(this, file.linesAdded(), file.linesRemoved(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
				}
				if (!found.isEmpty()) {
					append("  ⚠" + found.size(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
				}
				// the package, as the source root and the package all changes share say little; the tooltip has the full path
				String location = packages.getOrDefault(file.path(), slash > 0 ? path.substring(0, slash) : "");
				if (!location.isEmpty()) {
					append("  " + location, SimpleTextAttributes.GRAYED_ATTRIBUTES);
				}
				Change change = file.change();
				String beforePath = change.getBeforeRevision() == null ? null : change.getBeforeRevision().getFile().getPath();
				String moved = null;
				if ((change.isMoved() || change.isRenamed()) && beforePath != null) {
					// a rename shows the old name, a move the old path relative to the root
					String shown = change.isMoved() ? DiffLabels.relativePath(beforePath, basePath) : StringUtil.getShortName(beforePath, '/');
					append("  ← " + shown, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
					moved = (change.isMoved() ? "Moved from " : "Renamed from ") + FileUtil.toSystemDependentName(beforePath);
				}
				String lines = file.linesAdded() + file.linesRemoved() == 0 ? null : DiffLabels.linesText(file.linesAdded(), file.linesRemoved());
				StringBuilder tip = new StringBuilder("<html>").append(StringUtil.escapeXmlEntities(FileUtil.toSystemDependentName(path)));
				if (moved != null) {
					tip.append("<br>").append(StringUtil.escapeXmlEntities(moved));
				}
				if (lines != null) {
					tip.append("<br>").append(lines);
				}
				setToolTipText(MemberSignals.withSignals(tip.append("</html>").toString(), found, diags, !ghost && signals.notAnalysed(node)));
			}
			else if (item instanceof MemberDiff member) {
				List<MemberFinding> found = ghost ? List.of() : signals.findingsOf(node);
				List<MemberDiagnostic> diags = ghost ? List.of() : signals.diagnosticsOf(node);
				Icon icon = member.icon();
				if (((DefaultMutableTreeNode) node.getParent()).getUserObject() instanceof FileDiff parent) {
					icon = withTestMark(icon, parent.path());
				}
				setIcon(MemberSignals.withSignalIcons(icon, found, diags));
				String shortLabel = DiffLabels.shorten(member.label());
				append(shortLabel, attributes(restored ? FileStatus.NOT_CHANGED : member.status(), ghost && !restored, background));
				setToolTipText(MemberSignals.withSignals(DiffLabels.tooltip(member), found, diags, false));
			}
		}

		/** {@code icon} with the test marker when {@code path} is in test sources, as the Project view shows test classes. */
		private Icon withTestMark(Icon icon, String path) {
			if (icon == null || !testPaths.contains(path)) {
				return icon;
			}
			return IconManager.getInstance().createLayered(icon, AllIcons.Nodes.JunitTestMark);
		}


		/** The status color (removed for a ghost, added for a restored node) blended into the tree background, fading with the flash. */
		private Color flashBackground(Flash flash, boolean ghost, boolean restored) {
			double strength = flash.strength(System.currentTimeMillis());
			if (strength <= 0) {
				return null;
			}
			Color color = (restored ? FileStatus.ADDED : ghost ? FileStatus.DELETED : flash.status()).getColor();
			return color == null ? null : ColorUtil.mix(tree.getBackground(), color, 0.4 * strength);
		}

		/** A ghost is grey and struck out: the node is gone after the flash. */
		private static SimpleTextAttributes attributes(FileStatus status, boolean ghost, Color background) {
			int style = ghost || status == FileStatus.DELETED ? SimpleTextAttributes.STYLE_STRIKEOUT : SimpleTextAttributes.STYLE_PLAIN;
			if (background != null) {
				// the fragment background is painted only for opaque attributes; tree cell renderers are not opaque
				style |= SimpleTextAttributes.STYLE_OPAQUE;
			}
			Color foreground = ghost ? UIUtil.getInactiveTextColor() : status.getColor();
			return new SimpleTextAttributes(background, foreground, null, style);
		}
	}
}
