package com.fsodomka.structurediff;

import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.lang.LanguageStructureViewBuilder;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.ChangesUtil;
import com.intellij.openapi.vcs.changes.ContentRevision;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes member-level differences by comparing the Structure view trees of the before and after file versions.
 */
final class StructureDiffComputer {
	private static final Logger LOG = Logger.getInstance(StructureDiffComputer.class);

	private StructureDiffComputer() {
	}

	/**
	 * Structure element of one file version. {@code text} is the element source, used for the line diff; the label when
	 * there is no PSI element. {@code normalizedText} is compared to detect changes, see {@link #normalizedText}.
	 */
	private record Member(String key, String label, Icon icon, PsiElement element, String text, String normalizedText,
						  int offset, boolean container, List<Member> children) {
	}

	/**
	 * PSI types of code members whose nested structure (JSX, object literals, local functions) is hidden unless
	 * "Show Nested Changes" is on. Matched by name so the plugin needs no compile dependency on language plugins.
	 * Everything else (classes, namespaces, YAML/JSON/Markdown nodes) is a container whose children are always shown.
	 */
	private static final Set<String> LEAF_TYPES = Set.of("PsiMethod", "PsiField", "PsiClassInitializer",
			"JSFunction", "JSVariable", "KtNamedFunction", "KtProperty");
	private static final ClassValue<Boolean> IS_CONTAINER = new ClassValue<>() {
		@Override
		protected Boolean computeValue(@NotNull Class<?> type) {
			return !isLeafType(type);
		}
	};
	/** Larger files are listed without members; parsing them would stall the refresh. */
	private static final int MAX_STRUCTURE_LENGTH = 1_000_000;

	private static boolean isLeafType(Class<?> type) {
		if (type == null) {
			return false;
		}
		if (LEAF_TYPES.contains(type.getSimpleName())) {
			return true;
		}
		for (Class<?> i : type.getInterfaces()) {
			if (isLeafType(i)) {
				return true;
			}
		}
		return isLeafType(type.getSuperclass());
	}

	/**
	 * Loads revision contents; may do VCS I/O, so call outside a read action.
	 * Committed revisions are immutable, so their contents are taken from and added to {@code revisionCache}.
	 */
	static FileDiff load(Change change, Map<String, String> revisionCache) {
		return load(change, change.getFileStatus(), revisionCache);
	}

	/** Unversioned file, shown as added. */
	static FileDiff loadUnversioned(FilePath path) {
		return load(new Change(null, CurrentContentRevision.create(path)), FileStatus.ADDED, Map.of());
	}

	private static FileDiff load(Change change, FileStatus status, Map<String, String> revisionCache) {
		ContentRevision before = change.getBeforeRevision();
		ContentRevision after = change.getAfterRevision();
		FilePath path = ChangesUtil.getFilePath(change);
		FileType fileType = path.getFileType();
		if (fileType.isBinary()) {
			// no structure; decoding binary revisions to strings would only waste memory and VCS I/O on every refresh
			return new FileDiff(change, path.getPath(), fileType, status, null, null, 0, 0, null);
		}
		String beforeText = cachedContent(before, revisionCache);
		String afterText = cachedContent(after, revisionCache);
		int[] lines = changedLines(StringUtil.notNullize(beforeText), StringUtil.notNullize(afterText));
		return new FileDiff(change, path.getPath(), fileType, status, beforeText, afterText, lines[0], lines[1], null);
	}

	/** Cache key of a committed revision; null for the working copy, whose content can change. */
	static String revisionKey(ContentRevision revision) {
		if (revision == null || revision instanceof CurrentContentRevision) {
			return null;
		}
		return revision.getFile().getPath() + "@" + revision.getRevisionNumber().asString();
	}

	private static String cachedContent(ContentRevision revision, Map<String, String> revisionCache) {
		String key = revisionKey(revision);
		if (key == null) {
			return content(revision);
		}
		String cached = revisionCache.get(key);
		if (cached != null) {
			return cached;
		}
		String content = content(revision);
		if (content != null) {
			revisionCache.put(key, content);
		}
		return content;
	}

	/** Adds the member diff; call inside a read action. A failing file is listed without members. */
	static FileDiff withMembers(Project project, FileDiff file) {
		try {
			return computeMembers(project, file);
		}
		catch (ProcessCanceledException e) {
			throw e;
		}
		catch (RuntimeException e) {
			LOG.warn("Cannot compute structure diff for " + file.path(), e);
			return file;
		}
	}

	private static FileDiff computeMembers(Project project, FileDiff file) {
		if (file.fileType().isBinary() || StringUtil.length(file.beforeText()) > MAX_STRUCTURE_LENGTH
				|| StringUtil.length(file.afterText()) > MAX_STRUCTURE_LENGTH) {
			return file;
		}
		// an existing revision without content would otherwise show every member as added or removed
		if (file.change().getBeforeRevision() != null && file.beforeText() == null
				|| file.change().getAfterRevision() != null && file.afterText() == null) {
			return file;
		}
		String name = StringUtil.getShortName(file.path(), '/');
		List<Member> before = file.beforeText() == null ? List.of() : structure(project, name, file.fileType(), file.beforeText());
		List<Member> after = file.afterText() == null ? List.of() : structure(project, name, file.fileType(), file.afterText());
		if (before == null || after == null) {
			return file;
		}
		return file.withMembers(diff(before, after));
	}

	private static String content(ContentRevision revision) {
		if (revision == null) {
			return null;
		}
		try {
			String content = revision.getContent();
			if (content == null) {
				// FakeRevision: placeholder from change lists restored at startup, replaced after the first VCS refresh
				if (!"FakeRevision".equals(revision.getClass().getSimpleName())) {
					LOG.warn("No content for " + revision.getFile() + " (" + revision.getClass().getName() + ")");
				}
				return null;
			}
			return StringUtil.convertLineSeparators(content);
		}
		catch (VcsException e) {
			LOG.warn("Cannot load " + revision.getFile(), e);
			return null;
		}
	}

	/** Returns null when the file type has no tree-based structure view. */
	private static List<Member> structure(Project project, String name, FileType fileType, String text) {
		PsiFile psi = PsiFileFactory.getInstance(project).createFileFromText(name, fileType, text);
		StructureViewBuilder builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psi);
		if (!(builder instanceof TreeBasedStructureViewBuilder treeBuilder)) {
			return null;
		}
		StructureViewModel model = treeBuilder.createStructureViewModel(null);
		try {
			return members(model.getRoot(), psi);
		}
		finally {
			Disposer.dispose(model);
		}
	}

	/**
	 * Offset in {@code text} of the member at {@code path}, matched by key like {@link #diff} matches members, else by name;
	 * -1 when the file type has no tree-based structure view or the member is not there. Call inside a read action.
	 */
	static int currentOffset(Project project, String fileName, FileType fileType, CharSequence text, List<MemberDiff> path) {
		// parsed like the revisions, not taken from the document: the PSI of an uncommitted document is stale, and committing needs the write-intent lock
		PsiFile psi = PsiFileFactory.getInstance(project).createFileFromText(fileName, fileType, text);
		StructureViewBuilder builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psi);
		if (!(builder instanceof TreeBasedStructureViewBuilder treeBuilder)) {
			return -1;
		}
		StructureViewModel model = treeBuilder.createStructureViewModel(null);
		try {
			TreeElement current = model.getRoot();
			for (MemberDiff member : path) {
				Map<String, TreeElement> children = keyedChildren(current, psi);
				current = children.get(member.key());
				if (current == null) {
					String name = name(member.label());
					current = children.values().stream()
							.filter(c -> name.equals(name(label(c))))
							.findFirst().orElse(null);
				}
				if (current == null) {
					return -1;
				}
			}
			PsiElement element = element(current);
			return element == null ? -1 : element.getTextOffset();
		}
		finally {
			Disposer.dispose(model);
		}
	}

	/** Children of {@code parent} declared in {@code file} by key: the label, with {@code #n} for the n-th same label. */
	private static Map<String, TreeElement> keyedChildren(TreeElement parent, PsiFile file) {
		Map<String, TreeElement> result = new LinkedHashMap<>();
		Map<String, Integer> labelCounts = new HashMap<>();
		for (TreeElement child : parent.getChildren()) {
			ProgressManager.checkCanceled();
			PsiElement element = element(child);
			if (element != null && element.getContainingFile() != file) {
				continue; // inherited members
			}
			String label = label(child);
			// same-named elements (e.g. JS overloads without types): disambiguate by occurrence order
			int count = labelCounts.merge(label, 1, Integer::sum);
			result.put(count == 1 ? label : label + "#" + count, child);
		}
		return result;
	}

	private static PsiElement element(TreeElement child) {
		return child instanceof StructureViewTreeElement e && e.getValue() instanceof PsiElement p ? p : null;
	}

	private static String label(TreeElement child) {
		return StringUtil.notNullize(child.getPresentation().getPresentableText());
	}

	private static List<Member> members(TreeElement parent, PsiFile file) {
		List<Member> result = new ArrayList<>();
		for (Map.Entry<String, TreeElement> entry : keyedChildren(parent, file).entrySet()) {
			String key = entry.getKey();
			TreeElement child = entry.getValue();
			PsiElement element = element(child);
			ItemPresentation presentation = child.getPresentation();
			String label = StringUtil.notNullize(presentation.getPresentableText());
			String elementText = element == null ? null : element.getText();
			String text = elementText == null ? label : elementText;
			int offset = element == null ? -1 : element.getTextOffset();
			boolean container = element != null && IS_CONTAINER.get(element.getClass());
			List<Member> children = members(child, file);
			String normalized = element == null ? label : normalizedText(element, children);
			result.add(new Member(key, label, presentation.getIcon(false), element, text, normalized, offset, container, children));
		}
		return result;
	}

	/**
	 * Tokens of the element joined by single spaces, so whitespace-only changes are ignored but whitespace inside
	 * tokens (string literals, comments) is not. Child members are replaced by their keys: their changes are compared
	 * separately, and the keys keep a reordering of children a change of the parent.
	 */
	private static String normalizedText(PsiElement element, List<Member> children) {
		Map<PsiElement, String> childKeys = new IdentityHashMap<>();
		for (Member child : children) {
			if (child.element() != null) {
				childKeys.put(child.element(), child.key());
			}
		}
		StringBuilder result = new StringBuilder();
		// iterative depth-first walk: deeply nested expressions would overflow the stack of a recursive one
		PsiElement e = element;
		while (true) {
			String childKey = childKeys.get(e);
			PsiElement first = childKey == null ? e.getFirstChild() : null;
			if (first != null) {
				e = first;
				continue;
			}
			String token = childKey != null ? '\0' + childKey : e.getText();
			if (!StringUtil.isEmptyOrSpaces(token)) {
				result.append(token).append(' ');
			}
			while (e != element && e.getNextSibling() == null) {
				e = e.getParent();
			}
			if (e == element) {
				return result.toString();
			}
			e = e.getNextSibling();
		}
	}

	private static List<MemberDiff> diff(List<Member> before, List<Member> after) {
		Map<String, Member> beforeByKey = new LinkedHashMap<>();
		for (Member m : before) {
			beforeByKey.put(m.key(), m);
		}
		Map<Member, Member> matched = new IdentityHashMap<>();
		for (Member a : after) {
			Member b = beforeByKey.remove(a.key());
			if (b != null) {
				matched.put(a, b);
			}
		}
		// a changed signature changes the label ("foo()" -> "foo(x: number)"): pair the rest by name, in order
		Map<String, Deque<Member>> unmatchedByName = new HashMap<>();
		for (Member b : beforeByKey.values()) {
			unmatchedByName.computeIfAbsent(name(b.label()), n -> new ArrayDeque<>()).add(b);
		}
		for (Member a : after) {
			Deque<Member> candidates = matched.containsKey(a) ? null : unmatchedByName.get(name(a.label()));
			Member b = candidates == null ? null : candidates.poll();
			if (b != null) {
				matched.put(a, b);
				beforeByKey.remove(b.key());
			}
		}
		List<MemberDiff> result = new ArrayList<>();
		for (Member a : after) {
			Member b = matched.get(a);
			if (b == null) {
				result.add(whole(a, FileStatus.ADDED));
				continue;
			}
			List<MemberDiff> children = diff(b.children(), a.children());
			if (!children.isEmpty() || !a.normalizedText().equals(b.normalizedText()) || !a.label().equals(b.label())) {
				int[] lines = changedLines(b.text(), a.text());
				result.add(new MemberDiff(a.key(), a.label(), b.label(), a.icon(), FileStatus.MODIFIED, b.offset(), a.offset(),
						lines[0], lines[1], a.container(), children));
			}
		}
		for (Member b : beforeByKey.values()) {
			result.add(whole(b, FileStatus.DELETED));
		}
		return result;
	}

	/** Inserted and deleted lines between two member texts; zeros when the texts are too big to diff. */
	private static int[] changedLines(String before, String after) {
		int[] lines = new int[2];
		try {
			for (Diff.Change change = Diff.buildChanges(before, after); change != null; change = change.link) {
				lines[0] += change.inserted;
				lines[1] += change.deleted;
			}
		}
		catch (FilesTooBigForDiffException e) {
			return new int[2];
		}
		return lines;
	}

	/** Label without parameters and type: {@code foo(a: string): void} -> {@code foo}, {@code count: int} -> {@code count}. */
	private static String name(String label) {
		int end = StringUtil.indexOfAny(label, "(:");
		return end < 0 ? label : label.substring(0, end).trim();
	}

	private static MemberDiff whole(Member m, FileStatus status) {
		List<MemberDiff> children = m.children().stream().map(c -> whole(c, status)).toList();
		boolean added = status == FileStatus.ADDED;
		int lines = StringUtil.countNewLines(m.text()) + 1;
		return new MemberDiff(m.key(), m.label(), m.label(), m.icon(), status, added ? -1 : m.offset(), added ? m.offset() : -1,
				added ? lines : 0, added ? 0 : lines, m.container(), children);
	}
}
