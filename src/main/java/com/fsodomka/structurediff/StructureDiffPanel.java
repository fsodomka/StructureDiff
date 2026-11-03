package com.fsodomka.structurediff;

import com.fsodomka.structurediff.ChangeFlashes.Flash;
import com.fsodomka.structurediff.DiagnosticMembers.Diagnostics;
import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.DiffTreeNodes.Ghost;
import com.fsodomka.structurediff.DiffTreeNodes.RepoNode;
import com.fsodomka.structurediff.history.MemberHistoryToolWindowFactory;
import com.fsodomka.structurediff.review.FindingMembers;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.fsodomka.structurediff.review.ReviewFindingsListener;
import com.fsodomka.structurediff.review.ReviewFindingsToolWindowFactory;
import com.fsodomka.structurediff.review.ReviewSeverity;
import com.fsodomka.structurediff.settings.DiagnosticLevel;
import com.fsodomka.structurediff.settings.StructureDiffSettings;
import com.fsodomka.structurediff.source.ChangeSource;
import com.fsodomka.structurediff.source.CommitChangeSource;
import com.fsodomka.structurediff.source.LocalChangeSource;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.DiffUtil;
import com.intellij.diff.util.Side;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.*;
import com.intellij.ui.awt.RelativePoint;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;
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
import java.awt.datatransfer.StringSelection;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
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
	private final ChangeFlashes flashes = new ChangeFlashes(() -> {
		tree.repaint();
		this.treemap.repaint();
	}, () -> show(this.files, false), () -> this.showTreemap);
	/** The {@link FileDiff} a diff request was opened from, so a later open of the same file can reuse its tab. */
	private static final Key<FileDiff> OPENED_FILE_DIFF = Key.create("structureDiff.openedFileDiff");
	private static final String SHOW_NESTED_KEY = "structureDiff.showNested";
	private static final String SHOW_UNVERSIONED_KEY = "structureDiff.showUnversioned";
	private static final String FLASH_CHANGES_KEY = "structureDiff.flashChanges";
	private static final String SHOW_TREEMAP_KEY = "structureDiff.showTreemap";
	private static final String SHOW_USAGES_KEY = "structureDiff.showUsages";
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
	private boolean showNested = PropertiesComponent.getInstance().getBoolean(SHOW_NESTED_KEY, false);
	private boolean showUnversioned = PropertiesComponent.getInstance().getBoolean(SHOW_UNVERSIONED_KEY, true);
	private boolean flashChanges = PropertiesComponent.getInstance().getBoolean(FLASH_CHANGES_KEY, true);
	private boolean showTreemap = PropertiesComponent.getInstance().getBoolean(SHOW_TREEMAP_KEY, false);
	private final JScrollPane treeScroll = ScrollPaneFactory.createScrollPane(tree);
	private boolean showUsages = PropertiesComponent.getInstance().getBoolean(SHOW_USAGES_KEY, false);
	private final UsagesPanel usagesPanel;
	private final UsagesController usages;
	/** The tree or treemap above the {@link #usagesPanel}; its proportion is saved under its key. */
	private final OnePixelSplitter usagesSplitter = new OnePixelSplitter(true, "structureDiff.usagesProportion", 0.7f);
	/** Set while {@link #show} replaces the tree, whose selection events are not the user's. */
	private boolean rebuilding;
	private final TreemapPanel treemap = new TreemapPanel(tree, StructureDiffPanel::speedSearchText,
			node -> DiffTreeNodes.keyPart(node.getUserObject()), this::jumpToSource, this::openSelected,
			this::findingsOf, this::showFindings, this::diagnosticsOf, this::notAnalysed, this::openDiagnostic, this::isTestFile,
			flashes::get, this::isRelated, () -> this.loading, () -> tree.getEmptyText().getText());
	/** A refresh is in progress, from the start until the first one finishes; shown by the treemap, the tree paints busy. */
	private boolean loading = true;
	/** Paths of the unversioned files in {@link #files}; they are loaded even when hidden, so the toggle needs no refresh. */
	private volatile Set<String> unversionedPaths = Set.of();
	/** Paths of the files in test sources, marked as such in the tree; looked up off the EDT, as the index may be slow. */
	private volatile Set<String> testPaths = Set.of();
	/** Package of each file in a source root, relative to the package all changed code shares; shown instead of its directory. */
	private Map<String, String> packages = Map.of();
	/** Review findings by absolute file path, see {@link FindingMembers}; EDT only. */
	private Map<String, List<MemberFinding>> findings = Map.of();
	private final AtomicInteger findingsGeneration = new AtomicInteger();
	/** The IDE's diagnostics on the shown files with an open editor, see {@link DiagnosticMembers}; EDT only. */
	private Diagnostics diagnostics = Diagnostics.NONE;
	private final AtomicInteger diagnosticsGeneration = new AtomicInteger();
	/** Only touched by the diagnostics computation, which may overlap a cancelled older one. */
	private final Map<String, DiagnosticMembers.CachedKeyPaths> diagnosticsCache = new ConcurrentHashMap<>();
	/** Whether the panel is showing; a hidden panel (other mode, closed tool window) does not refresh. */
	private volatile boolean showing;
	/** A refresh or findings update was skipped while hidden; done when the panel shows again. */
	private volatile boolean stale;

	/** @param extraActions added to the toolbar before Expand All and Collapse All */
	StructureDiffPanel(Project project, ChangeSource source, AnAction... extraActions) {
		super(true, true);
		this.project = project;
		this.source = source;
		usagesPanel = new UsagesPanel(project, this::jumpToSource);
		Disposer.register(this, usagesPanel);
		usages = new UsagesController(project, usagesPanel, tree, () -> {
			tree.repaint();
			treemap.repaint();
		}, this);

		tree.setRootVisible(false);
		// "Nothing to show" only once the first refresh finished
		tree.getEmptyText().clear().appendLine(AnimatedIcon.Default.INSTANCE, "Loading…",
				SimpleTextAttributes.REGULAR_ATTRIBUTES, null);
		tree.setCellRenderer(new Renderer());
		ToolTipManager.sharedInstance().registerComponent(tree);
		// matches the shown label, not the user object's toString (a FileDiff's holds the whole source text);
		// searches collapsed nodes too, expanding to the match
		TreeSpeedSearch.installOn(tree, true, path -> speedSearchText((DefaultMutableTreeNode) path.getLastPathComponent()));
		// double-click and Enter edit the file like in the Project view; middle click shows the diff
		Trees.installOpenHandler(tree, this::jumpToSource);
		MouseAdapter mouse = new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				if (SwingUtilities.isMiddleMouseButton(e) && path != null) {
					tree.setSelectionPath(path);
					openSelected();
					e.consume();
				}
				else if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 1 && path != null) {
					Signal signal = signalAt(path, e.getX());
					if (signal == Signal.REVIEW) {
						showFindings(findingsOf((DefaultMutableTreeNode) path.getLastPathComponent()));
						e.consume();
					}
					else if (signal == Signal.IDE) {
						openDiagnostic((DefaultMutableTreeNode) path.getLastPathComponent());
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
		updateContent();
		tree.addTreeSelectionListener(e -> {
			if (!rebuilding) {
				updateUsages();
			}
		});
		installPopupMenu();

		// the treemap shows all levels, so there is nothing to expand or collapse
		DefaultTreeExpander expander = new DefaultTreeExpander(tree) {
			@Override
			public boolean canExpand() {
				return !showTreemap && super.canExpand();
			}

			@Override
			public boolean canCollapse() {
				return !showTreemap && super.canCollapse();
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
		group.add(new EdtToggleAction("Show Nested Changes",
				"Show changes inside functions, components and object literals, not only inside classes", AllIcons.Actions.ShowAsTree) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return showNested;
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				showNested = state;
				PropertiesComponent.getInstance().setValue(SHOW_NESTED_KEY, state, false);
				show(files, false);
			}

		});
		group.add(new EdtToggleAction("Show Unversioned Files", null, AllIcons.Vcs.ShowUnversionedFiles) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return source.hasUnversioned() && showUnversioned;
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				showUnversioned = state;
				PropertiesComponent.getInstance().setValue(SHOW_UNVERSIONED_KEY, state, true);
				show(files, false);
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				super.update(e);
				if (!source.hasUnversioned()) {
					e.getPresentation().setEnabled(false);
					e.getPresentation().setDescription("Commits have no unversioned files");
				}
			}

		});
		group.add(new EdtToggleAction("Flash Changes",
				"Briefly highlight members and files that appeared, changed or disappeared", AllIcons.Actions.Highlighting) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return source.hasUnversioned() && flashChanges;
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				flashChanges = state;
				PropertiesComponent.getInstance().setValue(FLASH_CHANGES_KEY, state, true);
				if (!state) {
					// drops the running flashes and their ghosts
					flashes.clear();
					show(files, false);
				}
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				super.update(e);
				if (!source.hasUnversioned()) {
					e.getPresentation().setEnabled(false);
					e.getPresentation().setDescription("Commits have no member changes");
				}
			}

		});
		group.add(new EdtToggleAction("Show as Treemap",
				"Show the changes as nested rectangles sized by their changed lines", AllIcons.Actions.GroupByModule) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return showTreemap;
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				showTreemap = state;
				PropertiesComponent.getInstance().setValue(SHOW_TREEMAP_KEY, state, false);
				updateContent();
			}

		});
		group.add(new EdtToggleAction("Show Usages",
				"Show what the selected member uses and what uses it, and highlight the related changed members",
				AllIcons.Hierarchy.Supertypes) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return usagesShown();
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				showUsages = state;
				PropertiesComponent.getInstance().setValue(SHOW_USAGES_KEY, state, false);
				updateContent();
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				super.update(e);
				if (source instanceof CommitChangeSource) {
					e.getPresentation().setEnabled(false);
					e.getPresentation().setDescription("Usages need the working-tree version of the files, not a commit's");
				}
			}

		});
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
		setToolbar(top);

		// sources report changes from any thread; the refresh that finishes last clears it
		source.subscribeReplacing(() -> UIUtil.invokeLaterIfNeeded(() -> {
			tree.setPaintBusy(true);
			loading = true;
			treemap.repaint();
		}), this);
		source.subscribe(this::scheduleRefresh, this);
		project.getMessageBus().connect(this).<ReviewFindingsListener>subscribe(ReviewFindingsListener.TOPIC, () -> {
			if (showing) {
				updateFindings();
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
		scheduleRefresh();
	}

	/**
	 * Picks up the toggles saved by another panel: the Uncommitted panel outlives mode switches, and the settings are
	 * shared by all projects.
	 */
	private void loadSettings() {
		PropertiesComponent properties = PropertiesComponent.getInstance();
		boolean savedTreemap = properties.getBoolean(SHOW_TREEMAP_KEY, false);
		boolean savedUsages = properties.getBoolean(SHOW_USAGES_KEY, false);
		if (savedTreemap != showTreemap || savedUsages != showUsages) {
			showTreemap = savedTreemap;
			showUsages = savedUsages;
			// later, not in the showing event: that comes from this panel's addNotify before its children's, so a child
			// swapped in now is added twice, and a splitter's divider leaks the listener of the first
			SwingUtilities.invokeLater(this::updateContent);
		}
		boolean savedNested = properties.getBoolean(SHOW_NESTED_KEY, false);
		boolean savedUnversioned = properties.getBoolean(SHOW_UNVERSIONED_KEY, true);
		boolean savedFlash = properties.getBoolean(FLASH_CHANGES_KEY, true);
		if (!savedFlash && flashChanges) {
			flashes.clear();
		}
		boolean reshow = savedNested != showNested || savedUnversioned != showUnversioned || savedFlash != flashChanges;
		showNested = savedNested;
		showUnversioned = savedUnversioned;
		flashChanges = savedFlash;
		if (reshow) {
			show(files, false);
		}
	}

	/** Show Diff (Ctrl+D), Jump to Source (F4) and Copy Path, with the shortcuts of the matching IDE actions. */
	private void installPopupMenu() {
		DefaultActionGroup popup = new DefaultActionGroup();
		popup.add(treeAction("Show Diff", AllIcons.Actions.Diff, "Diff.ShowDiff", () -> selectedFile() != null, this::openSelected));
		popup.add(treeAction("Jump to Source", AllIcons.Actions.EditSource, IdeActions.ACTION_EDIT_SOURCE,
				() -> selectedWorkingFile() != null, this::jumpToSource));
		popup.add(treeAction("Revert Member", AllIcons.Actions.Rollback, null,
				() -> source instanceof LocalChangeSource && selectedMember() != null && selectedWorkingFile() != null,
				this::revertSelectedMember));
		popup.add(treeAction("Show Member History", AllIcons.Vcs.History, null,
				this::canShowSelectedMemberHistory, this::showSelectedMemberHistory));
		popup.add(treeAction("Copy Path", AllIcons.Actions.Copy, null, () -> selectedFile() != null, () -> {
			FileDiff file = selectedFile();
			if (file == null) {
				return false;
			}
			CopyPasteManager.getInstance().setContents(new StringSelection(FileUtil.toSystemDependentName(file.path())));
			return true;
		}));
		PopupHandler.installPopupMenu(tree, popup, ActionPlaces.getPopupPlace("StructureDiffPopup"));
		PopupHandler.installPopupMenu(treemap, popup, ActionPlaces.getPopupPlace("StructureDiffPopup"));
	}

	private DumbAwareAction treeAction(String text, Icon icon, String shortcutActionId, BooleanSupplier enabled,
									   BooleanSupplier perform) {
		DumbAwareAction action = new DumbAwareAction(text, null, icon) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				perform.getAsBoolean();
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				e.getPresentation().setEnabled(enabled.getAsBoolean());
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		};
		AnAction shortcutSource = shortcutActionId == null ? null : ActionManager.getInstance().getAction(shortcutActionId);
		if (shortcutSource != null) {
			action.registerCustomShortcutSet(shortcutSource.getShortcutSet(), tree);
			action.registerCustomShortcutSet(shortcutSource.getShortcutSet(), treemap);
		}
		return action;
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
			loaded.add(StructureDiffComputer.load(change, revisionCache, previous.get(ChangesUtil.getFilePath(change).getPath())));
		}
		for (FilePath path : unversioned) {
			if (current != generation.get()) {
				return;
			}
			loaded.add(StructureDiffComputer.loadUnversioned(path, previous.get(path.getPath())));
		}
		loaded.sort(Comparator.comparing(FileDiff::path, String.CASE_INSENSITIVE_ORDER));
		Set<String> usedRevisions = changes.stream().flatMap(change -> Stream.of(change.getBeforeRevision(), change.getAfterRevision()))
				.map(StructureDiffComputer::revisionKey).filter(Objects::nonNull).collect(Collectors.toSet());
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
						updateFindings();
						updateDiagnostics();
					}
				})
				.submit(AppExecutorUtil.getAppExecutorService())
				// a cancellation by coalesceBy comes with a newer generation, so only an error of the latest refresh clears it
				.onError(e -> refreshFailed(current));
	}

	/** A toggle whose state is panel state, read on the EDT. */
	private abstract static class EdtToggleAction extends ToggleAction {
		EdtToggleAction(String text, String description, Icon icon) {
			super(text, description, icon);
		}

		@Override
		public @NotNull ActionUpdateThread getActionUpdateThread() {
			return ActionUpdateThread.EDT;
		}
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
		DefaultMutableTreeNode lead = selectedNode();
		// after a refresh the whole selection comes back, the lead last, so Show Diff and Export still use the lead
		List<List<String>> selectedKeys = new ArrayList<>(selectedNodes().stream().map(DiffTreeNodes::keyOf).toList());
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
			FileDiff file = showNested || fullFile.members() == null ? fullFile
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
		Set<List<String>> started = flashes.update(oldNodes, newNodes, highlightChanges, flashChanges, root, repoNodes, vcsRoots);
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
		appendCounts(summary, total, SimpleTextAttributes.STYLE_BOLD);
		appendLines(summary, linesAdded, linesRemoved, SimpleTextAttributes.GRAYED_ATTRIBUTES);
		updateFindingCounts();
	}

	/** {@code loadedFiles} without the unversioned ones while they are hidden. */
	private List<FileDiff> shownFiles(List<FileDiff> loadedFiles) {
		return showUnversioned ? loadedFiles : loadedFiles.stream().filter(f -> !unversionedPaths.contains(f.path())).toList();
	}

	/** Writes the shown changes of {@link #exportRoots()} for review; see {@link DiffExport#export}. */
	private void exportDiff() {
		DiffExport.export(project, exportRoots(), shownFiles(files), vcsRoots);
	}

	/** Roots to export: the roots of the selection, else the roots of all shown files; empty when none is in a repository. */
	private List<VirtualFile> exportRoots() {
		return DiffExport.exportRoots(shownFiles(files), vcsRoots, selectedNodes());
	}

	/** Recomputes which members the review findings are in, then repaints. */
	private void updateFindings() {
		int current = findingsGeneration.incrementAndGet();
		Supplier<Map<String, List<MemberFinding>>> compute = FindingMembers.prepare(project);
		ReadAction.nonBlocking(compute::get)
				.expireWith(this)
				// cancels an older computation, whose result would be discarded anyway
				.coalesceBy(this, findingsGeneration)
				.finishOnUiThread(ModalityState.any(), result -> {
					if (current == findingsGeneration.get()) {
						findings = result;
						tree.repaint();
						treemap.repaint();
						updateFindingCounts();
					}
				})
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	private boolean isShownFile(VirtualFile file) {
		String path = file.getPath();
		return files.stream().anyMatch(f -> f.path().equals(path));
	}

	/** The daemon's results or the open editors changed; updated now, or when the panel shows again. */
	private void diagnosticsChanged() {
		if (showing) {
			updateDiagnostics();
		}
		else {
			stale = true;
		}
	}

	/**
	 * Recomputes the IDE's diagnostics on the shown files, then repaints; none when turned off or when the after side is
	 * not the working tree, whose offsets would not match the editor's document.
	 */
	private void updateDiagnostics() {
		int current = diagnosticsGeneration.incrementAndGet();
		StructureDiffSettings settings = StructureDiffSettings.getInstance();
		Set<DiagnosticLevel> shown = settings.diagnosticLevels();
		if (shown.isEmpty() || !source.hasUnversioned()) {
			if (diagnostics != Diagnostics.NONE) {
				diagnostics = Diagnostics.NONE;
				tree.repaint();
				treemap.repaint();
			}
			return;
		}
		Supplier<Diagnostics> compute = DiagnosticMembers.prepare(project, shownFiles(files), shown, diagnosticsCache);
		ReadAction.nonBlocking(compute::get)
				.expireWith(this)
				// cancels an older computation, whose result would be discarded anyway
				.coalesceBy(this, diagnosticsGeneration)
				.finishOnUiThread(ModalityState.any(), result -> {
					if (current == diagnosticsGeneration.get()) {
						diagnostics = result;
						tree.repaint();
						treemap.repaint();
					}
				})
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	/** Shows the {@link MemberSignals#findingCounts} of the shown files; findings of other files are mentioned in the tooltip only. */
	private void updateFindingCounts() {
		int[] counts = MemberSignals.findingCounts(shownFiles(files), findings);
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
		List<MemberFinding> found = findingsOf(node);
		List<MemberDiagnostic> diags = diagnosticsOf(node);
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

	/**
	 * Findings shown on a node, most severe first: all of a file node's; on a member node those it is the innermost
	 * shown member of. Findings in members that are not shown (unchanged, renamed, nested) count on the file only.
	 */
	private List<MemberFinding> findingsOf(DefaultMutableTreeNode node) {
		return MemberSignals.findingsOf(node, findings);
	}

	/** Diagnostics shown on a node, most severe first, placed as {@link #findingsOf(DefaultMutableTreeNode)}. */
	private List<MemberDiagnostic> diagnosticsOf(DefaultMutableTreeNode node) {
		return MemberSignals.placedOn(node, diagnostics.byPath(), MemberDiagnostic::keyPath);
	}

	/** Whether {@code node} is a changed file the IDE has not analysed, as it has no open editor. */
	private boolean notAnalysed(DefaultMutableTreeNode node) {
		return diagnostics != Diagnostics.NONE && node.getUserObject() instanceof FileDiff file
				&& file.status() != FileStatus.DELETED && !diagnostics.analysed().contains(file.path());
	}

	/** Whether {@code node} is a changed file in test sources. */
	private boolean isTestFile(DefaultMutableTreeNode node) {
		return node.getUserObject() instanceof FileDiff file && testPaths.contains(file.path());
	}

	/** Opens the node's file at its most severe diagnostic. */
	private void openDiagnostic(DefaultMutableTreeNode node) {
		List<MemberDiagnostic> diags = diagnosticsOf(node);
		DefaultMutableTreeNode fileNode = node;
		while (fileNode != null && !(fileNode.getUserObject() instanceof FileDiff)) {
			fileNode = (DefaultMutableTreeNode) fileNode.getParent();
		}
		VirtualFile file = fileNode == null ? null
				: LocalFileSystem.getInstance().findFileByPath(((FileDiff) fileNode.getUserObject()).path());
		if (!diags.isEmpty() && file != null) {
			new OpenFileDescriptor(project, file, diags.getFirst().offset()).navigate(true);
		}
	}

	private void showFindings(List<MemberFinding> found) {
		ReviewFindingsToolWindowFactory.show(project, found);
	}


	/**
	 * Changed lines as one grey, unsigned number with a unit, so only the colored member counts use +/~/−;
	 * the added/removed split is in the tooltip.
	 */
	private static void appendLines(SimpleColoredComponent component, int added, int removed, SimpleTextAttributes attributes) {
		int lines = added + removed;
		if (lines > 0) {
			component.append("   · " + lines + (lines == 1 ? " line" : " lines"), attributes);
			component.setToolTipText(linesText(added, removed));
		}
	}

	private static String linesText(int added, int removed) {
		// a side with no lines is left out, as in "10 lines added" rather than "10 lines added, 0 removed"
		if (removed == 0) {
			return added + (added == 1 ? " line" : " lines") + " added";
		}
		if (added == 0) {
			return removed + (removed == 1 ? " line" : " lines") + " removed";
		}
		return added + " lines added, " + removed + " removed";
	}

	/** Text the speed search matches: the name shown by the {@link Renderer}. */
	private static String speedSearchText(DefaultMutableTreeNode node) {
		Object item = node.getUserObject();
		if (item instanceof Ghost(Object ghostItem)) {
			item = ghostItem;
		}
		return switch (item) {
			case RepoNode(VirtualFile root) -> root.getName();
			case FileDiff file -> StringUtil.getShortName(file.path(), '/');
			case MemberDiff member -> Renderer.shorten(member.label());
			case null, default -> "";
		};
	}

	/** The added/modified/removed {@link DiffTreeNodes#count}s, each in its status color, where not 0. */
	private static void appendCounts(SimpleColoredComponent component, int[] counts, int style) {
		appendCount(component, "+", counts[0], FileStatus.ADDED, style);
		appendCount(component, "~", counts[1], FileStatus.MODIFIED, style);
		appendCount(component, "−", counts[2], FileStatus.DELETED, style);
	}

	private static void appendCount(SimpleColoredComponent component, String prefix, int count, FileStatus status, int style) {
		if (count > 0) {
			component.append(" " + prefix + count, new SimpleTextAttributes(style, status.getColor()));
		}
	}

	private DefaultMutableTreeNode selectedNode() {
		return (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
	}

	/** All selected nodes; the tree allows selecting several rows with Ctrl or Shift. */
	private List<DefaultMutableTreeNode> selectedNodes() {
		TreePath[] paths = tree.getSelectionPaths();
		if (paths == null) {
			return List.of();
		}
		return Arrays.stream(paths)
				.map(path -> (DefaultMutableTreeNode) path.getLastPathComponent())
				.filter(Objects::nonNull)
				.toList();
	}

	/** File of the selected node or of its enclosing file node; null for a repository node or no selection. */
	private FileDiff selectedFile() {
		return DiffTreeNodes.fileOf(selectedNode());
	}

	private MemberDiff selectedMember() {
		DefaultMutableTreeNode node = selectedNode();
		return node != null && node.getUserObject() instanceof MemberDiff member ? member : null;
	}

	/** Working copy of the selected file, for a commit the file at its path after the commit; null when it was deleted. */
	private VirtualFile selectedWorkingFile() {
		FileDiff file = selectedFile();
		ContentRevision after = file == null ? null : file.change().getAfterRevision();
		if (after instanceof CurrentContentRevision current) {
			return current.getVirtualFile();
		}
		return after == null ? null : LocalFileSystem.getInstance().findFileByPath(after.getFile().getPath());
	}

	/** Opens the file in the editor at the selected member; a removed member opens the diff instead. */
	private boolean jumpToSource() {
		VirtualFile workingFile = selectedWorkingFile();
		if (workingFile == null) {
			return false;
		}
		MemberDiff member = selectedMember();
		if (member != null && member.afterOffset() < 0) {
			return openSelected();
		}
		FileDiff file = Objects.requireNonNull(selectedFile());
		if (member == null) {
			new OpenFileDescriptor(project, workingFile).navigate(true);
			return true;
		}
		boolean current = file.change().getAfterRevision() instanceof CurrentContentRevision;
		CharSequence text = ApplicationManager.getApplication().runReadAction((Computable<CharSequence>) () -> {
			Document document = FileDocumentManager.getInstance().getDocument(workingFile);
			return document == null ? null : document.getImmutableCharSequence();
		});
		if (text == null || current && StringUtil.equals(text, file.afterText())) {
			// the member offsets are still valid; skip reparsing
			new OpenFileDescriptor(project, workingFile, member.afterOffset()).navigate(true);
			return true;
		}
		List<MemberDiff> path = selectedMemberPath();
		// parsing the current text and diffing it against the committed one can take long on large files
		ReadAction.nonBlocking(() -> {
					int offset = StructureDiffComputer.currentOffset(project, workingFile.getName(), workingFile.getFileType(), text, path);
					if (offset >= 0 || current) {
						return new int[]{offset >= 0 ? offset : member.afterOffset(), -1};
					}
					// the member is gone from the working copy: map its line in the committed text onto the current text
					int line = StringUtil.offsetToLineNumber(file.afterText(), member.afterOffset());
					try {
						line = Math.max(0, Diff.translateLine(file.afterText(), text, line, true));
					}
					catch (FilesTooBigForDiffException ignored) {
					}
					return new int[]{-1, line};
				})
				.expireWith(this)
				.finishOnUiThread(ModalityState.defaultModalityState(), target -> (target[1] < 0
						? new OpenFileDescriptor(project, workingFile, target[0])
						: new OpenFileDescriptor(project, workingFile, target[1], 0)).navigate(true))
				.submit(AppExecutorUtil.getAppExecutorService());
		return true;
	}

	/** The selected member and its enclosing members, outermost first. */
	private List<MemberDiff> selectedMemberPath() {
		return DiffTreeNodes.memberPath(selectedNode());
	}

	/** Whether {@code node} is related to the selected member by its usages; for the treemap, created before them. */
	private boolean isRelated(DefaultMutableTreeNode node) {
		return usages.isRelated(node);
	}

	/** Searches the usages of the selected member while they are shown. */
	private void updateUsages() {
		if (usagesShown()) {
			usages.update(selectedNode(), files);
		}
	}

	/** Whether the {@link #usagesPanel} is shown: Show Usages is on, and the files are the working copy, not a commit's. */
	private boolean usagesShown() {
		return showUsages && !(source instanceof CommitChangeSource);
	}

	/** Shows the tree or the treemap, with the {@link #usagesPanel} below while {@link #usagesShown}. */
	private void updateContent() {
		JComponent main = showTreemap ? treemap : treeScroll;
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

	/** A member that exists after the change, in a file that has an after revision. */
	private boolean canShowSelectedMemberHistory() {
		MemberDiff member = selectedMember();
		FileDiff file = selectedFile();
		if (member == null || member.afterOffset() < 0 || file == null || file.change().getAfterRevision() == null) {
			return false;
		}
		// uncommitted: an added file or member is in no commit yet; the other modes show committed revisions
		return !(source instanceof LocalChangeSource) || file.status() != FileStatus.ADDED && member.status() != FileStatus.ADDED;
	}

	/** Opens the commit history of the selected member, at the file's path after the change. */
	private boolean showSelectedMemberHistory() {
		FileDiff file = selectedFile();
		ContentRevision after = file == null ? null : file.change().getAfterRevision();
		List<MemberDiff> path = selectedMemberPath();
		if (after == null || path.isEmpty()) {
			return false;
		}
		// local changes are uncommitted: the after keys are working copy keys
		MemberHistoryToolWindowFactory.show(project, after.getFile(), path.stream().map(MemberDiff::key).toList(),
				source instanceof LocalChangeSource ? file.change() : null);
		return true;
	}

	/** Replaces the selected member in the working file with its original version, as one undoable command. */
	private boolean revertSelectedMember() {
		VirtualFile workingFile = selectedWorkingFile();
		FileDiff file = selectedFile();
		List<MemberDiff> path = selectedMemberPath();
		Document document = workingFile == null ? null : FileDocumentManager.getInstance().getDocument(workingFile);
		if (file == null || path.isEmpty() || document == null) {
			return false;
		}
		CharSequence text = document.getImmutableCharSequence();
		long stamp = document.getModificationStamp();
		ReadAction.nonBlocking(() -> MemberRevert.compute(project, file, workingFile.getName(), workingFile.getFileType(), text, path))
				.expireWith(this)
				.finishOnUiThread(ModalityState.defaultModalityState(), result -> {
					String error = result.error();
					if (error == null && document.getModificationStamp() != stamp) {
						error = "The file changed while reverting. Try again.";
					}
					if (error != null) {
						showWarningBalloon(error);
						return;
					}
					MemberRevert.Edit edit = Objects.requireNonNull(result.edit());
					WriteCommandAction.runWriteCommandAction(project, "Revert " + path.getLast().label(), null,
							() -> document.replaceString(edit.start(), edit.end(), edit.text()));
				})
				.submit(AppExecutorUtil.getAppExecutorService());
		return true;
	}

	/** Shows {@code message} below the selected row, or at the tree's center when nothing is selected. */
	private void showWarningBalloon(String message) {
		Rectangle row = tree.getPathBounds(tree.getSelectionPath());
		RelativePoint at = row == null ? RelativePoint.getCenterOf(tree)
				: new RelativePoint(tree, new Point(row.x + row.width / 2, row.y + row.height));
		JBPopupFactory.getInstance().createHtmlTextBalloonBuilder(message, MessageType.WARNING, null)
				.setFadeoutTime(5000)
				.createBalloon()
				.show(at, Balloon.Position.below);
	}

	private boolean openSelected() {
		DefaultMutableTreeNode node = selectedNode();
		FileDiff file = selectedFile();
		if (file == null) {
			return false;
		}
		if (file.fileType().isBinary()) {
			// no text was loaded for binary files; open the working copy (e.g. image viewer) instead
			return jumpToSource();
		}
		Pair<Side, Integer> scrollTo = null;
		if (node.getUserObject() instanceof MemberDiff member) {
			if (member.afterOffset() >= 0) {
				scrollTo = Pair.create(Side.RIGHT, StringUtil.offsetToLineNumber(file.afterText(), member.afterOffset()));
			}
			else if (member.beforeOffset() >= 0) {
				scrollTo = Pair.create(Side.LEFT, StringUtil.offsetToLineNumber(file.beforeText(), member.beforeOffset()));
			}
		}
		if (scrollOpenDiff(file, scrollTo)) {
			return true;
		}
		DiffContentFactory factory = DiffContentFactory.getInstance();
		DiffContent before = factory.create(project, StringUtil.notNullize(file.beforeText()), file.fileType());
		VirtualFile afterFile = file.change().getAfterRevision() instanceof CurrentContentRevision current
				? current.getVirtualFile() : null;
		DiffContent after = afterFile != null
				? factory.create(project, afterFile)
				: factory.create(project, StringUtil.notNullize(file.afterText()), file.fileType());
		SimpleDiffRequest request = new SimpleDiffRequest(file.path(), before, after, "Before", "After");
		request.putUserData(OPENED_FILE_DIFF, file);
		if (scrollTo != null) {
			request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE, scrollTo);
		}
		DiffManager.getInstance().showDiff(project, request);
		return true;
	}

	/**
	 * Scrolls a diff tab already opened for {@code file} to {@code scrollTo} and selects the tab. Only a tab opened from
	 * the same {@link FileDiff} instance is reused, so a refresh that changed the before-text opens a fresh diff.
	 */
	private boolean scrollOpenDiff(FileDiff file, Pair<Side, Integer> scrollTo) {
		FileEditorManager editors = FileEditorManager.getInstance(project);
		for (FileEditor editor : editors.getAllEditors()) {
			if (!(editor instanceof DiffEditorViewerFileEditor diffEditor)
					|| !(diffEditor.getEditorViewer() instanceof DiffRequestProcessor processor)
					|| processor.getActiveRequest().getUserData(OPENED_FILE_DIFF) != file) {
				continue;
			}
			new OpenFileDescriptor(project, diffEditor.getFile()).navigate(true);
			if (scrollTo != null) {
				if (processor.getActiveViewer() instanceof TwosideTextDiffViewer twoside) {
					DiffUtil.scrollEditor(twoside.getEditor(scrollTo.first), scrollTo.second, false);
				}
				else if (processor.getActiveViewer() instanceof UnifiedDiffViewer unified) {
					DiffUtil.scrollEditor(unified.getEditor(),
							unified.transferLineToOneside(scrollTo.first, scrollTo.second), false);
				}
			}
			return true;
		}
		return false;
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
				String path = relativePath(file.path(), basePath);
				int slash = path.lastIndexOf('/');
				List<MemberFinding> found = ghost ? List.of() : findingsOf(node);
				List<MemberDiagnostic> diags = ghost ? List.of() : diagnosticsOf(node);
				setIcon(MemberSignals.withSignalIcons(withTestMark(file.fileType().getIcon(), file.path()), found, diags));
				append(path.substring(slash + 1), attributes(restored ? FileStatus.NOT_CHANGED : file.status(), ghost && !restored, background));
				if (file.members() != null && !ghost) {
					int[] counts = new int[3];
					DiffTreeNodes.count(file.members(), counts);
					appendCounts(this, counts, SimpleTextAttributes.STYLE_SMALLER);
				}
				if (!ghost) {
					appendLines(this, file.linesAdded(), file.linesRemoved(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
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
					String shown = change.isMoved() ? relativePath(beforePath, basePath) : StringUtil.getShortName(beforePath, '/');
					append("  ← " + shown, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
					moved = (change.isMoved() ? "Moved from " : "Renamed from ") + FileUtil.toSystemDependentName(beforePath);
				}
				String lines = file.linesAdded() + file.linesRemoved() == 0 ? null : linesText(file.linesAdded(), file.linesRemoved());
				StringBuilder tip = new StringBuilder("<html>").append(StringUtil.escapeXmlEntities(FileUtil.toSystemDependentName(path)));
				if (moved != null) {
					tip.append("<br>").append(StringUtil.escapeXmlEntities(moved));
				}
				if (lines != null) {
					tip.append("<br>").append(lines);
				}
				setToolTipText(MemberSignals.withSignals(tip.append("</html>").toString(), found, diags, !ghost && notAnalysed(node)));
			}
			else if (item instanceof MemberDiff member) {
				List<MemberFinding> found = ghost ? List.of() : findingsOf(node);
				List<MemberDiagnostic> diags = ghost ? List.of() : diagnosticsOf(node);
				Icon icon = member.icon();
				if (((DefaultMutableTreeNode) node.getParent()).getUserObject() instanceof FileDiff parent) {
					icon = withTestMark(icon, parent.path());
				}
				setIcon(MemberSignals.withSignalIcons(icon, found, diags));
				String shortLabel = shorten(member.label());
				append(shortLabel, attributes(restored ? FileStatus.NOT_CHANGED : member.status(), ghost && !restored, background));
				setToolTipText(MemberSignals.withSignals(tooltip(member), found, diags, false));
			}
		}

		/** {@code path} relative to {@code basePath} when it is below it, else {@code path}. */
		private static String relativePath(String path, String basePath) {
			return path.startsWith(basePath + "/") ? path.substring(basePath.length() + 1) : path;
		}

		/** Line counts, plus the old and new signature when it changed. */
		private static String tooltip(MemberDiff member) {
			StringBuilder html = new StringBuilder("<html>");
			if (!member.beforeLabel().equals(member.label())) {
				html.append("<b>Before:</b> ").append(StringUtil.escapeXmlEntities(member.beforeLabel()))
						.append("<br><b>After:</b> ").append(StringUtil.escapeXmlEntities(member.label())).append("<br>");
			}
			else if (!shorten(member.label()).equals(member.label())) {
				html.append(StringUtil.escapeXmlEntities(member.label())).append("<br>");
			}
			if (member.status() == FileStatus.MODIFIED) {
				html.append(linesText(member.linesAdded(), member.linesRemoved()));
			}
			else {
				html.append(member.linesAdded() + member.linesRemoved()).append(" lines");
			}
			return html.append("</html>").toString();
		}
		/** {@code icon} with the test marker when {@code path} is in test sources, as the Project view shows test classes. */
		private Icon withTestMark(Icon icon, String path) {
			if (icon == null || !testPaths.contains(path)) {
				return icon;
			}
			return IconManager.getInstance().createLayered(icon, AllIcons.Nodes.JunitTestMark);
		}


		/** {@code foo(List<Map<A, B>>, int): Pair<X, Y>} -> {@code foo(List, int): Pair}; long parameter lists cut after the first. */
		private static String shorten(String label) {
			String result = stripGenerics(label);
			int open = result.indexOf('(');
			int close = result.lastIndexOf(')');
			if (open > 0 && close > open && close - open > 60) {
				int comma = result.indexOf(',', open);
				if (comma > 0 && comma < close) {
					result = result.substring(0, comma) + ", …" + result.substring(close);
				}
			}
			return result;
		}

		private static String stripGenerics(String label) {
			StringBuilder sb = new StringBuilder(label.length());
			int depth = 0;
			for (int i = 0; i < label.length(); i++) {
				char c = label.charAt(i);
				if (c == '<') {
					depth++;
				}
				else if (c == '>' && depth > 0) {
					depth--;
				}
				else if (depth == 0) {
					sb.append(c);
				}
			}
			return depth == 0 ? sb.toString() : label;
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
