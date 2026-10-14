package com.fsodomka.structurediff;

import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.contents.DiffContent;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.diff.util.DiffUserDataKeys;
import com.intellij.diff.util.Side;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.ui.PopupHandler;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.actionSystem.ToggleAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangeListListener;
import com.intellij.openapi.vcs.changes.ChangeListManager;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.DoubleClickListener;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleColoredComponent;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeUIHelper;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.Alarm;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import javax.swing.Timer;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tree of changed files and their added/modified/removed members. Refreshes on change list updates.
 */
final class StructureDiffPanel extends SimpleToolWindowPanel implements Disposable {
	private final Project project;
	private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
	private final Alarm alarm = new Alarm(Alarm.ThreadToUse.POOLED_THREAD, this);
	private static final long FLASH_MILLIS = 4000;
	/**
	 * Recent changes by node key. Nodes that appeared or changed status get a background that fades out;
	 * nodes that disappeared stay in the tree as {@link Ghost}s until their flash ends.
	 */
	private final Map<List<String>, Flash> flashes = new HashMap<>();
	/**
	 * Whether the change list finished its first update after VCS initialization. Until then, files and members
	 * appear because the IDE is loading, not because they changed.
	 */
	private volatile boolean vcsReady;
	/** Whether a refresh result after {@link #vcsReady} was shown; it is the initial state, not changes to flash, even if a later one starts from an empty tree. */
	private boolean baselineShown;
	private final Timer fadeTimer = new Timer(50, e -> fadeStep());
	private static final String SHOW_NESTED_KEY = "structureDiff.showNested";
	private static final String SHOW_UNVERSIONED_KEY = "structureDiff.showUnversioned";
	private static final String FLASH_CHANGES_KEY = "structureDiff.flashChanges";

	private final AtomicInteger generation = new AtomicInteger();
	private final SimpleColoredComponent summary = new SimpleColoredComponent();
	private volatile List<FileDiff> files = List.of();
	/** VCS root of each file path; files are grouped by root when there is more than one. */
	private Map<String, VirtualFile> vcsRoots = Map.of();
	/** Contents of committed before-revisions, which never change; loading them is VCS I/O. */
	private final Map<String, String> revisionCache = new ConcurrentHashMap<>();
	private boolean showNested = PropertiesComponent.getInstance().getBoolean(SHOW_NESTED_KEY, false);
	private boolean showUnversioned = PropertiesComponent.getInstance().getBoolean(SHOW_UNVERSIONED_KEY, true);
	private boolean flashChanges = PropertiesComponent.getInstance().getBoolean(FLASH_CHANGES_KEY, true);
	/** Paths of the unversioned files in {@link #files}; they are loaded even when hidden, so the toggle needs no refresh. */
	private volatile Set<String> unversionedPaths = Set.of();

	StructureDiffPanel(Project project) {
		super(true, true);
		this.project = project;

		tree.setRootVisible(false);
		tree.setCellRenderer(new Renderer());
		ToolTipManager.sharedInstance().registerComponent(tree);
		TreeUIHelper.getInstance().installTreeSpeedSearch(tree);
		// double-click and Enter edit the file like in the Project view; middle click shows the diff
		new DoubleClickListener() {
			@Override
			protected boolean onDoubleClick(@NotNull MouseEvent event) {
				return jumpToSource();
			}
		}.installOn(tree);
		tree.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				TreePath path = tree.getPathForLocation(e.getX(), e.getY());
				if (SwingUtilities.isMiddleMouseButton(e) && path != null) {
					tree.setSelectionPath(path);
					openSelected();
					e.consume();
				}
			}
		});
		tree.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ENTER && jumpToSource()) {
					e.consume();
				}
			}
		});
		setContent(ScrollPaneFactory.createScrollPane(tree));
		installPopupMenu();

		DefaultTreeExpander expander = new DefaultTreeExpander(tree);
		DefaultActionGroup group = new DefaultActionGroup();
		group.add(new DumbAwareAction("Refresh", null, AllIcons.Actions.Refresh) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				scheduleRefresh();
			}
		});
		group.add(new ToggleAction("Show Nested Changes",
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

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		});
		group.add(new ToggleAction("Show Unversioned Files", null, AllIcons.Vcs.ShowUnversionedFiles) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return showUnversioned;
			}

			@Override
			public void setSelected(@NotNull AnActionEvent e, boolean state) {
				showUnversioned = state;
				PropertiesComponent.getInstance().setValue(SHOW_UNVERSIONED_KEY, state, true);
				show(files, false);
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		});
		group.add(new ToggleAction("Flash Changes",
				"Briefly highlight members and files that appeared, changed status or disappeared", AllIcons.Actions.Highlighting) {
			@Override
			public boolean isSelected(@NotNull AnActionEvent e) {
				return flashChanges;
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
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		});
		group.add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree));
		group.add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree));
		ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("StructureDiff", group, true);
		toolbar.setTargetComponent(tree);
		summary.setBorder(JBUI.Borders.empty(2, 8, 4, 8));
		JPanel top = new JPanel(new BorderLayout());
		top.add(toolbar.getComponent(), BorderLayout.NORTH);
		top.add(summary, BorderLayout.SOUTH);
		setToolbar(top);

		project.getMessageBus().connect(this).subscribe(ChangeListListener.TOPIC, new ChangeListListener() {
			@Override
			public void changeListUpdateDone() {
				scheduleRefresh();
			}
		});
		ProjectLevelVcsManager.getInstance(project).runAfterInitialization(
				() -> ChangeListManager.getInstance(project).invokeAfterUpdate(false, () -> {
					vcsReady = true;
					scheduleRefresh();
				}));
		scheduleRefresh();
	}

	/** Show Diff (Ctrl+D), Jump to Source (F4) and Copy Path, with the shortcuts of the matching IDE actions. */
	private void installPopupMenu() {
		DefaultActionGroup popup = new DefaultActionGroup();
		popup.add(treeAction("Show Diff", AllIcons.Actions.Diff, "Diff.ShowDiff", () -> selectedFile() != null, this::openSelected));
		popup.add(treeAction("Jump to Source", AllIcons.Actions.EditSource, IdeActions.ACTION_EDIT_SOURCE,
				() -> selectedWorkingFile() != null, this::jumpToSource));
		popup.add(treeAction("Copy Path", AllIcons.Actions.Copy, null, () -> selectedFile() != null, () -> {
			FileDiff file = selectedFile();
			if (file == null) {
				return false;
			}
			CopyPasteManager.getInstance().setContents(new StringSelection(FileUtil.toSystemDependentName(file.path())));
			return true;
		}));
		PopupHandler.installPopupMenu(tree, popup, "StructureDiffPopup");
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
		}
		return action;
	}

	private void scheduleRefresh() {
		alarm.cancelAllRequests();
		alarm.addRequest(this::refresh, 300);
	}

	/** Runs on a pooled thread. */
	private void refresh() {
		int current = generation.incrementAndGet();
		boolean ready = vcsReady;
		ChangeListManager changeListManager = ChangeListManager.getInstance(project);
		ProjectFileIndex fileIndex = ProjectFileIndex.getInstance(project);
		// unversioned project files, except those directly in a content root, which skips scratch files like out.json in the project root
		List<FilePath> unversioned = ApplicationManager.getApplication().runReadAction((Computable<List<FilePath>>) () -> changeListManager.getUnversionedFilesPaths().stream()
				.filter(path -> {
					VirtualFile file = path.getVirtualFile();
					return file != null && fileIndex.isInContent(file)
							&& !Objects.equals(file.getParent(), fileIndex.getContentRootForFile(file));
				})
				.toList());
		Collection<Change> changes = changeListManager.getAllChanges();
		List<FileDiff> loaded = Stream.concat(
						changes.stream().map(change -> StructureDiffComputer.load(change, revisionCache)),
						unversioned.stream().map(StructureDiffComputer::loadUnversioned))
				.sorted(Comparator.comparing(FileDiff::path, String.CASE_INSENSITIVE_ORDER))
				.toList();
		Set<String> usedRevisions = changes.stream().map(change -> StructureDiffComputer.revisionKey(change.getBeforeRevision()))
				.filter(Objects::nonNull).collect(Collectors.toSet());
		revisionCache.keySet().retainAll(usedRevisions);
		Map<String, FileDiff> previous = files.stream().filter(f -> f.members() != null)
				.collect(Collectors.toMap(FileDiff::path, f -> f, (a, b) -> a));
		// the read action restarts after every write action (each keystroke); keep the files already parsed
		AtomicReferenceArray<FileDiff> computed = new AtomicReferenceArray<>(loaded.size());
		ReadAction.nonBlocking(() -> {
					ProjectLevelVcsManager vcsManager = ProjectLevelVcsManager.getInstance(project);
					Map<String, VirtualFile> roots = new HashMap<>();
					for (FileDiff file : loaded) {
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
					return new Snapshot(withMembers, roots, unversioned.stream().map(FilePath::getPath).collect(Collectors.toSet()));
				})
				.inSmartMode(project)
				.expireWith(this)
				// cancels the computation of an older refresh, whose result would be discarded anyway
				.coalesceBy(this)
				.finishOnUiThread(ModalityState.any(), snapshot -> {
					if (current == generation.get()) {
						files = snapshot.files();
						vcsRoots = snapshot.roots();
						unversionedPaths = snapshot.unversioned();
						show(snapshot.files(), ready);
					}
				})
				.submit(AppExecutorUtil.getAppExecutorService());
	}

	private record Snapshot(List<FileDiff> files, Map<String, VirtualFile> roots, Set<String> unversioned) {
	}

	/** Group node of one VCS root. */
	private record RepoNode(VirtualFile root) {
	}

	/** Reuses the members of a file whose contents did not change since the last refresh; parsing is the costly part. */
	private FileDiff withMembers(Map<String, FileDiff> previous, FileDiff file) {
		FileDiff old = previous.get(file.path());
		if (old != null && old.status() == file.status() && Objects.equals(old.beforeText(), file.beforeText())
				&& Objects.equals(old.afterText(), file.afterText())) {
			return file.withMembers(old.members());
		}
		return StructureDiffComputer.withMembers(project, file);
	}

	/**
	 * A recent change of the node at a key. {@code vanished} is the user object of a node that disappeared,
	 * shown at {@code index} of its parent until the flash ends; null for a node that appeared or changed status.
	 */
	private record Flash(FileStatus status, Object vanished, int index, long start) {
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

	/** @param highlightChanges flashes nodes that appeared, changed status or disappeared since the previous tree */
	private void show(List<FileDiff> loadedFiles, boolean highlightChanges) {
		List<FileDiff> allFiles = showUnversioned ? loadedFiles
				: loadedFiles.stream().filter(f -> !unversionedPaths.contains(f.path())).toList();
		DefaultMutableTreeNode oldRoot = (DefaultMutableTreeNode) tree.getModel().getRoot();
		boolean firstShow = oldRoot.getChildCount() == 0;
		Set<List<String>> collapsed = new HashSet<>();
		collectCollapsed(oldRoot, new ArrayList<>(), collapsed);
		Map<List<String>, DefaultMutableTreeNode> oldNodes = new LinkedHashMap<>();
		collectNodes(oldRoot, new ArrayList<>(), oldNodes);
		DefaultMutableTreeNode selected = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
		List<String> selectedKey = selected == null ? null : keyOf(selected);

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
		if (highlightChanges && baselineShown && flashChanges) {
			addFlashes(oldNodes, newNodes, now);
		}
		baselineShown |= highlightChanges;
		flashes.entrySet().removeIf(e -> e.getValue().expired(now) || e.getValue().vanished() != null && newNodes.containsKey(e.getKey()));
		insertGhosts(root, newNodes);
		((DefaultTreeModel) tree.getModel()).setRoot(root);
		TreeUtil.expandAll(tree);
		if (!firstShow) {
			restoreCollapsed(root, new ArrayList<>(), collapsed);
		}
		if (flashes.isEmpty()) {
			fadeTimer.stop();
		}
		else if (!fadeTimer.isRunning()) {
			fadeTimer.start();
		}
		if (selectedKey != null) {
			DefaultMutableTreeNode node = find(root, selectedKey);
			if (node != null) {
				tree.setSelectionPath(new TreePath(node.getPath()));
			}
		}

		summary.clear();
		// clear() keeps the tooltip; appendLines sets it only when there are changed lines
		summary.setToolTipText(null);
		summary.append(allFiles.size() + (allFiles.size() == 1 ? " file" : " files"), SimpleTextAttributes.REGULAR_ATTRIBUTES);
		appendCount(summary, "+", total[0], FileStatus.ADDED, SimpleTextAttributes.STYLE_BOLD);
		appendCount(summary, "~", total[1], FileStatus.MODIFIED, SimpleTextAttributes.STYLE_BOLD);
		appendCount(summary, "−", total[2], FileStatus.DELETED, SimpleTextAttributes.STYLE_BOLD);
		appendLines(summary, linesAdded, linesRemoved, SimpleTextAttributes.GRAYED_ATTRIBUTES);
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

	/** Path of node keys from the root, stable across refreshes. */
	private static List<String> keyOf(DefaultMutableTreeNode node) {
		List<String> key = new ArrayList<>();
		for (Object userObject : node.getUserObjectPath()) {
			key.add(keyPart(userObject));
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

	private void collectCollapsed(DefaultMutableTreeNode node, List<String> key, Set<List<String>> collapsed) {
		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			if (child.isLeaf()) {
				continue;
			}
			List<String> childKey = new ArrayList<>(key);
			childKey.add(keyPart(child.getUserObject()));
			if (!tree.isExpanded(new TreePath(child.getPath()))) {
				collapsed.add(childKey);
			}
			collectCollapsed(child, childKey, collapsed);
		}
	}

	private static FileStatus statusOf(Object userObject) {
		if (userObject instanceof FileDiff file) {
			return file.status();
		}
		return userObject instanceof MemberDiff member ? member.status() : null;
	}

	/** Collects nodes by key, without ghosts; the key of the invisible root is [""]. */
	private static void collectNodes(DefaultMutableTreeNode node, List<String> key, Map<List<String>, DefaultMutableTreeNode> nodes) {
		List<String> nodeKey = key.isEmpty() ? List.of("") : key;
		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			if (child.getUserObject() instanceof Ghost) {
				continue;
			}
			List<String> childKey = new ArrayList<>(nodeKey);
			childKey.add(keyPart(child.getUserObject()));
			nodes.put(childKey, child);
			collectNodes(child, childKey, nodes);
		}
	}

	private void addFlashes(Map<List<String>, DefaultMutableTreeNode> oldNodes, Map<List<String>, DefaultMutableTreeNode> newNodes,
							long now) {
		newNodes.forEach((key, node) -> {
			FileStatus status = statusOf(node.getUserObject());
			DefaultMutableTreeNode old = oldNodes.get(key);
			if (status != null && (old == null || status != statusOf(old.getUserObject()))
					&& (node.getUserObject() instanceof FileDiff || hadMembers(node, oldNodes))) {
				flashes.put(key, new Flash(status, null, -1, now));
			}
		});
		oldNodes.forEach((key, node) -> {
			FileStatus status = statusOf(node.getUserObject());
			// only the topmost vanished node; its subtree goes with it
			boolean parentRemains = key.size() == 2 || newNodes.containsKey(key.subList(0, key.size() - 1));
			if (status != null && !newNodes.containsKey(key) && parentRemains
					&& (node.getUserObject() instanceof FileDiff || hadMembers(node, newNodes))) {
				flashes.put(key, new Flash(status, node.getUserObject(), node.getParent().getIndex(node), now));
			}
		});
	}

	/**
	 * Whether a member node's appearance or disappearance is a change to flash: its file had computed members in the
	 * other tree, or is a modified file new there (its first changed member). Members of an added file all come with it,
	 * and members of a file whose structure was not available yet (before revision still loading) are not changes.
	 */
	private static boolean hadMembers(DefaultMutableTreeNode member, Map<List<String>, DefaultMutableTreeNode> otherNodes) {
		DefaultMutableTreeNode fileNode = member;
		while (fileNode != null && !(fileNode.getUserObject() instanceof FileDiff)) {
			fileNode = (DefaultMutableTreeNode) fileNode.getParent();
		}
		if (fileNode == null) {
			return false;
		}
		DefaultMutableTreeNode other = otherNodes.get(keyOf(fileNode));
		if (other == null) {
			return statusOf(fileNode.getUserObject()) == FileStatus.MODIFIED;
		}
		return other.getUserObject() instanceof FileDiff file && file.members() != null;
	}

	private void insertGhosts(DefaultMutableTreeNode root, Map<List<String>, DefaultMutableTreeNode> nodes) {
		flashes.forEach((key, flash) -> {
			if (flash.vanished() == null) {
				return;
			}
			DefaultMutableTreeNode parent = key.size() == 2 ? root : nodes.get(key.subList(0, key.size() - 1));
			if (parent != null) {
				parent.insert(new DefaultMutableTreeNode(new Ghost(flash.vanished())), Math.min(flash.index(), parent.getChildCount()));
			}
		});
	}

	/** Collapses children before parents, since collapsing a path expands its ancestors. */
	private void restoreCollapsed(DefaultMutableTreeNode node, List<String> key, Set<List<String>> collapsed) {
		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			if (child.isLeaf()) {
				continue;
			}
			List<String> childKey = new ArrayList<>(key);
			childKey.add(keyPart(child.getUserObject()));
			restoreCollapsed(child, childKey, collapsed);
			if (collapsed.contains(childKey)) {
				tree.collapsePath(new TreePath(child.getPath()));
			}
		}
	}

	/** Finds the node with the given {@link #keyOf} path; the first part is the invisible root. */
	private static DefaultMutableTreeNode find(DefaultMutableTreeNode root, List<String> key) {
		DefaultMutableTreeNode node = root;
		for (String part : key.subList(1, key.size())) {
			DefaultMutableTreeNode next = null;
			for (int i = 0; i < node.getChildCount() && next == null; i++) {
				DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
				if (keyPart(child.getUserObject()).equals(part)) {
					next = child;
				}
			}
			if (next == null) {
				return null;
			}
			node = next;
		}
		return node;
	}

	/** Hides changes below non-container members (functions, components, variables); shown with "Show Nested Changes". */
	private static MemberDiff prune(MemberDiff member) {
		return member.withChildren(member.container()
				? member.children().stream().map(StructureDiffPanel::prune).toList()
				: List.of());
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

	/** File of the selected node or of its enclosing file node; null for a repository node or no selection. */
	private FileDiff selectedFile() {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
		while (node != null && !(node.getUserObject() instanceof FileDiff)) {
			node = (DefaultMutableTreeNode) node.getParent();
		}
		return node == null ? null : (FileDiff) node.getUserObject();
	}

	private MemberDiff selectedMember() {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
		return node != null && node.getUserObject() instanceof MemberDiff member ? member : null;
	}

	/** Working copy of the selected file; null when it was deleted. */
	private VirtualFile selectedWorkingFile() {
		FileDiff file = selectedFile();
		return file != null && file.change().getAfterRevision() instanceof CurrentContentRevision current
				? current.getVirtualFile() : null;
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
		int offset = member == null ? 0 : member.afterOffset();
		new OpenFileDescriptor(project, workingFile, offset).navigate(true);
		return true;
	}

	private boolean openSelected() {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
		FileDiff file = selectedFile();
		if (file == null) {
			return false;
		}
		if (file.fileType().isBinary()) {
			// no text was loaded for binary files; open the working copy (e.g. image viewer) instead
			return jumpToSource();
		}
		DiffContentFactory factory = DiffContentFactory.getInstance();
		DiffContent before = factory.create(project, StringUtil.notNullize(file.beforeText()), file.fileType());
		VirtualFile afterFile = file.change().getAfterRevision() instanceof CurrentContentRevision current
				? current.getVirtualFile() : null;
		DiffContent after = afterFile != null
				? factory.create(project, afterFile)
				: factory.create(project, StringUtil.notNullize(file.afterText()), file.fileType());
		SimpleDiffRequest request = new SimpleDiffRequest(file.path(), before, after, "Before", "After");
		if (node.getUserObject() instanceof MemberDiff member) {
			if (member.afterOffset() >= 0) {
				request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE,
						Pair.create(Side.RIGHT, StringUtil.offsetToLineNumber(file.afterText(), member.afterOffset())));
			}
			else if (member.beforeOffset() >= 0) {
				request.putUserData(DiffUserDataKeys.SCROLL_TO_LINE,
						Pair.create(Side.LEFT, StringUtil.offsetToLineNumber(file.beforeText(), member.beforeOffset())));
			}
		}
		DiffManager.getInstance().showDiff(project, request);
		return true;
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
				String path = file.path().startsWith(basePath + "/") ? file.path().substring(basePath.length() + 1) : file.path();
				int slash = path.lastIndexOf('/');
				setIcon(file.fileType().getIcon());
				append(path.substring(slash + 1), attributes(restored ? FileStatus.NOT_CHANGED : file.status(), ghost && !restored, background));
				if (file.members() != null && !ghost) {
					int[] counts = new int[3];
					count(file.members(), counts);
					appendCount(this, "+", counts[0], FileStatus.ADDED, SimpleTextAttributes.STYLE_SMALLER);
					appendCount(this, "~", counts[1], FileStatus.MODIFIED, SimpleTextAttributes.STYLE_SMALLER);
					appendCount(this, "−", counts[2], FileStatus.DELETED, SimpleTextAttributes.STYLE_SMALLER);
				}
				if (!ghost) {
					appendLines(this, file.linesAdded(), file.linesRemoved(), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES);
				}
				if (slash > 0) {
					append("  " + path.substring(0, slash), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				}
				Change change = file.change();
				String beforePath = change.getBeforeRevision() == null ? null : change.getBeforeRevision().getFile().getPath();
				if ((change.isMoved() || change.isRenamed()) && beforePath != null) {
					// a rename shows the old name, a move the old path relative to the root
					String relative = beforePath.startsWith(basePath + "/") ? beforePath.substring(basePath.length() + 1) : beforePath;
					String shown = change.isMoved() ? relative : StringUtil.getShortName(beforePath, '/');
					append("  ← " + shown, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
				}
				String moved = !(change.isMoved() || change.isRenamed()) || beforePath == null ? null
						: (change.isMoved() ? "Moved from " : "Renamed from ") + FileUtil.toSystemDependentName(beforePath);
				String lines = file.linesAdded() + file.linesRemoved() == 0 ? null : linesText(file.linesAdded(), file.linesRemoved());
				setToolTipText(moved == null ? lines : lines == null ? moved
						: "<html>" + StringUtil.escapeXmlEntities(moved) + "<br>" + lines + "</html>");
			}
			else if (item instanceof MemberDiff member) {
				setIcon(member.icon());
				String shortLabel = shorten(member.label());
				append(shortLabel, attributes(restored ? FileStatus.NOT_CHANGED : member.status(), ghost && !restored, background));
				setToolTipText(tooltip(member));
			}
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
