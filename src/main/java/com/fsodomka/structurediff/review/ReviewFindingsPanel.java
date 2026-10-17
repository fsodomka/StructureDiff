package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.Trees;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.ColoredTreeCellRenderer;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.TreeSpeedSearch;
import com.intellij.ui.content.Content;
import com.intellij.ui.treeStructure.Tree;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;
import javax.swing.JEditorPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/** Findings of the shown reviews, split into shown and dismissed, grouped by file; findings that are not highlighted are greyed. */
final class ReviewFindingsPanel extends SimpleToolWindowPanel implements Disposable {
	private static final int MAX_TITLE_LENGTH = 160;

	/** A top node: the shown findings or the dismissed ones; {@code severities} counts them per severity. */
	private record Section(String title, int count, String severities) {
	}

	/** A file node. */
	private record FileGroup(String path, @Nullable VirtualFile file, int count) {
	}

	private final Project project;
	private final Content content;
	private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
	/** The selected finding in full, so it can be read and copied without a tooltip. */
	private final JEditorPane details = new JEditorPane();

	ReviewFindingsPanel(Project project, Content content) {
		super(true, true);
		this.project = project;
		this.content = content;

		tree.setRootVisible(false);
		tree.setCellRenderer(new Renderer());
		tree.getEmptyText().setText("No review findings");
		tree.getEmptyText().appendSecondaryText("Findings appear when the review skill writes a SARIF file to the reviews directory",
				SimpleTextAttributes.GRAYED_ATTRIBUTES, null);
		// matches the shown text, not the user object's toString; searches collapsed nodes too, expanding to the match
		TreeSpeedSearch.installOn(tree, true, path -> speedSearchText(((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject()));
		Trees.installOpenHandler(tree, this::jumpToSource);
		details.setEditable(false);
		details.setEditorKit(HTMLEditorKitBuilder.simple());
		details.setBorder(JBUI.Borders.empty(8));
		tree.addTreeSelectionListener(event -> showDetails());
		OnePixelSplitter splitter = new OnePixelSplitter(false, "ReviewFindings.details.proportion", 0.6f);
		splitter.setFirstComponent(ScrollPaneFactory.createScrollPane(tree, true));
		splitter.setSecondComponent(ScrollPaneFactory.createScrollPane(details, true));
		setContent(splitter);

		ReviewFindingsService service = ReviewFindingsService.getInstance(project);
		DumbAwareAction dismiss = treeAction("Dismiss Finding", AllIcons.Actions.Cancel,
				placement -> !service.isDismissed(placement.finding()),
				placements -> placements.forEach(placement -> service.dismiss(placement.finding())));
		DumbAwareAction restore = treeAction("Restore Finding", AllIcons.Actions.Rollback,
				placement -> service.isDismissed(placement.finding()),
				placements -> placements.forEach(placement -> service.restore(placement.finding())));
		DumbAwareAction copy = treeAction("Copy Finding", AllIcons.Actions.Copy, placement -> true,
				placements -> CopyPasteManager.getInstance().setContents(new StringSelection(placements.stream()
						.map(placement -> ReviewText.plain(placement.finding())).collect(Collectors.joining("\n\n")))));
		PopupHandler.installPopupMenu(tree, new DefaultActionGroup(dismiss, restore, copy), "ReviewFindingsPopup");

		DefaultTreeExpander expander = new DefaultTreeExpander(tree);
		DefaultActionGroup group = new DefaultActionGroup();
		group.add(new DumbAwareAction("Open Review...", "Show another review file of the repository, or a SARIF file from elsewhere", AllIcons.Actions.MenuOpen) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				chooseReview(e);
			}
		});
		group.add(new DumbAwareAction("Reload", "Load the newest review of each repository again, also cleared ones",
				AllIcons.Actions.Refresh) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				ReviewFindingsService.getInstance(project).reload();
			}
		});
		group.add(dismiss);
		group.add(restore);
		group.add(new DumbAwareAction("Clear All Findings", "Remove all findings until the next review", AllIcons.Actions.GC) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				ReviewFindingsService.getInstance(project).clearAll();
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				e.getPresentation().setEnabled(!ReviewFindingsService.getInstance(project).reviews().isEmpty());
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		});
		group.add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree));
		group.add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree));
		ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("ReviewFindings", group, true);
		toolbar.setTargetComponent(tree);
		setToolbar(toolbar.getComponent());

		project.getMessageBus().connect(this).<ReviewFindingsListener>subscribe(ReviewFindingsListener.TOPIC, this::rebuild);
		rebuild();
	}

	/** Rebuilds the tree, keeping the collapsed sections and files and the selected findings that are still shown. */
	private void rebuild() {
		ReviewFindingsService service = ReviewFindingsService.getInstance(project);
		boolean severalRoots = service.reviews().size() > 1;
		Set<List<String>> collapsed = Trees.collapsed(tree, (DefaultMutableTreeNode) tree.getModel().getRoot(),
				ReviewFindingsPanel::keyPart);
		Set<Finding> selected = selectedPlacements().stream().map(Placement::finding).collect(Collectors.toSet());

		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		if (!service.reviews().isEmpty()) {
			root.add(section("Findings", service.placements(), severalRoots));
			root.add(section("Dismissed", service.dismissedPlacements(), severalRoots));
		}
		((DefaultTreeModel) tree.getModel()).setRoot(root);
		Trees.expandAll(tree);
		Trees.restoreCollapsed(tree, root, collapsed, ReviewFindingsPanel::keyPart);
		// a finding that moved under a collapsed node, e.g. one just dismissed, is not selected, so its node stays collapsed
		List<TreePath> selection = new ArrayList<>();
		for (Enumeration<TreeNode> nodes = root.depthFirstEnumeration(); nodes.hasMoreElements(); ) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
			TreePath path = new TreePath(node.getPath());
			if (node.getUserObject() instanceof Placement placement && selected.contains(placement.finding()) && tree.isVisible(path)) {
				selection.add(path);
			}
		}
		tree.setSelectionPaths(selection.toArray(TreePath[]::new));
		// the selected findings may have a new status
		showDetails();
		content.setDisplayName(service.reviews().stream().map(review -> reviewLabel(review.file(), service.isLatest(review)))
				.collect(Collectors.joining(", ")));
	}

	/**
	 * Selects the shown {@code findings}, expanding to them, scrolls to the first, and jumps to the source of
	 * {@code jumpTo} as a double click on it does.
	 */
	void select(Set<Finding> findings, Finding jumpTo) {
		List<TreePath> selection = new ArrayList<>();
		Placement target = null;
		for (Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration(); nodes.hasMoreElements(); ) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
			if (node.getUserObject() instanceof Placement placement && findings.contains(placement.finding())) {
				selection.add(new TreePath(node.getPath()));
				if (placement.finding().equals(jumpTo)) {
					target = placement;
				}
			}
		}
		if (!selection.isEmpty()) {
			tree.setSelectionPaths(selection.toArray(TreePath[]::new));
			tree.scrollPathToVisible(selection.getFirst());
		}
		if (target != null) {
			jumpToSource(target);
		}
	}

	/** Identifies a section or file node across rebuilds. */
	private static String keyPart(Object userObject) {
		if (userObject instanceof Section section) {
			return section.title();
		}
		return userObject instanceof FileGroup group ? group.path() : "";
	}

	/** Text the speed search matches: the name shown by the {@link Renderer}, with a finding's full title. */
	private static String speedSearchText(Object userObject) {
		return switch (userObject) {
			case Section section -> section.title();
			case FileGroup group -> StringUtil.getShortName(group.path(), '/');
			case Placement placement -> placement.finding().ruleId() + "  " + placement.finding().title();
			case null, default -> "";
		};
	}

	/** A section node with {@code placements} grouped by file, files by their highest severity, then by path. */
	private static DefaultMutableTreeNode section(String title, Collection<Placement> placements, boolean severalRoots) {
		Map<String, List<Placement>> byFile = new TreeMap<>();
		for (Placement placement : placements) {
			String path = severalRoots ? placement.root().getName() + '/' + placement.finding().path() : placement.finding().path();
			byFile.computeIfAbsent(path, key -> new ArrayList<>()).add(placement);
		}
		List<Map.Entry<String, List<Placement>>> files = new ArrayList<>(byFile.entrySet());
		// stable sort: files of the same severity stay sorted by path
		files.sort(Comparator.comparingInt(entry -> highestRank(entry.getValue())));
		DefaultMutableTreeNode section = new DefaultMutableTreeNode(new Section(title, placements.size(),
				ReviewSeverity.countsText(placements.stream().map(placement -> placement.finding().severity()).toList())));
		files.forEach(entry -> {
			String path = entry.getKey();
			List<Placement> filePlacements = entry.getValue();
			filePlacements.sort(Comparator.comparingInt(Placement::line));
			DefaultMutableTreeNode fileNode = new DefaultMutableTreeNode(
					new FileGroup(path, filePlacements.getFirst().file(), filePlacements.size()));
			filePlacements.forEach(placement -> fileNode.add(new DefaultMutableTreeNode(placement)));
			section.add(fileNode);
		});
		return section;
	}

	/** The rank of the most severe finding; 0 is the most severe. */
	private static int highestRank(List<Placement> placements) {
		return placements.stream().mapToInt(placement -> placement.finding().rank()).min().orElseThrow();
	}

	private void showDetails() {
		Placement placement = selectedPlacement();
		details.setText(placement == null ? "" : ReviewText.details(placement));
		details.setCaretPosition(0);
	}

	/** A review file in the Open Review popup; {@link #OPEN_FILE} chooses one from elsewhere. */
	private record ReviewEntry(@Nullable VirtualFile root, @Nullable VirtualFile file, boolean latest) {
	}

	private static final ReviewEntry OPEN_FILE = new ReviewEntry(null, null, false);

	private void chooseReview(AnActionEvent e) {
		ReviewFindingsService service = ReviewFindingsService.getInstance(project);
		Map<VirtualFile, List<VirtualFile>> files = service.reviewFiles();
		List<ReviewEntry> entries = new ArrayList<>();
		files.forEach((root, reviews) -> reviews.forEach(file -> entries.add(new ReviewEntry(root, file, file.equals(reviews.getFirst())))));
		Set<VirtualFile> shown = service.reviews().stream().map(ReviewFindingsService.Review::file).collect(Collectors.toSet());
		entries.add(OPEN_FILE);
		boolean severalRoots = files.size() > 1;
		ListPopup popup = JBPopupFactory.getInstance().createListPopup(new BaseListPopupStep<>("Open Review", entries) {
			@Override
			public @NotNull String getTextFor(ReviewEntry entry) {
				if (entry == OPEN_FILE) {
					return "Open File...";
				}
				String label = reviewLabel(Objects.requireNonNull(entry.file()), entry.latest());
				return severalRoots ? Objects.requireNonNull(entry.root()).getName() + ": " + label : label;
			}

			@Override
			public Icon getIconFor(ReviewEntry entry) {
				if (entry == OPEN_FILE) {
					return AllIcons.Actions.MenuOpen;
				}
				return shown.contains(entry.file()) ? AllIcons.Actions.Checked : EmptyIcon.ICON_16;
			}

			@Override
			public @Nullable PopupStep<?> onChosen(ReviewEntry entry, boolean finalChoice) {
				if (entry == OPEN_FILE) {
					// after the popup closes: the chooser is modal
					return doFinalStep(() -> chooseReviewFile());
				}
				service.load(entry.file(), true, false);
				return FINAL_CHOICE;
			}
		});
		if (e.getInputEvent() != null && e.getInputEvent().getComponent() != null) {
			popup.showUnderneathOf(e.getInputEvent().getComponent());
		} else {
			popup.showInBestPositionFor(e.getDataContext());
		}
	}

	private void chooseReviewFile() {
		// Not createSingleFileDescriptor(): deprecated because it also allowed directories. Its replacement,
		// singleFile(), does not exist on the oldest supported platform (2024.2); this variant does the same.
		FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
				.withTitle("Open Review File")
				.withDescription("A SARIF file (*.sarif, *.sarif.json); its paths are relative to the repository containing it")
				.withFileFilter(file -> ReviewFindingsService.reviewName(file.getName()) != null);
		addSarifExtensionFilter(descriptor);
		VirtualFile file = FileChooser.chooseFile(descriptor, project, null);
		if (file != null && !ReviewFindingsService.getInstance(project).loadExternal(file)) {
			JBPopupFactory.getInstance().createMessage("No repository root or project directory to resolve " + file.getPresentableUrl()).showInFocusCenter();
		}
	}

	/**
	 * The native OS dialog ignores {@code withFileFilter}; it honors only the extension filter, which exists since 2024.3.
	 * Called reflectively while compiling against 2024.2. The native filter matches the last extension, so *.sarif.json shows as *.json.
	 */
	private static void addSarifExtensionFilter(FileChooserDescriptor descriptor) {
		try {
			FileChooserDescriptor.class.getMethod("withExtensionFilter", String.class, String[].class)
					.invoke(descriptor, "SARIF files", new String[]{"sarif", "json"});
		} catch (ReflectiveOperationException ignored) {
			// older platform: the native dialog shows all files, the IDE chooser still applies the file filter
		}
	}

	private static String reviewLabel(VirtualFile file, boolean latest) {
		String name = Objects.requireNonNullElse(ReviewFindingsService.reviewName(file.getName()), file.getName());
		return latest ? name + " (latest)" : name;
	}

	private @Nullable Placement selectedPlacement() {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
		return node != null && node.getUserObject() instanceof Placement placement ? placement : null;
	}

	private boolean jumpToSource() {
		Placement placement = selectedPlacement();
		return placement != null && jumpToSource(placement);
	}

	private boolean jumpToSource(Placement placement) {
		if (placement.file() == null || !placement.file().isValid()) {
			return false;
		}
		int offset = placement.offset();
		(offset >= 0
				? new OpenFileDescriptor(project, placement.file(), offset)
				: new OpenFileDescriptor(project, placement.file(), Math.max(placement.finding().line() - 1, 0), 0)).navigate(true);
		return true;
	}

	/** The selected findings, in tree order. */
	private List<Placement> selectedPlacements() {
		TreePath[] paths = tree.getSelectionPaths();
		if (paths == null) {
			return List.of();
		}
		return Arrays.stream(paths)
				.sorted(Comparator.comparingInt(tree::getRowForPath))
				.map(path -> ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject())
				.filter(Placement.class::isInstance)
				.map(Placement.class::cast)
				.toList();
	}

	/** An action on the selected findings that {@code enabled} accepts. */
	private DumbAwareAction treeAction(String text, Icon icon, Predicate<Placement> enabled, Consumer<List<Placement>> perform) {
		return new DumbAwareAction(text, null, icon) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				List<Placement> placements = selectedPlacements().stream().filter(enabled).toList();
				if (!placements.isEmpty()) {
					perform.accept(placements);
				}
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				e.getPresentation().setEnabled(selectedPlacements().stream().anyMatch(enabled));
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		};
	}

	@Override
	public void dispose() {
	}

	private static final class Renderer extends ColoredTreeCellRenderer {
		@Override
		public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf,
										  int row, boolean hasFocus) {
			Object node = ((DefaultMutableTreeNode) value).getUserObject();
			if (node instanceof Section(String title, int count, String severities)) {
				append(title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
				append("  " + (severities.isEmpty() ? String.valueOf(count) : severities), SimpleTextAttributes.GRAYED_ATTRIBUTES);
			} else if (node instanceof FileGroup(String path, VirtualFile file, int count)) {
				setIcon(file != null ? file.getFileType().getIcon() : AllIcons.FileTypes.Unknown);
				int slash = path.lastIndexOf('/');
				append(path.substring(slash + 1));
				append("  " + count, SimpleTextAttributes.GRAYED_ATTRIBUTES);
				if (slash > 0) {
					append("  " + path.substring(0, slash), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				}
			} else if (node instanceof Placement placement) {
				Finding finding = placement.finding();
				boolean shown = placement.highlighter() != null;
				setIcon(finding.severity().icon);
				append(finding.ruleId(), shown ? SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES : SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES);
				append("  " + StringUtil.shortenTextWithEllipsis(finding.title(), MAX_TITLE_LENGTH, 0),
						shown ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
				if (placement.line() > 0) {
					append("  :" + placement.line(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				}
				String label = ReviewText.statusLabel(placement.status());
				if (label != null) {
					append("  " + label, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
				}
			}
		}
	}
}
