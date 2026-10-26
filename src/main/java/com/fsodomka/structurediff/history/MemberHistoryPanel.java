package com.fsodomka.structurediff.history;

import com.fsodomka.structurediff.StructureDiffComputer;
import com.fsodomka.structurediff.StructureDiffComputer.Located;
import com.fsodomka.structurediff.StructureDiffComputer.Step;
import com.fsodomka.structurediff.history.HistoryRow.Kind;
import com.fsodomka.structurediff.history.MemberHistory.Batch;
import com.intellij.diff.DiffContentFactory;
import com.intellij.diff.DiffManager;
import com.intellij.diff.DiffRequestPanel;
import com.intellij.diff.requests.SimpleDiffRequest;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.ActionPlaces;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.util.ProgressIndicatorBase;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectCloseListener;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.AbstractVcs;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.ProjectLevelVcsManager;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.history.VcsFileRevision;
import com.intellij.openapi.vcs.history.VcsHistoryProvider;
import com.intellij.openapi.vcs.history.VcsHistorySession;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.DoubleClickListener;
import com.intellij.openapi.ui.ThreeComponentsSplitter;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.table.TableView;
import com.intellij.util.ui.ColumnInfo;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.ListTableModel;
import com.intellij.vcs.log.impl.HashImpl;
import com.intellij.vcs.log.impl.VcsLogNavigationUtil;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** The commits that changed one member, newest first, with a diff of the member in the selected commit. */
final class MemberHistoryPanel extends JPanel implements Disposable {
	private static final Logger LOG = Logger.getInstance(MemberHistoryPanel.class);
	/** Revision pairs compared per Load more. */
	private static final int BATCH = 50;
	/** {@code GitVcs.NAME}; Git4Idea is an optional dependency. */
	private static final String GIT = "Git";

	private final Project project;
	private final FilePath path;
	private final List<String> initialKeyPath;
	/** The uncommitted change of the file that {@link #initialKeyPath} is in; null when the key path is committed. */
	private final @Nullable Change localChange;
	private final ListTableModel<HistoryRow> model = new ListTableModel<>(
			column("Commit", 70, row -> shortHash(row.newer())),
			column("Date", 85, row -> row.newer().getRevisionDate() == null ? "" : String.format("%tF", row.newer().getRevisionDate())),
			column("Author", 120, row -> StringUtil.notNullize(row.newer().getAuthor())),
			column("Message", -1, row -> StringUtil.notNullize(row.newer().getCommitMessage()).lines().findFirst().orElse("")),
			column("Change", 75, row -> row.kind().text),
			column("Detail", -1, HistoryRow::detail));
	/** Initial height of the commit list; resizing the tool window changes only the diff below it. */
	private static final int LIST_HEIGHT = 180;
	private final TableView<HistoryRow> table = new TableView<>(model);
	private final JBLabel status = new JBLabel();
	private final JButton loadMore = new JButton("Load more");
	private final DiffRequestPanel diff;
	/** Contents of the revisions walked and shown in the diff, by revision; loading them may be slow. */
	private final Map<String, String> contents = MemberHistory.newCache();

	/** Read and written on the EDT. */
	private List<String> keyPath;
	private int nextIndex;
	private @Nullable List<VcsFileRevision> revisions;
	private @Nullable ProgressIndicatorBase indicator;
	private boolean disposed;

	MemberHistoryPanel(Project project, FilePath path, List<String> keyPath, @Nullable Change localChange) {
		super(new BorderLayout());
		this.project = project;
		this.path = path;
		this.initialKeyPath = List.copyOf(keyPath);
		this.localChange = localChange;
		this.keyPath = initialKeyPath;
		diff = DiffManager.getInstance().createRequestPanel(project, this, null);
		// tool window tabs are disposed after the project's line status trackers, which warn about diff editors still open
		project.getMessageBus().connect(this).subscribe(ProjectCloseListener.TOPIC, new ProjectCloseListener() {
			@Override
			public void projectClosing(@NotNull Project closing) {
				if (closing == project) {
					diff.setRequest(null);
				}
			}
		});

		JPanel header = new JPanel(new BorderLayout());
		header.setBorder(JBUI.Borders.empty(2, 6));
		header.add(new JBLabel(title()), BorderLayout.CENTER);
		header.add(loadMore, BorderLayout.EAST);
		loadMore.setEnabled(false);
		loadMore.addActionListener(e -> load());

		table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				showDiff(table.getSelectedObject());
			}
		});
		new DoubleClickListener() {
			@Override
			protected boolean onDoubleClick(@NotNull MouseEvent event) {
				return showInLog(table.getSelectedObject());
			}
		}.installOn(table);
		DefaultActionGroup popup = new DefaultActionGroup();
		popup.add(new DumbAwareAction("Show Commit in Log", null, AllIcons.Vcs.Branch) {
			@Override
			public void actionPerformed(@NotNull AnActionEvent e) {
				showInLog(table.getSelectedObject());
			}

			@Override
			public void update(@NotNull AnActionEvent e) {
				e.getPresentation().setEnabled(table.getSelectedObject() != null && logRoot() != null);
			}

			@Override
			public @NotNull ActionUpdateThread getActionUpdateThread() {
				return ActionUpdateThread.EDT;
			}
		});
		PopupHandler.installPopupMenu(table, popup, ActionPlaces.getPopupPlace("MemberHistoryPopup"));

		JPanel list = new JPanel(new BorderLayout());
		list.add(ScrollPaneFactory.createScrollPane(table), BorderLayout.CENTER);
		status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		list.add(status, BorderLayout.SOUTH);
		// the message gets most of the width left by the fixed columns
		table.getColumnModel().getColumn(3).setPreferredWidth(JBUI.scale(500));
		table.getColumnModel().getColumn(5).setPreferredWidth(JBUI.scale(200));
		ThreeComponentsSplitter splitter = new ThreeComponentsSplitter(true, true);
		splitter.setFirstComponent(list);
		splitter.setInnerComponent(diff.getComponent());
		splitter.setFirstSize(JBUI.scale(LIST_HEIGHT));
		add(header, BorderLayout.NORTH);
		add(splitter, BorderLayout.CENTER);
		load();
	}

	/** A column of {@code width} pixels, or sharing the remaining width when it is -1. */
	private static ColumnInfo<HistoryRow, String> column(String name, int width, Function<HistoryRow, String> value) {
		return new ColumnInfo<>(name) {
			@Override
			public String valueOf(HistoryRow row) {
				return value.apply(row);
			}

			@Override
			public int getWidth(JTable table) {
				return width < 0 ? -1 : JBUI.scale(width);
			}
		};
	}

	boolean shows(FilePath path, List<String> keyPath) {
		return this.path.equals(path) && initialKeyPath.equals(keyPath);
	}

	/** "History of A.round(int): Money": the enclosing members by name, the member by its key. */
	String title() {
		List<String> names = new ArrayList<>();
		for (String key : initialKeyPath.subList(0, initialKeyPath.size() - 1)) {
			names.add(StructureDiffComputer.name(key));
		}
		names.add(initialKeyPath.getLast());
		return "History of " + String.join(".", names);
	}

	/** Compares the next batch of revision pairs on a pooled thread, adding rows as they are found. */
	private void load() {
		loadMore.setEnabled(false);
		status.setText("Searching…");
		ProgressIndicatorBase progress = new ProgressIndicatorBase() {
			@Override
			public void setText2(String text) {
				super.setText2(text);
				onEdt(() -> status.setText("Searching… " + text));
			}
		};
		indicator = progress;
		List<VcsFileRevision> known = revisions;
		List<String> from = keyPath;
		int fromIndex = nextIndex;
		ApplicationManager.getApplication().executeOnPooledThread(() -> ProgressManager.getInstance().runProcess(() -> {
			try {
				List<VcsFileRevision> all = known != null ? known : revisions();
				// the walk starts at the latest commit, which has the member's key from before the local change
				List<String> start = fromIndex == 0 && localChange != null
						? MemberHistory.committedKeyPath(project, localChange, from) : from;
				Batch batch = MemberHistory.walk(project, all, path, start, fromIndex, BATCH, progress,
						row -> onEdt(() -> model.addRow(row)), contents);
				onEdt(() -> {
					revisions = all;
					keyPath = batch.keyPath();
					nextIndex = batch.nextIndex();
					loadMore.setEnabled(nextIndex >= 0);
					status.setText(nextIndex >= 0 ? nextIndex + " commits checked"
							: model.getRowCount() == 0 ? "No commit changed this member" : "");
				});
			}
			catch (ProcessCanceledException e) {
				// closed; not a failure to log below
				throw e;
			}
			catch (VcsException | RuntimeException e) {
				LOG.warn("Cannot load the history of " + path, e);
				onEdt(() -> status.setText("Cannot load history: " + e.getMessage()));
			}
		}, progress));
	}

	/** The file's revisions, newest first; empty when it has no VCS history. */
	private List<VcsFileRevision> revisions() throws VcsException {
		AbstractVcs vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(path);
		VcsHistoryProvider provider = vcs == null ? null : vcs.getVcsHistoryProvider();
		VcsHistorySession session = provider == null ? null : provider.createSessionFor(path);
		return session == null ? List.of() : List.copyOf(session.getRevisionList());
	}

	/** Shows the member's text in the row's older and newer revision; nothing for the end of the history. */
	private void showDiff(@Nullable HistoryRow row) {
		if (row == null || row.kind() == Kind.END) {
			diff.setRequest(null);
			return;
		}
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			try {
				String hash = shortHash(row.newer());
				String newer = memberText(row.newer(), row.newerKeyPath());
				String older = row.older() == null || row.kind() == Kind.ADDED ? "" : memberText(row.older(), row.olderKeyPath());
				FileType fileType = path.getFileType();
				onEdt(() -> {
					if (table.getSelectedObject() == row) {
						DiffContentFactory factory = DiffContentFactory.getInstance();
						diff.setRequest(new SimpleDiffRequest(title(), factory.create(project, older, fileType),
								factory.create(project, newer, fileType), hash + "^", hash));
					}
				});
			}
			catch (ProcessCanceledException e) {
				// closed; not a failure to log below
				throw e;
			}
			catch (Exception e) {
				LOG.warn("Cannot show the member diff of " + shortHash(row.newer()), e);
			}
		});
	}

	/** The member's text in {@code revision}; empty when it is not there. */
	private String memberText(VcsFileRevision revision, List<String> keyPath) {
		FilePath revisionPath = MemberHistory.path(revision, path);
		String fileText = StringUtil.notNullize(
				StructureDiffComputer.cachedContent(new MemberHistory.Revision(revision, revisionPath), contents));
		List<Step> steps = keyPath.stream().map(key -> new Step(key, StructureDiffComputer.name(key))).toList();
		Located located = MemberHistory.read(() -> StructureDiffComputer.locate(project, revisionPath.getName(),
				revisionPath.getFileType(), fileText, steps));
		return located == null ? "" : located.range().substring(fileText);
	}

	private boolean showInLog(@Nullable HistoryRow row) {
		VirtualFile root = row == null ? null : logRoot();
		if (root == null) {
			return false;
		}
		VcsLogNavigationUtil.jumpToRevisionAsync(project, root, HashImpl.build(row.newer().getRevisionNumber().asString()),
				MemberHistory.path(row.newer(), path));
		return true;
	}

	/** The file's VCS root when it is a Git root; other VCSs' revision numbers are no log hashes. */
	private @Nullable VirtualFile logRoot() {
		AbstractVcs vcs = ProjectLevelVcsManager.getInstance(project).getVcsFor(path);
		return vcs != null && GIT.equals(vcs.getName()) ? VcsUtil.getVcsRootFor(project, path) : null;
	}

	private static String shortHash(VcsFileRevision revision) {
		String hash = revision.getRevisionNumber().asString();
		return hash.length() > 7 ? hash.substring(0, 7) : hash;
	}

	private void onEdt(Runnable runnable) {
		ApplicationManager.getApplication().invokeLater(runnable, ModalityState.any(), o -> disposed);
	}

	@Override
	public void dispose() {
		disposed = true;
		if (indicator != null) {
			indicator.cancel();
		}
	}
}
