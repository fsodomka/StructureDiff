package com.fsodomka.structurediff.review;

import com.fsodomka.structurediff.StatusColors;
import com.fsodomka.structurediff.Trees;
import com.fsodomka.structurediff.reason.EditReason;
import com.fsodomka.structurediff.reason.EditReasonOrder;
import com.fsodomka.structurediff.reason.EditReasonsListener;
import com.fsodomka.structurediff.reason.EditReasonsService;
import com.fsodomka.structurediff.reason.MarkerLines;
import com.intellij.icons.AllIcons;
import com.intellij.ide.CommonActionsManager;
import com.intellij.ide.DefaultTreeExpander;
import com.intellij.ide.OccurenceNavigator;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionManager;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.ui.popup.ListPopup;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.Computable;
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
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.HTMLEditorKitBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.tree.TreeUtil;
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
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Findings of the shown reviews, split into shown and dismissed, grouped by file; findings that are not highlighted are
 * greyed. Then the loaded edit reasons, by run in walkthrough order ({@link EditReasonOrder}). Next / Previous Occurrence
 * steps through findings and reasons alike.
 */
final class ReviewFindingsPanel extends SimpleToolWindowPanel implements Disposable, OccurenceNavigator {

	/** A top node: the shown findings or the dismissed ones; {@code severities} counts them per severity. */
	private record Section(String title, int count, String severities) {
	}

	/** A file node. */
	private record FileGroup(String path, @Nullable VirtualFile file, int count) {
	}

	private static final String REASONS_TITLE = "Edit Reasons";
	private static final String PENDING_TEXT = "reason pending";

	/** A run of edit reasons: an agent session or a skill run; {@code session} is null for records that name none. */
	private record ReasonRun(@Nullable String session, @Nullable String title, boolean skill, Instant earliest, Instant latest,
							 int count) {
		/** From the earliest listed reason to the latest; the end without its day when on the same day, one time when equal. */
		String when() {
			String start = EditReason.when(earliest);
			String end = EditReason.when(latest);
			if (start.equals(end)) {
				return start;
			}
			ZoneId zone = ZoneId.systemDefault();
			boolean sameDay = earliest.atZone(zone).toLocalDate().equals(latest.atZone(zone).toLocalDate());
			return start + "–" + (sameDay ? end.substring(end.lastIndexOf(' ') + 1) : end);
		}
	}

	/**
	 * The places of a run with the same reason text, which the gutter draws as one pencil; also for a single place, so
	 * every reason row starts with its time.
	 *
	 * @param places in walkthrough order; the first is the earliest
	 */
	private record ReasonGroup(String text, List<ReasonNode> places) {
		Instant earliest() {
			return places.stream().map(place -> place.anchored().reason().time()).min(Comparator.naturalOrder()).orElseThrow();
		}
	}

	/** An edit reason with a valid marker: one place of a reason, under its {@link ReasonGroup}, which shows the reason. */
	private record ReasonNode(EditReasonsService.Anchored anchored) {
		RangeMarker marker() {
			return Objects.requireNonNull(anchored.marker());
		}

		/** Identifies the reason across rebuilds. */
		String key() {
			return anchored.key();
		}

		/** The marker's document: resolving it reads the file's document, which needs read access, also on the EDT (2025.2+). */
		Document document() {
			return ApplicationManager.getApplication().runReadAction((Computable<Document>) () -> marker().getDocument());
		}
	}

	private final Project project;
	private final Content content;
	private final Tree tree = new Tree(new DefaultTreeModel(new DefaultMutableTreeNode()));
	/** The selected finding in full, so it can be read and copied without a tooltip. */
	private final JEditorPane details = new JEditorPane();
	/** Whether a shown file is in test sources, for {@link #fileIcon}; false while it is being looked up. */
	private final Map<VirtualFile, Boolean> testFiles = ContainerUtil.createConcurrentWeakMap();

	ReviewFindingsPanel(Project project, Content content) {
		super(true, true);
		this.project = project;
		this.content = content;

		tree.setRootVisible(false);
		// a long reason or finding title would shift the tree left on a click or a step, hiding the icons
		tree.setHorizontalAutoScrollingEnabled(false);
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
		DumbAwareAction dismiss = treeAction(placements -> affectedText("Dismiss", placements), AllIcons.Actions.Cancel,
				placement -> !service.isDismissed(placement.finding()),
				placements -> placements.forEach(placement -> service.dismiss(placement.finding())));
		DumbAwareAction restore = treeAction(placements -> affectedText("Restore", placements), AllIcons.Actions.Rollback,
				placement -> service.isDismissed(placement.finding()),
				placements -> placements.forEach(placement -> service.restore(placement.finding())));
		DumbAwareAction copy = treeAction(placements -> "Copy Finding", AllIcons.Actions.Copy, placement -> true,
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
		group.add(new DumbAwareAction("Reload", "Load the newest review of each repository again, also cleared ones, and the edit reasons",
				AllIcons.Actions.Refresh) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				ReviewFindingsService.getInstance(project).reload();
				// also re-reads the edit reasons from disk, which the agent writes outside the IDE
				EditReasonsService.getInstance(project).refreshAndLoad();
			}
		});
		group.add(dismiss);
		group.add(restore);
		group.add(new DumbAwareAction("Clear Findings", "Remove all review findings until the next review; edit reasons stay", AllIcons.Actions.GC) {
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
		group.add(CommonActionsManager.getInstance().createPrevOccurenceAction(this));
		group.add(CommonActionsManager.getInstance().createNextOccurenceAction(this));
		group.add(CommonActionsManager.getInstance().createExpandAllAction(expander, tree));
		group.add(CommonActionsManager.getInstance().createCollapseAllAction(expander, tree));
		ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("ReviewFindings", group, true);
		toolbar.setTargetComponent(tree);
		setToolbar(toolbar.getComponent());

		MessageBusConnection connection = project.getMessageBus().connect(this);
		connection.<ReviewFindingsListener>subscribe(ReviewFindingsListener.TOPIC, this::rebuild);
		connection.subscribe(EditReasonsListener.TOPIC, new EditReasonsListener() {
			@Override
			public void reasonsChanged() {
				rebuild();
			}

			@Override
			public void kindsChanged() {
				rebuild();
			}

			@Override
			public void reasonClicked(EditReason reason, RangeMarker at) {
				selectPlace(reason, at);
			}
		});
		rebuild();
	}

	/** Rebuilds the tree, keeping the collapsed sections, files and runs and the selected findings and reasons that are still shown. */
	private void rebuild() {
		ReviewFindingsService service = ReviewFindingsService.getInstance(project);
		boolean severalRoots = service.reviews().size() > 1;
		Set<List<String>> collapsed = Trees.collapsed(tree, (DefaultMutableTreeNode) tree.getModel().getRoot(),
				ReviewFindingsPanel::keyPart);
		Set<Finding> selected = selectedPlacements().stream().map(Placement::finding).collect(Collectors.toSet());
		Set<String> selectedReasons = selectedNodes().stream().filter(ReasonNode.class::isInstance)
				.map(node -> ((ReasonNode) node).key()).collect(Collectors.toSet());

		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		if (!service.reviews().isEmpty()) {
			root.add(section("Findings", service.placements(), severalRoots));
			root.add(section("Dismissed", service.dismissedPlacements(), severalRoots));
		}
		DefaultMutableTreeNode reasons = reasonsSection(project);
		if (reasons != null) {
			root.add(reasons);
		}
		((DefaultTreeModel) tree.getModel()).setRoot(root);
		Trees.expandAll(tree);
		Trees.restoreCollapsed(tree, root, collapsed, ReviewFindingsPanel::keyPart);
		// a finding that moved under a collapsed node, e.g. one just dismissed, is not selected, so its node stays collapsed
		List<TreePath> selection = new ArrayList<>();
		for (Enumeration<TreeNode> nodes = root.depthFirstEnumeration(); nodes.hasMoreElements(); ) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
			TreePath path = new TreePath(node.getPath());
			boolean wasSelected = node.getUserObject() instanceof Placement placement ? selected.contains(placement.finding())
					: node.getUserObject() instanceof ReasonNode reason && selectedReasons.contains(reason.key());
			if (wasSelected && tree.isVisible(path)) {
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
			TreeUtil.scrollToVisible(tree, selection.getFirst(), false);
		}
		if (target != null) {
			jumpToSource(target);
		}
	}

	/**
	 * Selects the place of {@code reason} a clicked gutter pencil stands on, {@code at}: the listed place of the same
	 * session and reason text whose range holds its start, else the reason's first place. Scrolls to it without opening
	 * it, as the editor already shows it, and without activating the tool window.
	 */
	private void selectPlace(EditReason reason, RangeMarker at) {
		if (!at.isValid()) {
			return;
		}
		Document document = ApplicationManager.getApplication().runReadAction((Computable<Document>) at::getDocument);
		DefaultMutableTreeNode first = null;
		DefaultMutableTreeNode exact = null;
		for (DefaultMutableTreeNode leaf : leaves()) {
			if (leaf.getUserObject() instanceof ReasonNode node
					&& Objects.equals(node.anchored().reason().session(), reason.session())
					&& Objects.equals(node.anchored().reason().reason(), reason.reason())) {
				first = first == null ? leaf : first;
				RangeMarker marker = node.marker();
				if (exact == null && marker.isValid() && node.document() == document
						&& marker.getStartOffset() <= at.getStartOffset() && at.getStartOffset() <= marker.getEndOffset()) {
					exact = leaf;
				}
			}
		}
		DefaultMutableTreeNode target = exact != null ? exact : first;
		if (target != null) {
			TreePath path = new TreePath(target.getPath());
			tree.setSelectionPath(path);
			TreeUtil.scrollToVisible(tree, path, false);
		}
	}

	/** Identifies a section, file or run node across rebuilds. */
	private static String keyPart(Object userObject) {
		return switch (userObject) {
			case Section section -> section.title();
			case FileGroup group -> group.path();
			case ReasonRun run -> String.valueOf(run.session());
			case ReasonGroup group -> group.text();
			case ReasonNode reason -> reason.key();
			case null, default -> "";
		};
	}

	/** Text the speed search matches: the name shown by the {@link Renderer}, with a finding's or reason's full title. */
	private static String speedSearchText(Object userObject) {
		return switch (userObject) {
			case Section section -> section.title();
			case FileGroup group -> StringUtil.getShortName(group.path(), '/');
			case Placement placement -> placement.finding().ruleId() + "  " + placement.finding().title();
			case ReasonRun run -> run.when() + "  " + runLabel(run);
			case ReasonGroup group -> EditReason.when(group.earliest()) + "  " + firstLine(group.text());
			case ReasonNode reason -> reasonLocation(reason);
			case null, default -> "";
		};
	}

	/**
	 * The Edit Reasons section: the listed reasons by run, in walkthrough order; null when edit reasons are off or none
	 * is listed. A reason is listed when it has a file and a valid marker; one still pending shows as "reason pending".
	 */
	private static @Nullable DefaultMutableTreeNode reasonsSection(Project project) {
		if (!EditReasonsService.isEnabled()) {
			return null;
		}
		EditReasonsService service = EditReasonsService.getInstance(project);
		// only what the shown snapshot changes, as the gutter's undimmed pencils: not stale reasons, nor ignored files
		List<EditReasonsService.Anchored> anchored = service.reasons().stream()
				.filter(reason -> reason.file() != null && reason.marker() != null && reason.marker().isValid()
						&& service.kind(reason) != null)
				.toList();
		List<EditReasonOrder.Run<EditReasonsService.Anchored>> runs = EditReasonOrder.runs(anchored, EditReasonsService.Anchored::reason);
		if (runs.isEmpty()) {
			return null;
		}
		DefaultMutableTreeNode section = new DefaultMutableTreeNode();
		int count = 0;
		for (EditReasonOrder.Run<EditReasonsService.Anchored> run : runs) {
			// a group at its earliest place: the items are in walkthrough order, so the first seen
			Map<String, List<EditReasonsService.Anchored>> byText = new LinkedHashMap<>();
			run.items().forEach(reason -> byText.computeIfAbsent(text(reason.reason()), key -> new ArrayList<>()).add(reason));
			DefaultMutableTreeNode runNode = new DefaultMutableTreeNode();
			int places = 0;
			for (Map.Entry<String, List<EditReasonsService.Anchored>> entry : byText.entrySet()) {
				List<ReasonNode> nodes = mergedPlaces(entry.getValue()).stream().map(ReasonNode::new).toList();
				DefaultMutableTreeNode groupNode = new DefaultMutableTreeNode(new ReasonGroup(entry.getKey(), nodes));
				nodes.forEach(node -> groupNode.add(new DefaultMutableTreeNode(node)));
				runNode.add(groupNode);
				places += nodes.size();
			}
			runNode.setUserObject(new ReasonRun(run.session(), sessionTitle(run.items().getFirst().reason()), run.skill(), run.earliest(),
					run.items().stream().map(item -> item.reason().time()).max(Comparator.naturalOrder()).orElseThrow(), places));
			section.add(runNode);
			count += places;
		}
		section.setUserObject(new Section(REASONS_TITLE, count, ""));
		return section;
	}

	/**
	 * The places of one reason as the gutter draws them: by file in the order of first appearance, then by line, with
	 * records whose lines overlap or touch (a whole block, then a line in it) merged into the first of them. Reads the
	 * markers' documents.
	 */
	private static List<EditReasonsService.Anchored> mergedPlaces(List<EditReasonsService.Anchored> places) {
		return ApplicationManager.getApplication().runReadAction((Computable<List<EditReasonsService.Anchored>>)() -> {
			Map<String, List<EditReasonsService.Anchored>> byFile = new LinkedHashMap<>();
			places.forEach(place -> byFile.computeIfAbsent(place.path(), key -> new ArrayList<>()).add(place));
			List<EditReasonsService.Anchored> merged = new ArrayList<>();
			byFile.values().forEach(inFile -> {
				MarkerLines.runs(inFile, place -> MarkerLines.of(place.marker().getDocument(), place.marker()))
						.forEach(run -> merged.add(run.getFirst()));
			});
			return merged;
		});
	}

	/** An agent session by its title and the first 8 characters of its id, the id alone without a title; a skill run by its whole name. */
	private static String runLabel(ReasonRun run) {
		String title = runTitle(run);
		return title == null ? runId(run) : title + " · " + runId(run);
	}

	/** The run's shortened session title; null for a skill run and for a session without one. */
	private static @Nullable String runTitle(ReasonRun run) {
		return run.title() == null || run.skill() ? null : StringUtil.shortenTextWithEllipsis(run.title(), 60, 0);
	}

	private static String runId(ReasonRun run) {
		String session = Objects.requireNonNullElse(run.session(), "no session");
		return run.skill() || session.length() <= 8 ? session : session.substring(0, 8);
	}

	/** The title of the session a reason belongs to; null for a skill run, which is named by its run. */
	private static @Nullable String sessionTitle(EditReason reason) {
		return reason.skill() ? null : reason.title();
	}

	/** File name and the marker's current lines, {@code :32} or {@code :32-35}; the name alone once the marker became invalid. */
	private static String reasonLocation(ReasonNode reason) {
		RangeMarker marker = reason.marker();
		return Objects.requireNonNull(reason.anchored().file()).getName()
				+ (marker.isValid() ? MarkerLines.of(reason.document(), marker).suffix() : "");
	}

	private static String reasonText(ReasonNode reason) {
		return text(reason.anchored().reason());
	}

	/** The reason's text; "reason pending" while the agent has not given one, as the tree and the gutter say. */
	private static String text(EditReason reason) {
		return reason.pending() ? PENDING_TEXT : Objects.requireNonNull(reason.reason());
	}

	private static String firstLine(String text) {
		int newline = text.indexOf('\n');
		return newline < 0 ? text : text.substring(0, newline).strip();
	}

	/**
	 * The file's icon as the Structure Diff tree shows it: the test icon in test sources, else its type's. Whether a file is
	 * in test sources is looked up once, off the EDT (the file index is a slow operation there); until then its type's icon.
	 */
	private Icon fileIcon(@Nullable VirtualFile file) {
		if (file == null || !file.isValid()) {
			return AllIcons.FileTypes.Unknown;
		}
		Boolean test = testFiles.putIfAbsent(file, false);
		if (test == null) {
			ReadAction.nonBlocking(() -> ProjectFileIndex.getInstance(project).isInTestSourceContent(file))
					.expireWith(this)
					.finishOnUiThread(ModalityState.any(), isTest -> {
						testFiles.put(file, isTest);
						if (isTest) {
							tree.repaint();
						}
					})
					.submit(AppExecutorUtil.getAppExecutorService());
			test = false;
		}
		return test ? AllIcons.Nodes.Test : file.getFileType().getIcon();
	}

	/**
	 * A run's pencil at full size, like the other row icons: purple for a skill run, the plain one for an agent session.
	 * Not {@link StatusColors#pencilMark}, which is scaled down to sit after a label.
	 */
	private static Icon pencil(boolean skill) {
		if (!skill) {
			return AllIcons.Actions.Edit;
		}
		return StatusColors.pencil(StatusColors.SKILL);
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
		Object selected = selectedNode();
		details.setText(selected instanceof Placement placement ? ReviewText.details(placement)
				: selected instanceof ReasonNode reason ? reasonDetails(reasonText(reason), List.of(reason))
				: selected instanceof ReasonGroup group ? reasonDetails(group.text(), group.places()) : "");
		details.setCaretPosition(0);
	}

	/**
	 * The reason in full, then its session: "Agent session" and its title, with the whole id below; "Skill run" and its name
	 * (its id) for a skill. The places are the tree's leaves.
	 */
	private static String reasonDetails(String text, List<ReasonNode> places) {
		EditReason first = places.getFirst().anchored().reason();
		String session = StringUtil.escapeXmlEntities(Objects.requireNonNullElse(first.session(), ""));
		String title = sessionTitle(first);
		StringBuilder html = new StringBuilder("<html><body><p>")
				.append(StringUtil.escapeXmlEntities(text).replace("\n", "<br>")).append("</p><br><p style='color:gray'>");
		if (first.skill()) {
			html.append("Skill run  ").append(session);
		} else {
			html.append("Agent session");
			if (title != null) {
				html.append("  ").append(StringUtil.escapeXmlEntities(title));
			}
			html.append("<br>").append(session);
		}
		return html.append("</p></body></html>").toString();
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

	private @Nullable Object selectedNode() {
		DefaultMutableTreeNode node = (DefaultMutableTreeNode) tree.getLastSelectedPathComponent();
		return node == null ? null : node.getUserObject();
	}

	private boolean jumpToSource() {
		return open(selectedNode());
	}

	/** Opens a finding or a reason as a double click does; false for other nodes. */
	private boolean open(@Nullable Object node) {
		return node instanceof Placement placement ? jumpToSource(placement)
				: node instanceof ReasonNode reason && jumpToSource(reason);
	}

	/** Shows the reason in the editor as its gutter pencil does; nothing when the marker or the file became invalid. */
	private boolean jumpToSource(ReasonNode reason) {
		VirtualFile file = reason.anchored().file();
		if (!reason.marker().isValid() || file == null || !file.isValid()) {
			return false;
		}
		EditReasonsService.getInstance(project).showReason(reason.anchored());
		return true;
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
		return selectedNodes().stream().filter(Placement.class::isInstance).map(Placement.class::cast).toList();
	}

	/** The user objects of the selected nodes, in tree order. */
	private List<Object> selectedNodes() {
		TreePath[] paths = tree.getSelectionPaths();
		if (paths == null) {
			return List.of();
		}
		return Arrays.stream(paths)
				.sorted(Comparator.comparingInt(tree::getRowForPath))
				.map(path -> ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject())
				.toList();
	}

	/** The findings and reasons, in tree order, collapsed groups included: what Next / Previous Occurrence steps through. */
	private List<DefaultMutableTreeNode> leaves() {
		List<DefaultMutableTreeNode> leaves = new ArrayList<>();
		for (Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration(); nodes.hasMoreElements(); ) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
			if (node.getUserObject() instanceof Placement || node.getUserObject() instanceof ReasonNode) {
				leaves.add(node);
			}
		}
		return leaves;
	}

	/**
	 * Where stepping starts in {@code leaves}: the selected leaf; with none selected, the reason under the caret of the
	 * selected editor (the latest in walkthrough order); -1 for neither.
	 */
	private int current(List<DefaultMutableTreeNode> leaves) {
		Object selected = selectedNode();
		if (selected != null) {
			for (int i = 0; i < leaves.size(); i++) {
				if (leaves.get(i).getUserObject() == selected) {
					return i;
				}
			}
		}
		Editor editor = tree.isSelectionEmpty() ? FileEditorManager.getInstance(project).getSelectedTextEditor() : null;
		if (editor == null) {
			return -1;
		}
		int caret = editor.getCaretModel().getOffset();
		for (int i = leaves.size() - 1; i >= 0; i--) {
			if (leaves.get(i).getUserObject() instanceof ReasonNode reason && reason.marker().isValid()
					&& reason.document() == editor.getDocument()
					&& reason.marker().getStartOffset() <= caret && caret <= reason.marker().getEndOffset()) {
				return i;
			}
		}
		return -1;
	}

	/** The leaf {@code step} from the current one; -1 past an end. With no current leaf, Next starts at the first and Previous at the last. */
	private int target(int step) {
		List<DefaultMutableTreeNode> leaves = leaves();
		int current = current(leaves);
		if (current < 0 && tree.getLastSelectedPathComponent() instanceof DefaultMutableTreeNode selected) {
			// a group, run or section: Next goes to the first leaf after it in tree order, its own first place included,
			// Previous to the last one before it
			return neighbour(leaves, selected, step);
		}
		int target = current < 0 ? (step > 0 ? 0 : leaves.size() - 1) : current + step;
		return target >= 0 && target < leaves.size() ? target : -1;
	}

	/** The leaf after (step &gt; 0) or before {@code node} in tree order, its own leaves counting as after; -1 when none. */
	private int neighbour(List<DefaultMutableTreeNode> leaves, DefaultMutableTreeNode node, int step) {
		int result = -1;
		boolean passed = false;
		int leaf = 0;
		for (Enumeration<TreeNode> nodes = ((DefaultMutableTreeNode) tree.getModel().getRoot()).preorderEnumeration(); nodes.hasMoreElements(); ) {
			Object next = nodes.nextElement();
			passed |= next == node;
			if (leaf < leaves.size() && next == leaves.get(leaf)) {
				if (passed && step > 0) {
					return leaf;
				}
				if (!passed) {
					result = leaf;
				}
				leaf++;
			}
		}
		return step > 0 ? -1 : result;
	}

	private @Nullable OccurenceInfo go(int step) {
		List<DefaultMutableTreeNode> leaves = leaves();
		int target = target(step);
		if (target < 0) {
			return null;
		}
		TreePath path = new TreePath(leaves.get(target).getPath());
		tree.setSelectionPath(path);
		TreeUtil.scrollToVisible(tree, path, false);
		// opened here, selecting a reason's range, which a navigatable would not; null when it could not be opened
		return open(leaves.get(target).getUserObject()) ? new OccurenceInfo(null, target + 1, leaves.size()) : null;
	}

	@Override
	public boolean hasNextOccurence() {
		return target(1) >= 0;
	}

	@Override
	public boolean hasPreviousOccurence() {
		return target(-1) >= 0;
	}

	@Override
	public @Nullable OccurenceInfo goNextOccurence() {
		return go(1);
	}

	@Override
	public @Nullable OccurenceInfo goPreviousOccurence() {
		return go(-1);
	}

	@Override
	public @NotNull String getNextOccurenceActionName() {
		return "Next Finding or Edit Reason";
	}

	@Override
	public @NotNull String getPreviousOccurenceActionName() {
		return "Previous Finding or Edit Reason";
	}

	/**
	 * "Dismiss Finding", or "Dismiss 2 Findings" when the action also affects the findings that share a fingerprint
	 * with the selected ones.
	 */
	private String affectedText(String verb, List<Placement> placements) {
		int count = ReviewFindingsService.getInstance(project).affectedCount(placements.stream().map(Placement::finding).toList());
		return ReviewActions.findingsText(verb, count);
	}

	/** An action on the selected findings that {@code enabled} accepts; {@code text} names it for those findings. */
	private DumbAwareAction treeAction(Function<List<Placement>, String> text, Icon icon, Predicate<Placement> enabled,
									   Consumer<List<Placement>> perform) {
		return new DumbAwareAction(text.apply(List.of()), null, icon) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				List<Placement> placements = selectedPlacements().stream().filter(enabled).toList();
				if (!placements.isEmpty()) {
					perform.accept(placements);
				}
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				List<Placement> placements = selectedPlacements().stream().filter(enabled).toList();
				e.getPresentation().setEnabled(!placements.isEmpty());
				e.getPresentation().setText(text.apply(placements));
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

	private final class Renderer extends ColoredTreeCellRenderer {
		@Override
		public void customizeCellRenderer(@NotNull JTree tree, Object value, boolean selected, boolean expanded, boolean leaf,
										  int row, boolean hasFocus) {
			Object node = ((DefaultMutableTreeNode) value).getUserObject();
			setToolTipText(null);
			if (node instanceof Section(String title, int count, String severities)) {
				append(title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
				append("  " + (severities.isEmpty() ? String.valueOf(count) : severities), SimpleTextAttributes.GRAYED_ATTRIBUTES);
			} else if (node instanceof FileGroup(String path, VirtualFile file, int count)) {
				setIcon(fileIcon(file));
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
				append("  " + ReviewText.lead(finding.title()),
						shown ? SimpleTextAttributes.REGULAR_ATTRIBUTES : SimpleTextAttributes.GRAYED_ATTRIBUTES);
				if (placement.line() > 0) {
					append("  :" + placement.line(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				}
				String label = ReviewText.statusLabel(placement.status());
				if (label != null) {
					append("  " + label, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES);
				}
			} else if (node instanceof ReasonRun run) {
				setIcon(pencil(run.skill()));
				append(run.when(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				String title = runTitle(run);
				if (title == null) {
					append("  " + runId(run), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
				} else {
					append("  " + title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES);
					append(" · " + runId(run), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				}
				append("  " + run.count(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				setToolTipText(run.session());
			} else if (node instanceof ReasonGroup group) {
				append(EditReason.when(group.earliest()), SimpleTextAttributes.GRAYED_ATTRIBUTES);
				append("  " + ReviewText.lead(group.text()));
				append("  " + group.places().size(), SimpleTextAttributes.GRAYED_ATTRIBUTES);
			} else if (node instanceof ReasonNode reason) {
				setIcon(fileIcon(reason.anchored().file()));
				append(reasonLocation(reason), reason.anchored().approximate() ? SimpleTextAttributes.GRAYED_ATTRIBUTES
						: SimpleTextAttributes.REGULAR_ATTRIBUTES);
			}
		}
	}
}
