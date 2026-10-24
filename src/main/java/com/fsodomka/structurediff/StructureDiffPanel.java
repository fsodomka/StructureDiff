package com.fsodomka.structurediff;

import com.fsodomka.structurediff.DiagnosticMembers.Diagnostics;
import com.fsodomka.structurediff.DiagnosticMembers.MemberDiagnostic;
import com.fsodomka.structurediff.review.FindingMembers;
import com.fsodomka.structurediff.review.FindingMembers.MemberFinding;
import com.fsodomka.structurediff.review.ReviewFindingsListener;
import com.fsodomka.structurediff.review.ReviewFindingsToolWindowFactory;
import com.fsodomka.structurediff.review.ReviewSeverity;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.editor.DiffEditorViewerFileEditor;
import com.intellij.diff.impl.DiffRequestProcessor;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer;
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer;
import com.intellij.diff.util.DiffUtil;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.Side;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorManagerListener;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.MessageType;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
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

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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
	private static final long FLASH_MILLIS = 4000;
	/** The {@link FileDiff} a diff request was opened from, so a later open of the same file can reuse its tab. */
	private static final Key<FileDiff> OPENED_FILE_DIFF = Key.create("structureDiff.openedFileDiff");
	/**
	 * Recent changes by node key. Nodes that appeared or changed get a background that fades out;
	 * nodes that disappeared stay in the tree as {@link Ghost}s until their flash ends. Each node has its own flash:
	 * content changes to a flashing node do not restart it, while other nodes flash independently; a status change or
	 * removal always flashes.
	 */
	private final Map<List<String>, Flash> flashes = new HashMap<>();
	/** Whether a refresh result with {@link ChangeSource#highlightChanges()} was shown; it is the initial state, not changes to flash, even if a later one starts from an empty tree. */
	private boolean baselineShown;
	private final Timer fadeTimer = new Timer(50, e -> fadeStep());
	private static final String SHOW_NESTED_KEY = "structureDiff.showNested";
	private static final String SHOW_UNVERSIONED_KEY = "structureDiff.showUnversioned";
	private static final String FLASH_CHANGES_KEY = "structureDiff.flashChanges";
	private static final String SHOW_TREEMAP_KEY = "structureDiff.showTreemap";
	/** Editor type id of the platform's internal DiffPatchFileEditorProvider, which shows a patch file in the diff viewer. */
	private static final String PATCH_DIFF_EDITOR_TYPE = "DiffPatchFileEditorProvider";

	private final AtomicInteger generation = new AtomicInteger();
	private final SimpleColoredComponent summary = new SimpleColoredComponent();
	/** Finding counts per severity, after {@link #summary}; one icon each, which a {@link SimpleColoredComponent} cannot show. */
	private final JPanel findingCounts = new JPanel(new FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0));
	private volatile List<FileDiff> files = List.of();
	/** VCS root of each file path; files are grouped by root when there is more than one. */
	private Map<String, VirtualFile> vcsRoots = Map.of();
	/** Contents of committed revisions, which never change; loading them is VCS I/O. */
	private final Map<String, String> revisionCache = new ConcurrentHashMap<>();
	private boolean showNested = PropertiesComponent.getInstance().getBoolean(SHOW_NESTED_KEY, false);
	private boolean showUnversioned = PropertiesComponent.getInstance().getBoolean(SHOW_UNVERSIONED_KEY, true);
	private boolean flashChanges = PropertiesComponent.getInstance().getBoolean(FLASH_CHANGES_KEY, true);
	private boolean showTreemap = PropertiesComponent.getInstance().getBoolean(SHOW_TREEMAP_KEY, false);
	private final JScrollPane treeScroll = ScrollPaneFactory.createScrollPane(tree);
	private final TreemapPanel treemap = new TreemapPanel(tree, StructureDiffPanel::speedSearchText,
			node -> keyPart(node.getUserObject()), this::jumpToSource, this::openSelected,
			this::findingsOf, this::showFindings, this::diagnosticsOf, this::notAnalysed, this::openDiagnostic, () -> this.loading, () -> tree.getEmptyText().getText());
	/** A refresh is in progress, from the start until the first one finishes; shown by the treemap, the tree paints busy. */
	private boolean loading = true;
	/** Paths of the unversioned files in {@link #files}; they are loaded even when hidden, so the toggle needs no refresh. */
	private volatile Set<String> unversionedPaths = Set.of();
	/** Paths of the files in test sources, marked as such in the tree; looked up off the EDT, as the index may be slow. */
	private volatile Set<String> testPaths = Set.of();
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
		setContent(showTreemap ? treemap : treeScroll);
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
				else if (showTreemap) {
					e.getPresentation().setEnabled(false);
					e.getPresentation().setDescription("The treemap does not flash changes");
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
				setContent(state ? treemap : treeScroll);
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
		if (savedTreemap != showTreemap) {
			showTreemap = savedTreemap;
			setContent(showTreemap ? treemap : treeScroll);
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
		popup.add(treeAction("Copy Path", AllIcons.Actions.Copy, null, () -> selectedFile() != null, () -> {
			FileDiff file = selectedFile();
			if (file == null) {
				return false;
			}
			CopyPasteManager.getInstance().setContents(new StringSelection(FileUtil.toSystemDependentName(file.path())));
			return true;
		}));
		PopupHandler.installPopupMenu(tree, popup, "StructureDiffPopup");
		PopupHandler.installPopupMenu(treemap, popup, "StructureDiffPopup");
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
		ReadAction.nonBlocking(() -> {
					ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(project);
					ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
					Map<String, VirtualFile> roots = new HashMap<>();
					Set<String> tests = new HashSet<>();
					for (FileDiff file : loaded) {
						VirtualFile virtualFile = LocalFileSystem.getInstance().findFileByPath(file.path());
						if (virtualFile != null && fileIndex.isInTestSourceContent(virtualFile)) {
							tests.add(file.path());
						}
						VirtualFile root = vcsManager.getVcsRootFor(ChangesUtil.getFilePath(file.change()));
						if (root != null) {
							roots.put(file.path(), root);
						}
					}
					List<FileDiff> withMembers = new ArrayList<>(loaded.size());
					for (int i = 0; i < loaded.size(); i++) {
						FileDiff file = computed.get(i);
						if (file == null) {
							file = withMembers(previous, loaded.get(i));
							computed.set(i, file);
						}
						withMembers.add(file);
					}
					return new Snapshot(withMembers, roots, unversioned.stream().map(FilePath::getPath).collect(Collectors.toSet()), tests);
				})
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
						unversionedPaths = snapshot.unversioned();
						testPaths = snapshot.tests();
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

	private record Snapshot(List<FileDiff> files, Map<String, VirtualFile> roots, Set<String> unversioned, Set<String> tests) {
	}

	/** Group node of one VCS root. */
	record RepoNode(VirtualFile root) {
	}

	/** Reuses the members of a file whose contents did not change since the last refresh; parsing is the costly part. */
	private FileDiff withMembers(Map<String, FileDiff> previous, FileDiff file) {
		FileDiff old = previous.get(file.path());
		if (old != null && old.members() != null && old.status() == file.status() && Objects.equals(old.beforeText(), file.beforeText())
				&& Objects.equals(old.afterText(), file.afterText())) {
			return file.withMembers(old.members());
		}
		return StructureDiffComputer.withMembers(project, file);
	}

	/**
	 * A recent change of the node at a key. {@code vanished} is the user object of a node that disappeared,
	 * shown at {@code index} of its parent until the flash ends; null for a node that appeared or changed.
	 * Package-private for the test of {@link #newFlashes}.
	 */
	record Flash(FileStatus status, Object vanished, int index, long start) {
		/** 1 for the first half of the flash, then fading to 0. */
		double strength(long now) {
			double remaining = 1 - (double) (now - start) / FLASH_MILLIS;
			return Math.clamp(remaining * 2, 0, 1);
		}

		boolean expired(long now) {
			return now - start >= FLASH_MILLIS;
		}
	}

	/** User object of a node that disappeared in the last refresh. */
	private record Ghost(Object item) {
	}

	private void fadeStep() {
		long now = System.currentTimeMillis();
		boolean ghostExpired = flashes.values().stream().anyMatch(f -> f.vanished() != null && f.expired(now));
		flashes.values().removeIf(f -> f.expired(now));
		if (flashes.isEmpty()) {
			fadeTimer.stop();
		}
		if (ghostExpired) {
			show(files, false);
		}
		else {
			tree.repaint();
		}
	}

	/** @param highlightChanges flashes nodes that appeared, changed or disappeared since the previous tree */
	private void show(List<FileDiff> loadedFiles, boolean highlightChanges) {
		List<FileDiff> allFiles = shownFiles(loadedFiles);
		DefaultMutableTreeNode oldRoot = (DefaultMutableTreeNode) tree.getModel().getRoot();
		boolean firstShow = oldRoot.getChildCount() == 0;
		Set<List<String>> collapsed = Trees.collapsed(tree, oldRoot, StructureDiffPanel::keyPart, StructureDiffPanel::isFile);
		Map<List<String>, DefaultMutableTreeNode> oldNodes = new LinkedHashMap<>();
		collectNodes(oldRoot, new ArrayList<>(), oldNodes);
		DefaultMutableTreeNode lead = selectedNode();
		// after a refresh the whole selection comes back, the lead last, so Show Diff and Export still use the lead
		List<List<String>> selectedKeys = new ArrayList<>(selectedNodes().stream().map(StructureDiffPanel::keyOf).toList());
		if (lead != null) {
			List<String> leadKey = keyOf(lead);
			selectedKeys.remove(leadKey);
			selectedKeys.add(leadKey);
		}

		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		boolean groupByRoot = allFiles.stream().map(f -> vcsRoots.get(f.path())).filter(Objects::nonNull).distinct().count() > 1;
		Map<VirtualFile, DefaultMutableTreeNode> repoNodes = new LinkedHashMap<>();
		int[] total = new int[3];
		int linesAdded = 0;
		int linesRemoved = 0;
		for (FileDiff fullFile : allFiles) {
			FileDiff file = showNested || fullFile.members() == null ? fullFile
					: fullFile.withMembers(fullFile.members().stream().map(StructureDiffPanel::prune).toList());
			linesAdded += file.linesAdded();
			linesRemoved += file.linesRemoved();
			if (file.members() != null) {
				count(file.members(), total);
			}
			DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(file);
			if (file.members() != null) {
				for (MemberDiff member : file.members()) {
					fileNode.add(memberNode(member));
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
		collectNodes(root, new ArrayList<>(), newNodes);
		long now = System.currentTimeMillis();
		// a flash that ended is dropped before new ones start, so its node can flash again
		flashes.entrySet().removeIf(e -> e.getValue().expired(now) || e.getValue().vanished() != null && newNodes.containsKey(e.getKey()));
		Map<List<String>, Flash> started = Map.of();
		if (highlightChanges && baselineShown && flashChanges) {
			started = newFlashes(oldNodes, newNodes, flashes.keySet(), now);
			flashes.putAll(started);
		}
		baselineShown |= highlightChanges;
		insertGhosts(root, newNodes, repoNodes);
		((DefaultTreeModel) tree.getModel()).setRoot(root);
		Trees.expandAll(tree);
		if (!firstShow) {
			Trees.restoreCollapsed(tree, root, collapsed, StructureDiffPanel::keyPart, StructureDiffPanel::isFile);
		}
		if (flashes.isEmpty()) {
			fadeTimer.stop();
		}
		else if (!fadeTimer.isRunning()) {
			fadeTimer.start();
		}
		if (!selectedKeys.isEmpty()) {
			List<TreePath> selectedPaths = selectedKeys.stream().map(key -> find(root, key)).filter(Objects::nonNull)
					.map(node -> new TreePath(node.getPath())).toList();
			if (!selectedPaths.isEmpty()) {
				tree.setSelectionPaths(selectedPaths.toArray(TreePath[]::new));
			}
		}
		if (!started.isEmpty()) {
			revealFlash(tree, started.keySet().stream().map(key -> find(root, key)).filter(Objects::nonNull)
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

	/**
	 * Scrolls the topmost of the flashed {@code paths} into view when none of them is visible, so a flash is not missed
	 * in a tree taller than the view. Paths under a collapsed node are left alone: the user collapsed it.
	 */
	static void revealFlash(JTree tree, List<TreePath> paths) {
		Rectangle visible = tree.getVisibleRect();
		Rectangle topmost = null;
		for (TreePath path : paths) {
			Rectangle bounds = tree.isVisible(path) ? tree.getPathBounds(path) : null;
			if (bounds == null) {
				continue;
			}
			if (bounds.intersects(visible)) {
				return;
			}
			if (topmost == null || bounds.y < topmost.y) {
				topmost = bounds;
			}
		}
		if (topmost != null) {
			tree.scrollRectToVisible(topmost);
		}
	}

	/** {@code loadedFiles} without the unversioned ones while they are hidden. */
	private List<FileDiff> shownFiles(List<FileDiff> loadedFiles) {
		return showUnversioned ? loadedFiles : loadedFiles.stream().filter(f -> !unversionedPaths.contains(f.path())).toList();
	}

	/** Roots to export: the roots of the selection, else the roots of all shown files; empty when none is in a repository. */
	private List<VirtualFile> exportRoots() {
		return exportRoots(shownFiles(files), vcsRoots, selectedNodes());
	}

	/**
	 * The roots of the selected repositories and files, else every root of {@code shownFiles}, in their order; empty
	 * when no shown file is in a repository. A selection of files or repositories restricts the export to those roots.
	 */
	static List<VirtualFile> exportRoots(List<FileDiff> shownFiles, Map<String, VirtualFile> vcsRoots,
			List<DefaultMutableTreeNode> selected) {
		List<VirtualFile> roots = shownFiles.stream().map(f -> vcsRoots.get(f.path())).filter(Objects::nonNull)
				.distinct().toList();
		if (roots.size() <= 1) {
			return roots;
		}
		Set<VirtualFile> selectedRoots = new HashSet<>();
		for (DefaultMutableTreeNode node : selected) {
			if (node.getUserObject() instanceof RepoNode(VirtualFile root)) {
				selectedRoots.add(root);
			}
			else {
				FileDiff file = fileOf(node);
				VirtualFile root = file == null ? null : vcsRoots.get(file.path());
				if (root != null) {
					selectedRoots.add(root);
				}
			}
		}
		return selectedRoots.isEmpty() ? roots : roots.stream().filter(selectedRoots::contains).toList();
	}

	/** Name of {@code root} for a diff of its own, safe for a file name and unique among {@code used}. */
	private static String repoLabel(VirtualFile root, Set<String> used) {
		String base = root.getName().replaceAll("[^A-Za-z0-9._-]+", "-");
		if (base.isEmpty() || base.equals(".") || base.equals("..")) {
			base = "repo";
		}
		String label = base;
		for (int i = 2; !used.add(label); i++) {
			label = base + "-" + i;
		}
		return label;
	}

	/** {@code exported} relative to the project base directory when it is below it, else absolute, with {@code /} separators. */
	private String commandPath(Path exported) {
		Path file = exported.toAbsolutePath().normalize();
		String base = project.getBasePath();
		if (base != null) {
			Path basePath = Path.of(base).toAbsolutePath().normalize();
			if (file.startsWith(basePath)) {
				return basePath.relativize(file).toString().replace('\\', '/');
			}
		}
		return file.toString().replace('\\', '/');
	}

	/**
	 * Writes the shown changes of {@link #exportRoots()} to their reviews directories and copies the review command if
	 * enabled: the selected repository only, or a diff for each of several repositories when none is selected.
	 */
	private void exportDiff() {
		List<VirtualFile> roots = exportRoots();
		if (roots.isEmpty()) {
			return;
		}
		List<FileDiff> shown = shownFiles(files);
		Map<VirtualFile, List<Change>> changes = new LinkedHashMap<>();
		Map<VirtualFile, String> labels = new LinkedHashMap<>();
		Set<String> usedLabels = new HashSet<>();
		for (VirtualFile root : roots) {
			changes.put(root, shown.stream().filter(f -> root.equals(vcsRoots.get(f.path()))).map(FileDiff::change).toList());
			// a diff per repository needs a name of its own; one selected repository keeps the plain name
			labels.put(root, roots.size() == 1 ? "" : repoLabel(root, usedLabels));
		}
		StructureDiffSettings settings = StructureDiffSettings.getInstance();
		String reviewsDir = settings.reviewsDir();
		LocalDateTime now = LocalDateTime.now();
		new Task.Backgroundable(project, roots.size() == 1 ? "Exporting diff for review" : "Exporting diffs for review", false) {
			private final List<Path> exported = new ArrayList<>();
			private VirtualFile failed;
			private Exception error;

			@Override
			public void run(@NotNull ProgressIndicator indicator) {
				try {
					for (VirtualFile root : roots) {
						failed = root;
						exported.add(DiffExporter.export(project, root.toNioPath(), reviewsDir, changes.get(root), now, labels.get(root)));
					}
				}
				catch (VcsException | IOException e) {
					error = e;
				}
			}

			@Override
			public void onSuccess() {
				if (error != null) {
					notify(NotificationType.ERROR, "Cannot export the diff of " + failed.getName() + ": " + error.getMessage());
					return;
				}
				List<String> paths = exported.stream().map(StructureDiffPanel.this::commandPath).toList();
				String saved = paths.size() == 1
						? "Diff saved to " + paths.get(0) + "."
						: "Diffs saved to " + String.join(", ", paths) + ".";
				if (settings.copyCommandToClipboard() && !settings.skillCommand().isEmpty()) {
					CopyPasteManager.getInstance().setContents(new StringSelection(settings.skillCommand() + " " + String.join(" ", paths)));
					notify(NotificationType.INFORMATION, saved + " Review command copied — paste it into your AI agent.");
				} else {
					notify(NotificationType.INFORMATION, saved);
				}
				// DiffExporter already refreshed the files into the VFS
				FileEditorManager editors = FileEditorManager.getInstance(project);
				for (Path path : exported) {
					VirtualFile file = LocalFileSystem.getInstance().findFileByNioFile(path);
					if (file != null) {
						editors.openFile(file, true);
						// shows the patch in the diff viewer; does nothing when the IDE has it disabled (registry enable.patch.file.diff.viewer)
						editors.setSelectedEditor(file, PATCH_DIFF_EDITOR_TYPE);
					}
				}
			}

			private void notify(NotificationType type, String content) {
				NotificationGroupManager.getInstance().getNotificationGroup("Structure Diff")
						.createNotification(StringUtil.escapeXmlEntities(content), type).notify(project);
			}
		}.queue();
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
		Set<DiagnosticMembers.Level> shown = settings.diagnosticLevels();
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

	/** Shows the {@link #findingCounts} of the shown files; findings of other files are mentioned in the tooltip only. */
	private void updateFindingCounts() {
		int[] counts = findingCounts(shownFiles(files), findings);
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

	/** Findings per {@link ReviewSeverity} ordinal in {@code shownFiles}, so the sum of the file nodes' counts. */
	static int[] findingCounts(List<FileDiff> shownFiles, Map<String, List<MemberFinding>> findings) {
		int[] counts = new int[ReviewSeverity.values().length];
		for (FileDiff file : shownFiles) {
			findings.getOrDefault(file.path(), List.of()).forEach(finding -> counts[finding.severity().ordinal()]++);
		}
		return counts;
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
		return findingsOf(node, findings);
	}

	/** Diagnostics shown on a node, most severe first, placed as {@link #findingsOf(DefaultMutableTreeNode)}. */
	private List<MemberDiagnostic> diagnosticsOf(DefaultMutableTreeNode node) {
		return placedOn(node, diagnostics.byPath(), MemberDiagnostic::keyPath);
	}

	/** Whether {@code node} is a changed file the IDE has not analysed, as it has no open editor. */
	private boolean notAnalysed(DefaultMutableTreeNode node) {
		return diagnostics != Diagnostics.NONE && node.getUserObject() instanceof FileDiff file
				&& file.status() != FileStatus.DELETED && !diagnostics.analysed().contains(file.path());
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

	/** Diagnostics listed in a tooltip; the rest are counted. */
	static final int MAX_TOOLTIP_DIAGNOSTICS = 5;

	/**
	 * {@code tooltip} (plain text or HTML) followed by a section per source, the review findings, then the IDE's
	 * diagnostics, and a line when the file is not analysed; for the tree and the treemap.
	 */
	static String withSignals(String tooltip, List<MemberFinding> found, List<MemberDiagnostic> diags, boolean notAnalysed) {
		if (found.isEmpty() && diags.isEmpty() && !notAnalysed) {
			return tooltip;
		}
		StringBuilder html = new StringBuilder("<html>");
		if (tooltip != null) {
			html.append(tooltip.startsWith("<html>")
					? StringUtil.trimEnd(StringUtil.trimStart(tooltip, "<html>"), "</html>")
					: StringUtil.escapeXmlEntities(tooltip)).append("<br>");
		}
		if (!found.isEmpty()) {
			html.append("<br><b>Review</b>");
			for (MemberFinding finding : found) {
				html.append("<br>").append(StringUtil.escapeXmlEntities(finding.title()));
			}
		}
		if (!diags.isEmpty()) {
			html.append("<br><b>IDE</b>");
			for (MemberDiagnostic diag : diags.subList(0, Math.min(diags.size(), MAX_TOOLTIP_DIAGNOSTICS))) {
				html.append("<br>").append(diag.level().text).append(" · ").append(StringUtil.escapeXmlEntities(diag.message()));
			}
			if (diags.size() > MAX_TOOLTIP_DIAGNOSTICS) {
				html.append("<br>and ").append(diags.size() - MAX_TOOLTIP_DIAGNOSTICS).append(" more");
			}
		}
		if (notAnalysed) {
			html.append("<br><i>Not analysed by the IDE: double-click to open it</i>");
		}
		return html.append("</html>").toString();
	}

	/** {@code icon} followed by the icon of the most severe finding, then that of the most severe diagnostic. */
	static Icon withSignalIcons(Icon icon, List<MemberFinding> found, List<MemberDiagnostic> diags) {
		List<Icon> icons = new ArrayList<>(3);
		if (icon != null) {
			icons.add(icon);
		}
		if (!found.isEmpty()) {
			icons.add(found.getFirst().icon());
		}
		if (!diags.isEmpty()) {
			icons.add(diags.getFirst().icon());
		}
		return icons.isEmpty() ? null : icons.size() == 1 ? icons.getFirst() : new RowIcon(icons.toArray(Icon[]::new));
	}

	static List<MemberFinding> findingsOf(DefaultMutableTreeNode node, Map<String, List<MemberFinding>> findings) {
		return placedOn(node, findings, MemberFinding::keyPath);
	}

	/** Items by absolute file path shown on a node, placed as {@link #findingsOf(DefaultMutableTreeNode)} places findings. */
	static <T> List<T> placedOn(DefaultMutableTreeNode node, Map<String, List<T>> byPath, Function<T, List<String>> keyPathOf) {
		if (byPath.isEmpty()) {
			return List.of();
		}
		List<String> keyPath = new ArrayList<>();
		DefaultMutableTreeNode fileNode = node;
		while (fileNode != null && fileNode.getUserObject() instanceof MemberDiff member) {
			keyPath.addFirst(member.key());
			fileNode = (DefaultMutableTreeNode) fileNode.getParent();
		}
		if (fileNode == null || !(fileNode.getUserObject() instanceof FileDiff file)) {
			return List.of();
		}
		List<T> fileItems = byPath.getOrDefault(file.path(), List.of());
		int depth = keyPath.size();
		if (depth == 0) {
			return fileItems;
		}
		return fileItems.stream()
				.filter(item -> {
					List<String> itemPath = keyPathOf.apply(item);
					return itemPath.size() >= depth && itemPath.subList(0, depth).equals(keyPath)
							&& (itemPath.size() == depth || !hasMemberChild(node, itemPath.get(depth)));
				})
				.toList();
	}

	private static boolean hasMemberChild(DefaultMutableTreeNode node, String key) {
		for (int i = 0; i < node.getChildCount(); i++) {
			if (((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject() instanceof MemberDiff member && member.key().equals(key)) {
				return true;
			}
		}
		return false;
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

	/**
	 * Path of node keys from the root, stable across refreshes. Without the repository node above a file, as a file path
	 * is unique across repositories: the keys stay the same when the tree starts or stops grouping files by repository.
	 */
	static List<String> keyOf(DefaultMutableTreeNode node) {
		List<String> key = new ArrayList<>();
		Object[] path = node.getUserObjectPath();
		for (int i = 0; i < path.length; i++) {
			if (i == path.length - 1 || !(path[i] instanceof RepoNode)) {
				key.add(keyPart(path[i]));
			}
		}
		return key;
	}

	private static String keyPart(Object userObject) {
		if (userObject instanceof Ghost(Object item)) {
			return keyPart(item);
		}
		if (userObject instanceof RepoNode(VirtualFile root)) {
			return "repo:" + root.getPath();
		}
		if (userObject instanceof FileDiff file) {
			return file.path();
		}
		return userObject instanceof MemberDiff member ? member.key() : "";
	}

	/**
	 * Whether {@code userObject} is a file, whose path is unique across repositories: keying collapsed nodes from it
	 * keeps them collapsed when the tree starts or stops grouping files by repository.
	 */
	private static boolean isFile(Object userObject) {
		return (userObject instanceof Ghost(Object item) ? item : userObject) instanceof FileDiff;
	}

	private static FileStatus statusOf(Object userObject) {
		if (userObject instanceof FileDiff file) {
			return file.status();
		}
		return userObject instanceof MemberDiff member ? member.status() : null;
	}

	/**
	 * Whether the node whose user object is {@code after} changed since the same key held {@code before}: its status
	 * changed, or it is a member modified again, which keeps its status but gets a new {@link MemberDiff#changeId()}.
	 * A file that stays modified is not a change: every member edit would also flash the file.
	 */
	static boolean changedSince(Object before, Object after) {
		if (statusOf(before) != statusOf(after)) {
			return true;
		}
		return before instanceof MemberDiff was && after instanceof MemberDiff now && was.changeId() != now.changeId();
	}

	/** Collects nodes by key, without ghosts; the key of the invisible root is [""]. */
	static void collectNodes(DefaultMutableTreeNode node, List<String> key, Map<List<String>, DefaultMutableTreeNode> nodes) {
		List<String> nodeKey = key.isEmpty() ? List.of("") : key;
		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			if (child.getUserObject() instanceof Ghost) {
				continue;
			}
			List<String> childKey = new ArrayList<>(nodeKey);
			childKey.add(keyPart(child.getUserObject()));
			nodes.put(childKey, child);
			// see keyOf
			collectNodes(child, child.getUserObject() instanceof RepoNode ? key : childKey, nodes);
		}
	}

	/**
	 * The flashes for the nodes that appeared, changed or disappeared since {@code oldNodes}. A content change to a
	 * node whose key is in {@code running} is skipped, so its flash is not restarted while it runs, while other nodes
	 * flash independently; a status change replaces the running flash to show the new status. A removal always flashes
	 * as a {@link Ghost}, even while the removed node's own flash runs.
	 */
	static Map<List<String>, Flash> newFlashes(Map<List<String>, DefaultMutableTreeNode> oldNodes,
											   Map<List<String>, DefaultMutableTreeNode> newNodes, Set<List<String>> running, long now) {
		Map<List<String>, Flash> result = new LinkedHashMap<>();
		newNodes.forEach((key, node) -> {
			FileStatus status = statusOf(node.getUserObject());
			DefaultMutableTreeNode old = oldNodes.get(key);
			boolean statusChanged = old != null && status != statusOf(old.getUserObject());
			if (status != null && (statusChanged || !running.contains(key))
					&& (old == null || changedSince(old.getUserObject(), node.getUserObject()))
					&& (node.getUserObject() instanceof FileDiff || hadMembers(node, oldNodes))) {
				result.put(key, new Flash(status, null, -1, now));
			}
		});
		oldNodes.forEach((key, node) -> {
			FileStatus status = statusOf(node.getUserObject());
			// only the topmost vanished node; its subtree goes with it
			boolean parentRemains = key.size() == 2 || newNodes.containsKey(key.subList(0, key.size() - 1));
			if (status != null && !newNodes.containsKey(key) && parentRemains
					&& (node.getUserObject() instanceof FileDiff || hadMembers(node, newNodes))) {
				result.put(key, new Flash(status, node.getUserObject(), node.getParent().getIndex(node), now));
			}
		});
		return result;
	}

	/**
	 * Whether a member node's appearance or disappearance is a change to flash: its file had computed members in the
	 * other tree, or is a modified file new there (its first changed member). Members of an added file all come with it,
	 * and members of a file whose structure was not available yet (before revision still loading) are not changes.
	 */
	private static boolean hadMembers(DefaultMutableTreeNode member, Map<List<String>, DefaultMutableTreeNode> otherNodes) {
		DefaultMutableTreeNode fileNode = fileNode(member);
		if (fileNode == null) {
			return false;
		}
		DefaultMutableTreeNode other = otherNodes.get(keyOf(fileNode));
		if (other == null) {
			return statusOf(fileNode.getUserObject()) == FileStatus.MODIFIED;
		}
		return other.getUserObject() instanceof FileDiff file && file.members() != null;
	}

	private void insertGhosts(DefaultMutableTreeNode root, Map<List<String>, DefaultMutableTreeNode> nodes,
							  Map<VirtualFile, DefaultMutableTreeNode> repoNodes) {
		flashes.forEach((key, flash) -> {
			if (flash.vanished() == null) {
				return;
			}
			DefaultMutableTreeNode parent = key.size() > 2 ? nodes.get(key.subList(0, key.size() - 1))
					: flash.vanished() instanceof FileDiff file ? repoNodes.getOrDefault(vcsRoots.get(file.path()), root) : root;
			if (parent != null) {
				parent.insert(new DefaultMutableTreeNode(new Ghost(flash.vanished())), Math.min(flash.index(), parent.getChildCount()));
			}
		});
	}

	/** Finds the node with the given {@link #keyOf} path; the first part is the invisible root. */
	static DefaultMutableTreeNode find(DefaultMutableTreeNode root, List<String> key) {
		DefaultMutableTreeNode node = root;
		for (String part : key.subList(1, key.size())) {
			node = child(node, part);
			if (node == null) {
				return null;
			}
		}
		return node;
	}

	/** The child of {@code node} with the key part {@code part}, also one under a repository node (see {@link #keyOf}). */
	private static DefaultMutableTreeNode child(DefaultMutableTreeNode node, String part) {
		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			if (keyPart(child.getUserObject()).equals(part)) {
				return child;
			}
			if (child.getUserObject() instanceof RepoNode) {
				DefaultMutableTreeNode found = child(child, part);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/** Hides changes below non-container members (functions, components, variables); shown with "Show Nested Changes". */
	private static MemberDiff prune(MemberDiff member) {
		return member.withChildren(member.container()
				? member.children().stream().map(StructureDiffPanel::prune).toList()
				: List.of());
	}

	/** The added/modified/removed {@link #count}s, each in its status color, where not 0. */
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

	/** Counts added/modified/removed members; an added or removed subtree counts once, a container only through its children. */
	private static void count(List<MemberDiff> members, int[] counts) {
		for (MemberDiff member : members) {
			if (member.status() == FileStatus.ADDED) {
				counts[0]++;
			}
			else if (member.status() == FileStatus.DELETED) {
				counts[2]++;
			}
			else if (member.children().isEmpty()) {
				counts[1]++;
			}
			else {
				count(member.children(), counts);
			}
		}
	}

	private static DefaultMutableTreeNode memberNode(MemberDiff member) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(member);
		for (MemberDiff child : member.children()) {
			node.add(memberNode(child));
		}
		return node;
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
		return fileOf(selectedNode());
	}

	/** The file of {@code node} or of its nearest file ancestor; null when there is none. */
	private static FileDiff fileOf(DefaultMutableTreeNode node) {
		DefaultMutableTreeNode fileNode = fileNode(node);
		return fileNode == null ? null : (FileDiff) fileNode.getUserObject();
	}

	/** {@code node} if it is a file node, else its nearest file ancestor; null when there is none. */
	private static DefaultMutableTreeNode fileNode(DefaultMutableTreeNode node) {
		while (node != null && !(node.getUserObject() instanceof FileDiff)) {
			node = (DefaultMutableTreeNode) node.getParent();
		}
		return node;
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
		List<MemberDiff> path = new ArrayList<>();
		for (DefaultMutableTreeNode node = selectedNode();
			 node != null && node.getUserObject() instanceof MemberDiff pathMember; node = (DefaultMutableTreeNode) node.getParent()) {
			path.addFirst(pathMember);
		}
		return path;
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
			editors.openFile(diffEditor.getFile(), true);
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
		fadeTimer.stop();
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
			Flash flash = flashes.isEmpty() ? null : flashes.get(keyOf(node));
			// a removed node that vanished came back (removal undone): it flashes as added, not as gone
			boolean restored = ghost && flash != null && flash.status() == FileStatus.DELETED;
			Color background = flash == null ? null : flashBackground(flash, ghost, restored);
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
				setIcon(withSignalIcons(withTestMark(file.fileType().getIcon(), file.path()), found, diags));
				append(path.substring(slash + 1), attributes(restored ? FileStatus.NOT_CHANGED : file.status(), ghost && !restored, background));
				if (file.members() != null && !ghost) {
					int[] counts = new int[3];
					count(file.members(), counts);
					appendCounts(this, counts, SimpleTextAttributes.STYLE_SMALLER);
				}
				if (!ghost) {
					appendLines(this, file.linesAdded(), file.linesRemoved(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
				}
				if (!found.isEmpty()) {
					append("  ⚠" + found.size(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
				}
				if (slash > 0) {
					append("  " + path.substring(0, slash), SimpleTextAttributes.GRAYED_ATTRIBUTES);
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
				setToolTipText(withSignals(moved == null ? lines : lines == null ? moved
						: "<html>" + StringUtil.escapeXmlEntities(moved) + "<br>" + lines + "</html>", found, diags, !ghost && notAnalysed(node)));
			}
			else if (item instanceof MemberDiff member) {
				List<MemberFinding> found = ghost ? List.of() : findingsOf(node);
				List<MemberDiagnostic> diags = ghost ? List.of() : diagnosticsOf(node);
				Icon icon = member.icon();
				if (((DefaultMutableTreeNode) node.getParent()).getUserObject() instanceof FileDiff parent) {
					icon = withTestMark(icon, parent.path());
				}
				setIcon(withSignalIcons(icon, found, diags));
				String shortLabel = shorten(member.label());
				append(shortLabel, attributes(restored ? FileStatus.NOT_CHANGED : member.status(), ghost && !restored, background));
				setToolTipText(withSignals(tooltip(member), found, diags, false));
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
