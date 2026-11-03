package com.fsodomka.structurediff.diff;

import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.navigation.ItemPresentation;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.util.diff.Diff;
import com.intellij.util.diff.FilesTooBigForDiffException;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.*;

/**
 * Computes member-level differences by comparing the Structure view trees of the before and after file versions.
 */
public final class StructureDiffComputer {
	private static final Logger LOG = Logger.getInstance(StructureDiffComputer.class);

	private StructureDiffComputer() {
	}

	/**
	 * Structure element of one file version. {@code text} is the element source, used for the line diff; the label when
	 * there is no PSI element. {@code normalizedText} is compared to detect changes, see {@link #normalizedText}.
	 */
	record Member(String key, String label, Icon icon, PsiElement element, String text, String normalizedText,
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
	static final int MAX_STRUCTURE_LENGTH = 1_000_000;
	/** FNV-1a constants of the content ids, see {@link #contentId}. */
	private static final long FNV_OFFSET = 0xcbf29ce484222325L;
	private static final long FNV_PRIME = 0x100000001b3L;

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

	/** Adds the member diff; call inside a read action. A failing file is listed without members. */
	public static FileDiff withMembers(Project project, FileDiff file) {
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

	/** Returns null when the file type has no tree-based structure view. */
	private static List<Member> structure(Project project, String name, FileType fileType, String text) {
		return MemberLocator.withStructure(project, name, fileType, text, null, StructureDiffComputer::members);
	}

	private static List<Member> members(TreeElement parent, PsiFile file) {
		List<Member> result = new ArrayList<>();
		for (Map.Entry<String, TreeElement> entry : MemberLocator.keyedChildren(parent, file).entrySet()) {
			String key = entry.getKey();
			TreeElement child = entry.getValue();
			PsiElement element = MemberLocator.element(child);
			ItemPresentation presentation = child.getPresentation();
			String label = MemberLocator.label(child);
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

	static List<MemberDiff> diff(List<Member> before, List<Member> after) {
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
		// a rename keeps the text but the name: pair the rest by that text, where only one member on each side has it
		Map<String, List<Member>> removedByText = byTextWithoutName(beforeByKey.values());
		Map<String, List<Member>> addedByText = byTextWithoutName(after.stream().filter(a -> !matched.containsKey(a)).toList());
		removedByText.forEach((text, removed) -> {
			List<Member> added = addedByText.get(text);
			if (removed.size() == 1 && added != null && added.size() == 1) {
				matched.put(added.getFirst(), removed.getFirst());
				beforeByKey.remove(removed.getFirst().key());
			}
		});
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
				result.add(new MemberDiff(a.key(), b.key(), a.label(), b.label(), a.icon(), FileStatus.MODIFIED, b.offset(), a.offset(),
						lines[0], lines[1], a.container(), contentId(a), children));
			}
		}
		for (Member b : beforeByKey.values()) {
			result.add(whole(b, FileStatus.DELETED));
		}
		return result;
	}

	/** {@code members} by their normalized text with each one's own name blanked, including its uses, as in recursion. */
	private static Map<String, List<Member>> byTextWithoutName(Collection<Member> members) {
		Map<String, List<Member>> result = new LinkedHashMap<>();
		for (Member m : members) {
			// tokens are separated by single spaces, so the name is matched as a whole token
			String text = (" " + m.normalizedText()).replace(" " + name(m.label()) + " ", " \0name ");
			result.computeIfAbsent(text, t -> new ArrayList<>()).add(m);
		}
		return result;
	}

	/** Inserted and deleted lines between two member texts; zeros when the texts are too big to diff. */
	static int[] changedLines(String before, String after) {
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
	public static String name(String label) {
		int end = StringUtil.indexOfAny(label, "(:");
		return end < 0 ? label : label.substring(0, end).trim();
	}

	private static MemberDiff whole(Member m, FileStatus status) {
		List<MemberDiff> children = m.children().stream().map(c -> whole(c, status)).toList();
		boolean added = status == FileStatus.ADDED;
		int lines = StringUtil.countNewLines(m.text()) + 1;
		return new MemberDiff(m.key(), m.key(), m.label(), m.label(), m.icon(), status, added ? -1 : m.offset(), added ? m.offset() : -1,
				added ? lines : 0, added ? 0 : lines, m.container(), contentId(m), children);
	}

	/**
	 * Identifies the content of {@code member} and of its children, so a member modified again can be told from an
	 * unchanged one although its status stays {@link FileStatus#MODIFIED}. Whitespace-only differences are ignored, as
	 * by the change detection; child content is included, as a container's text only holds its children's keys.
	 */
	private static long contentId(Member member) {
		long id = stringHash(member.normalizedText());
		for (Member child : member.children()) {
			id = mix(id, contentId(child));
		}
		return id;
	}

	/** FNV-1a over the text's chars; an id is only compared for equality, so its distribution matters, not its value. */
	private static long stringHash(String text) {
		long hash = FNV_OFFSET;
		for (int i = 0; i < text.length(); i++) {
			hash = (hash ^ text.charAt(i)) * FNV_PRIME;
		}
		return hash;
	}

	/** Combines the id of a child with the id of the member so far; the children's order is part of the content. */
	private static long mix(long hash, long child) {
		long result = (hash ^ child) * FNV_PRIME;
		return result ^ (result >>> 29);
	}
}
