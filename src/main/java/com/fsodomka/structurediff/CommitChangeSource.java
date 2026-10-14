package com.fsodomka.structurediff;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.ui.ChangesTree;
import com.intellij.openapi.vcs.changes.ui.VcsTreeModelData;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.vcs.log.CommitId;
import com.intellij.vcs.log.VcsLogCommitSelection;
import com.intellij.vcs.log.VcsLogUi;
import com.intellij.vcs.log.impl.VcsLogContentProvider;
import com.intellij.vcs.log.impl.VcsLogManager;
import com.intellij.vcs.log.impl.VcsProjectLog;
import com.intellij.vcs.log.ui.MainVcsLogUi;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.Timer;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Changes of the commits selected in the main Git log tab, as its changes browser shows them; the browser merges the changes
 * of several selected commits. The log tab is created, disposed and recreated with the log, so it is looked up every second
 * while the tool window is showing.
 */
final class CommitChangeSource implements ChangeSource, Disposable {
	private static final Logger LOG = Logger.getInstance(CommitChangeSource.class);
	/** getMainUi() of the log manager class, if it has one; see {@link #mainLogUi}. */
	private static final ClassValue<Optional<Method>> GET_MAIN_UI = new ClassValue<>() {
		@Override
		protected Optional<Method> computeValue(Class<?> type) {
			try {
				return Optional.of(type.getMethod("getMainUi"));
			}
			catch (NoSuchMethodException e) {
				return Optional.empty();
			}
		}
	};
	private final Project project;
	/** Polling is skipped while it is not showing; the model listener still reports changes. */
	private final JComponent component;
	private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
	private final Timer pollTimer = new Timer(1000, e -> poll());
	/** The changes browser replaces its tree model whenever it loads the changes of a new selection. */
	private final PropertyChangeListener modelListener = e -> update();
	private MainVcsLogUi attachedUi;
	private ChangesTree attachedTree;
	private volatile List<Change> changes = List.of();
	/** Top selected commit; null when nothing is selected or the log is not open. */
	private CommitId firstCommit;
	/**
	 * Selected commit ids at the last update. Selection lists are mapped lazily from table rows; ids come from the
	 * in-memory graph, commit hashes from the log storage, so only the first hash is resolved.
	 */
	private List<Integer> commitIds = List.of();
	/** Selection that differed from {@link #commitIds} on the previous poll without the browser loading its changes. */
	private List<Integer> pendingIds;

	CommitChangeSource(Project project, JComponent component) {
		this.project = project;
		this.component = component;
		attach();
		pollTimer.start();
	}

	private void poll() {
		if (component.isShowing()) {
			attach();
		}
	}

	@Override
	public Collection<Change> changes() {
		return changes;
	}

	/** Top commit selected in the log; null when nothing is selected or the log is not open. */
	CommitId firstCommit() {
		return firstCommit;
	}

	/** Number of commits selected in the log. */
	int commitCount() {
		return commitIds.size();
	}

	@Override
	public void subscribe(Runnable onChange, Disposable parent) {
		listeners.add(onChange);
		Disposer.register(parent, () -> listeners.remove(onChange));
	}

	/** Committed changes are not edits: switching commits would flash everything. */
	@Override
	public boolean highlightChanges() {
		return false;
	}

	private void attach() {
		MainVcsLogUi ui = project.isDisposed() ? null : mainLogUi(VcsProjectLog.getInstance(project));
		if (ui == attachedUi) {
			if (ui != null) {
				checkSelection(ui.getTable().getSelection().getIds());
			}
			return;
		}
		detach();
		LOG.info(ui == null ? "Main Git log tab not found" : "Following main Git log tab " + ui.getId());
		attachedUi = ui;
		if (ui != null) {
			attachedTree = ui.getChangesBrowser().getViewer();
			attachedTree.addPropertyChangeListener(JTree.TREE_MODEL_PROPERTY, modelListener);
		}
		update();
	}

	/**
	 * Updates when the browser keeps its model although the selection changed, e.g. on clearing it. A new selection
	 * normally replaces the model once its changes load; updating before that would pair the new commits with the old
	 * changes, so a non-empty selection waits one poll for it.
	 */
	private void checkSelection(List<Integer> ids) {
		if (ids.equals(commitIds)) {
			pendingIds = null;
		}
		else if (ids.isEmpty() || ids.equals(pendingIds)) {
			update();
		}
		else {
			pendingIds = List.copyOf(ids);
		}
	}

	/**
	 * The main Git log tab. In 2026.2, VcsProjectLog.getMainLogUi() (deprecated, and getMainUi(), which delegates to it) is
	 * read once when the project log is created, before the log manager exists, so it stays null, and
	 * {@link VcsLogManager#getLogUis()} lists only the additional tabs. The log manager's getMainUi() is current, but its
	 * class IdeVcsLogManager does not exist in 2024.2, where getLogUis() lists the main tab too, under its fixed id.
	 */
	private static MainVcsLogUi mainLogUi(VcsProjectLog log) {
		VcsLogManager manager = log.getLogManager();
		if (manager == null) {
			return null;
		}
		Method getMainUi = GET_MAIN_UI.get(manager.getClass()).orElse(null);
		if (getMainUi == null) {
			for (VcsLogUi ui : manager.getLogUis()) {
				if (ui instanceof MainVcsLogUi main && VcsLogContentProvider.MAIN_LOG_ID.equals(ui.getId())) {
					return main;
				}
			}
			return null;
		}
		try {
			return getMainUi.invoke(manager) instanceof MainVcsLogUi ui ? ui : null;
		}
		catch (ReflectiveOperationException e) {
			LOG.warn("Cannot get the main Git log tab", e);
			return null;
		}
	}

	private void detach() {
		if (attachedTree != null) {
			attachedTree.removePropertyChangeListener(JTree.TREE_MODEL_PROPERTY, modelListener);
		}
		attachedTree = null;
		attachedUi = null;
	}

	private void update() {
		VcsLogCommitSelection selection = attachedUi == null ? null : attachedUi.getTable().getSelection();
		List<Integer> ids = selection == null ? List.of() : List.copyOf(selection.getIds());
		pendingIds = null;
		// a merge commit lists a file once per parent; keep the first
		Map<String, Change> byPath = new LinkedHashMap<>();
		if (attachedTree != null) {
			for (Change change : VcsTreeModelData.all(attachedTree).userObjects(Change.class)) {
				byPath.putIfAbsent(ChangesUtil.getFilePath(change).getPath(), change);
			}
		}
		List<Change> newChanges = List.copyOf(byPath.values());
		// the browser also resets its model for the same selection, e.g. on regrouping
		if (ids.equals(commitIds) && newChanges.equals(changes)) {
			return;
		}
		commitIds = ids;
		firstCommit = ids.isEmpty() ? null : selection.getCommits().get(0);
		changes = newChanges;
		listeners.forEach(Runnable::run);
	}

	@Override
	public void dispose() {
		pollTimer.stop();
		detach();
	}
}
