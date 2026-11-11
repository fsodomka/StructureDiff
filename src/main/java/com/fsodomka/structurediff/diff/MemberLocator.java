package com.fsodomka.structurediff.diff;

import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.ide.structureView.StructureViewModel;
import com.intellij.ide.structureView.StructureViewTreeElement;
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder;
import com.intellij.ide.util.treeView.smartTree.TreeElement;
import com.intellij.lang.LanguageStructureViewBuilder;
import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;
import java.util.function.BiFunction;

/**
 * Finds members in a file by their Structure view tree: the member at a key path, the members enclosing an offset, the
 * keyed children the {@link StructureDiffComputer} diffs.
 */
public final class MemberLocator {
	private MemberLocator() {
	}

	/**
	 * Parses {@code text} and applies {@code function} to the root of its structure view and the parsed file;
	 * {@code fallback} when the file type has no tree-based structure view.
	 */
	static <T> T withStructure(Project project, String fileName, FileType fileType, CharSequence text, T fallback,
								 BiFunction<TreeElement, PsiFile, T> function) {
		return withStructure(PsiFileFactory.getInstance(project).createFileFromText(fileName, fileType, text), fallback, function);
	}

	private static <T> T withStructure(PsiFile psi, T fallback, BiFunction<TreeElement, PsiFile, T> function) {
		StructureViewBuilder builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psi);
		if (!(builder instanceof TreeBasedStructureViewBuilder treeBuilder)) {
			return fallback;
		}
		StructureViewModel model = treeBuilder.createStructureViewModel(null);
		try {
			return function.apply(model.getRoot(), psi);
		}
		finally {
			Disposer.dispose(model);
		}
	}

	/**
	 * Offset in {@code text} of the member at {@code path}, matched by key like {@link StructureDiffComputer#diff} matches members,
	 * else by name; -1 when the file type has no tree-based structure view or the member is not there. Call inside a read action.
	 */
	public static int currentOffset(Project project, String fileName, FileType fileType, CharSequence text, List<MemberDiff> path) {
		// parsed like the revisions, not taken from the document: the PSI of an uncommitted document is stale, and committing needs the write-intent lock
		List<Step> steps = path.stream().map(m -> new Step(m.key(), StructureDiffComputer.name(m.label()))).toList();
		return withStructure(project, fileName, fileType, text, -1, (root, psi) -> {
			PsiElement element = element(find(root, psi, steps));
			return element == null ? -1 : element.getTextOffset();
		});
	}

	/**
	 * The elements of the members at {@code paths} in the project file {@code psi}, matched like {@link #currentOffset};
	 * null where the member is not there, all null when the file type has no tree-based structure view. Unlike the other
	 * lookups this uses the project's PSI, which resolves references. Call inside a read action.
	 */
	public static List<PsiElement> elements(PsiFile psi, List<List<MemberDiff>> paths) {
		List<PsiElement> none = Collections.nCopies(paths.size(), null);
		if (psi.getTextLength() > StructureDiffComputer.MAX_STRUCTURE_LENGTH) {
			return none;
		}
		return withStructure(psi, none, (root, file) -> {
			// shared by the paths: building a parent's children computes all their labels, slow for a large class
			Map<TreeElement, Map<String, TreeElement>> children = new IdentityHashMap<>();
			return paths.stream()
					.map(path -> element(find(root, file, path.stream().map(m -> new Step(m.key(), StructureDiffComputer.name(m.label()))).toList(), children)))
					.toList();
		});
	}

	/** One step of a path to a member: its key, else the first sibling with the same {@link StructureDiffComputer#name}. */
	public record Step(String key, String name) {
	}

	/** Text range of a member and its neighbor siblings, null at either end. */
	public record Located(TextRange range, @Nullable Step previous, @Nullable Step next) {
	}

	/**
	 * The member at {@code path} in {@code text}, matched like {@link #currentOffset}; null when the file type has no
	 * tree-based structure view or the member is not there. Call inside a read action.
	 */
	public static @Nullable Located locate(Project project, String fileName, FileType fileType, CharSequence text, List<Step> path) {
		return withStructure(project, fileName, fileType, text, null, (root, psi) -> {
			TreeElement parent = find(root, psi, path.subList(0, path.size() - 1));
			if (parent == null) {
				return null;
			}
			TreeElement current = find(parent, psi, path.subList(path.size() - 1, path.size()));
			PsiElement element = element(current);
			if (element == null) {
				return null;
			}
			Step previous = null;
			Step next = null;
			boolean seen = false;
			for (Map.Entry<String, TreeElement> entry : keyedChildren(parent, psi).entrySet()) {
				Step step = new Step(entry.getKey(), StructureDiffComputer.name(label(entry.getValue())));
				if (element(entry.getValue()) == element) {
					seen = true;
				}
				else if (!seen) {
					previous = step;
				}
				else {
					next = step;
					break;
				}
			}
			return new Located(element.getTextRange(), previous, next);
		});
	}

	/** The member at {@code path} below {@code root}; null when {@code root} is null or the member is not there. */
	private static @Nullable TreeElement find(@Nullable TreeElement root, PsiFile psi, List<Step> path) {
		return find(root, psi, path, new IdentityHashMap<>());
	}

	/** As {@link #find(TreeElement, PsiFile, List)}, taking and adding the keyed children of each parent from {@code cache}. */
	private static @Nullable TreeElement find(@Nullable TreeElement root, PsiFile psi, List<Step> path,
											  Map<TreeElement, Map<String, TreeElement>> cache) {
		TreeElement current = root;
		for (Step step : path) {
			if (current == null) {
				return null;
			}
			Map<String, TreeElement> children = cache.computeIfAbsent(current, parent -> keyedChildren(parent, psi));
			current = children.get(step.key());
			if (current == null) {
				current = children.values().stream()
						.filter(c -> step.name().equals(StructureDiffComputer.name(label(c))))
						.findFirst().orElse(null);
			}
		}
		return current;
	}

	/**
	 * For each offset in {@code text}, the keys of the members enclosing it, outermost first, as {@link StructureDiffComputer#diff} keys them;
	 * empty for an offset outside any member, or for all offsets when the file type has no tree-based structure view.
	 * Call inside a read action.
	 */
	public static List<List<String>> memberKeyPaths(Project project, String fileName, FileType fileType, CharSequence text,
													int[] offsets) {
		List<List<String>> none = Collections.nCopies(offsets.length, List.of());
		if (text.length() > StructureDiffComputer.MAX_STRUCTURE_LENGTH) {
			return none;
		}
		return withStructure(project, fileName, fileType, text, none, (root, psi) -> enclosing(root, psi, offsets, null, false).stream().map(MemberPath::keys).toList());
	}

	/**
	 * The keys, the shown labels and the icons of the members enclosing an offset, outermost first; all empty outside any
	 * member. An icon is null when the structure view shows none.
	 */
	public record MemberPath(List<String> keys, List<String> labels, List<Icon> icons) {
		public static final MemberPath NONE = new MemberPath(List.of(), List.of(), List.of());
	}

	/**
	 * For each offset in the project file {@code psi}, the members enclosing it; like {@link #memberKeyPaths}, but with
	 * the shown labels too and on the project's PSI, which resolves references. Call inside a read action.
	 *
	 * @param declarations for each offset, the range of the declaration it names, or null for a plain offset; its
	 *                     members are found by the whole declaration, see {@link #enclosing}
	 */
	public static List<MemberPath> memberPaths(PsiFile psi, int[] offsets, @Nullable TextRange @Nullable [] declarations) {
		List<MemberPath> none = Collections.nCopies(offsets.length, MemberPath.NONE);
		if (psi.getTextLength() > StructureDiffComputer.MAX_STRUCTURE_LENGTH) {
			return none;
		}
		return withStructure(psi, none, (root, file) -> enclosing(root, file, offsets, declarations, true));
	}

	/**
	 * The members enclosing each offset, outermost first; with their icons when {@code withIcons}, else null icons. For
	 * an offset with a declaration, the first member inside the declaration counts too, when none contains the offset,
	 * while the path has not reached the declaration: the structure view may show a part of it that does not hold the
	 * name, like TypeScript's arrow function of a {@code const}, which starts after the name. Of overlapping siblings
	 * containing the offset the smallest is taken, the first of equal ones: Kotlin's primary constructor contains its
	 * {@code val} parameters, which are properties of their own.
	 */
	private static List<MemberPath> enclosing(TreeElement root, PsiFile psi, int[] offsets,
											  @Nullable TextRange @Nullable [] declarations, boolean withIcons) {
		List<MemberPath> result = new ArrayList<>(offsets.length);
		// a structure view builds its children anew on each call, and coverage asks for every executable line of a file
		Map<TreeElement, Map<String, TreeElement>> children = new IdentityHashMap<>();
		for (int i = 0; i < offsets.length; i++) {
			int offset = offsets[i];
			TextRange declaration = declarations == null ? null : declarations[i];
			List<String> keys = new ArrayList<>();
			List<String> labels = new ArrayList<>();
			List<Icon> icons = new ArrayList<>();
			TreeElement current = root;
			// the range of the last member on the path, null at the root
			TextRange reached = null;
			while (current != null) {
				Map.Entry<String, TreeElement> enclosing = null;
				TextRange enclosingRange = null;
				// the first member inside the declaration, taken when none contains the offset
				Map.Entry<String, TreeElement> inDeclaration = null;
				TextRange inDeclarationRange = null;
				boolean beforeDeclaration = declaration != null && (reached == null || !declaration.contains(reached));
				for (Map.Entry<String, TreeElement> entry : children.computeIfAbsent(current, parent -> keyedChildren(parent, psi)).entrySet()) {
					PsiElement element = element(entry.getValue());
					TextRange range = element == null ? null : element.getTextRange();
					if (range == null) {
						continue;
					}
					if (range.getStartOffset() <= offset && offset < range.getEndOffset()) {
						if (enclosingRange == null || range.getLength() < enclosingRange.getLength()) {
							enclosing = entry;
							enclosingRange = range;
						}
					}
					else if (inDeclaration == null && beforeDeclaration && declaration.contains(range)) {
						inDeclaration = entry;
						inDeclarationRange = range;
					}
				}
				if (enclosing == null) {
					enclosing = inDeclaration;
					enclosingRange = inDeclarationRange;
				}
				if (enclosing != null) {
					keys.add(enclosing.getKey());
					labels.add(label(enclosing.getValue()));
					icons.add(withIcons ? enclosing.getValue().getPresentation().getIcon(false) : null);
					reached = enclosingRange;
				}
				current = enclosing == null ? null : enclosing.getValue();
			}
			result.add(new MemberPath(keys, labels, Collections.unmodifiableList(icons)));
		}
		return result;
	}

	/** Children of {@code parent} declared in {@code file} by key: the label, with {@code #n} for the n-th same label. */
	static Map<String, TreeElement> keyedChildren(TreeElement parent, PsiFile file) {
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

	static PsiElement element(TreeElement child) {
		return child instanceof StructureViewTreeElement e && e.getValue() instanceof PsiElement p ? p : null;
	}

	static String label(TreeElement child) {
		return StringUtil.notNullize(child.getPresentation().getPresentableText());
	}
}
