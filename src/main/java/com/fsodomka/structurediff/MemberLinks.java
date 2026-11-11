package com.fsodomka.structurediff;

import com.fsodomka.structurediff.diff.MemberLocator;
import com.fsodomka.structurediff.diff.MemberLocator.MemberPath;
import com.fsodomka.structurediff.diff.StructureDiffComputer;
import com.intellij.lang.Language;
import com.intellij.lang.findUsages.EmptyFindUsagesProvider;
import com.intellij.lang.findUsages.LanguageFindUsages;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.GeneratedSourcesFilter;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;

import javax.swing.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * What uses a member and what it uses, resolved in the project's PSI of the working copy: callers are references to the
 * member found through the index, callees the targets of the references in the member's own code. Each is grouped by
 * the member that holds it, internal ones in the member's own file, external ones in other files.
 */
final class MemberLinks {
	/** References to a member searched at most; with more, {@link Links#callersCapped} is set. */
	static final int CAP = 200;
	/**
	 * References in a member resolved at most; with more, {@link Links#calleesCapped} is set. Resolving is the cost, and in
	 * a class most references are to its own members, dropped only once resolved, so this bounds the references, not the callees.
	 */
	static final int RESOLVE_CAP = 2000;
	/** Endings of build, configuration, data and text files, whose references are not counted: they are not code. */
	private static final List<String> NON_CODE_ENDINGS = List.of(".gradle", ".gradle.kts", ".properties", ".xml", ".yaml", ".yml",
			".json", ".toml", ".md", ".txt", ".csv");
	/** "Import" as a camel-case word: its capital starts one, so it only has to end, before an uppercase letter, digit or the end. */
	private static final Pattern IMPORT_WORD = Pattern.compile("Import(?=[A-Z0-9]|$)");

	private MemberLinks() {
	}

	/**
	 * A member holding references: for a caller the member the references are in, for a callee the referenced member.
	 * {@code keys}, {@code labels} and {@code icons} are its member path, outermost first; empty for a caller outside any
	 * member.
	 * {@code offset} and the 0-based {@code line} are of its first reference, for a callee of the member itself; line
	 * is -1 without a document. {@code count} is the number of references, {@code module} empty without a module.
	 */
	record Link(VirtualFile file, int offset, int line, List<String> keys, List<String> labels, List<Icon> icons, String module,
				int count, boolean internal, boolean test) {
	}

	record Links(List<Link> callers, boolean callersCapped, List<Link> callees, boolean calleesCapped) {
	}

	/** A reference, or for a callee the referenced member at its name, with its whole {@code declaration}; null for a caller. */
	private record Ref(VirtualFile file, int offset, TextRange declaration) {
	}

	/** The callers and callees of {@code element}, a member or class in a project file. Call inside a read action. */
	static Links links(Project project, PsiElement element) {
		PsiFile home = element.getContainingFile();
		VirtualFile homeFile = home == null ? null : home.getVirtualFile();
		if (homeFile == null) {
			return new Links(List.of(), false, List.of(), false);
		}
		TextRange range = element.getTextRange();
		List<Ref> callers = new ArrayList<>();
		boolean[] capped = {false};
		ReferencesSearch.search(element, GlobalSearchScope.projectScope(project)).forEach(reference -> {
			ProgressManager.checkCanceled();
			PsiElement source = reference.getElement();
			PsiFile psi = source.getContainingFile();
			VirtualFile file = psi == null ? null : psi.getVirtualFile();
			if (file == null || !counts(project, file, source)) {
				return true;
			}
			int offset = source.getTextRange().getStartOffset() + reference.getRangeInElement().getStartOffset();
			if (file.equals(homeFile) && range.contains(offset)) {
				return true; // recursion, or the member's own name
			}
			if (callers.size() == CAP) {
				capped[0] = true;
				return false;
			}
			callers.add(new Ref(file, offset, null));
			return true;
		});

		ProjectFileIndex index = ProjectFileIndex.getInstance(project);
		List<Ref> callees = new ArrayList<>();
		int[] resolved = {0};
		boolean[] calleesCapped = {false};
		PsiTreeUtil.processElements(element, e -> {
			ProgressManager.checkCanceled();
			for (PsiReference reference : e.getReferences()) {
				if (resolved[0]++ == RESOLVE_CAP) {
					calleesCapped[0] = true;
					return false;
				}
				PsiElement target = resolve(reference);
				PsiFile targetPsi = target == null ? null : target.getContainingFile();
				VirtualFile file = targetPsi == null ? null : targetPsi.getVirtualFile();
				// a light element, e.g. a record's implicit accessor, has a file but no text
				if (file == null || target.getTextRange() == null || !index.isInContent(file) || GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project)
						// its own code, and the members enclosing it
						|| file.equals(homeFile) && (range.contains(target.getTextRange()) || PsiTreeUtil.isAncestor(target, element, false))) {
					continue;
				}
				callees.add(new Ref(file, target.getTextOffset(), target.getTextRange()));
			}
			return true;
		});
		return new Links(group(project, callers, homeFile, true), capped[0], group(project, callees, homeFile, false), calleesCapped[0]);
	}

	/**
	 * The first valid target of {@code reference} other than its own element; null when it resolves to nothing. Kotlin's
	 * destructuring entry {@code a} in {@code val (a, b) = v} resolves to itself first, then to {@code component1}.
	 */
	private static PsiElement resolve(PsiReference reference) {
		if (reference instanceof PsiPolyVariantReference poly) {
			for (ResolveResult result : poly.multiResolve(false)) {
				if (result.isValidResult() && result.getElement() != null && result.getElement() != reference.getElement()) {
					return result.getElement();
				}
			}
			return null;
		}
		return reference.resolve();
	}

	/** Whether a reference at {@code element} in {@code file} counts: code, not generated, not in an import. */
	private static boolean counts(Project project, VirtualFile file, PsiElement element) {
		return isCode(file.getPath()) && !GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project)
				&& declares(element.getLanguage()) && !inImport(element);
	}

	/**
	 * One link per member holding {@code refs}, internal ones first, then by module, path and offset.
	 * {@code keepOutside} keeps references outside any member, as one link per file. Call inside a read action.
	 */
	private static List<Link> group(Project project, List<Ref> refs, VirtualFile homeFile, boolean keepOutside) {
		ProjectFileIndex index = ProjectFileIndex.getInstance(project);
		List<Link> result = new ArrayList<>();
		Map<VirtualFile, List<Ref>> byFile = refs.stream().collect(Collectors.groupingBy(Ref::file, LinkedHashMap::new, Collectors.toList()));
		for (Map.Entry<VirtualFile, List<Ref>> entry : byFile.entrySet()) {
			ProgressManager.checkCanceled();
			VirtualFile file = entry.getKey();
			List<Ref> fileRefs = entry.getValue().stream().sorted(Comparator.comparingInt(Ref::offset)).toList();
			PsiFile psi = file.isValid() ? PsiManager.getInstance(project).findFile(file) : null;
			List<MemberPath> paths = psi == null ? Collections.nCopies(fileRefs.size(), MemberPath.NONE)
					: MemberLocator.memberPaths(psi, fileRefs.stream().mapToInt(Ref::offset).toArray(),
							fileRefs.stream().map(Ref::declaration).toArray(TextRange[]::new));
			Document document = psi == null ? null : PsiDocumentManager.getInstance(project).getDocument(psi);
			Module module = ModuleUtilCore.findModuleForFile(file, project);
			String moduleName = module == null ? "" : module.getName();
			boolean test = index.isInTestSourceContent(file);
			// by keys: icons are created per lookup and need not be equal
			Map<List<String>, MemberPath> pathOf = new HashMap<>();
			Map<List<String>, List<Ref>> byMember = new LinkedHashMap<>();
			for (int i = 0; i < fileRefs.size(); i++) {
				MemberPath path = paths.get(i);
				if (keepOutside || !path.keys().isEmpty()) {
					pathOf.putIfAbsent(path.keys(), path);
					byMember.computeIfAbsent(path.keys(), k -> new ArrayList<>()).add(fileRefs.get(i));
				}
			}
			byMember.forEach((keys, memberRefs) -> {
				MemberPath path = pathOf.get(keys);
				int offset = memberRefs.getFirst().offset();
				int line = document == null || offset > document.getTextLength() ? -1 : document.getLineNumber(offset);
				result.add(new Link(file, offset, line, keys, path.labels(), path.icons(), moduleName, memberRefs.size(),
						file.equals(homeFile), test));
			});
		}
		result.sort(Comparator.comparing((Link l) -> !l.internal()).thenComparing(Link::module)
				.thenComparing(l -> l.file().getPath()).thenComparingInt(Link::offset));
		return result;
	}

	/** Whether the file at {@code path} is code: not a build, configuration, data or text file. */
	static boolean isCode(String path) {
		return NON_CODE_ENDINGS.stream().noneMatch(path::endsWith);
	}

	/**
	 * Whether {@code language} has declarations, so a reference from it is code: it has a Find Usages provider, itself or
	 * through a base language, other than the platform's default {@link EmptyFindUsagesProvider}. Markdown and plain text
	 * have none, though since 2026.2 Markdown resolves code spans like {@code `Foo`} to JVM symbols.
	 */
	static boolean declares(Language language) {
		return !(LanguageFindUsages.INSTANCE.forLanguage(language) instanceof EmptyFindUsagesProvider);
	}

	/**
	 * Whether {@code element} is in an import, recognized by the PSI class name like {@link StructureDiffComputer} does
	 * for member types, as the core plugin depends on no language: PsiImportStatement, KtImportDirective,
	 * ES6ImportDeclaration, PyFromImportStatement and the like.
	 */
	private static boolean inImport(PsiElement element) {
		for (PsiElement e = element; e != null && !(e instanceof PsiFile); e = e.getParent()) {
			if (isImportName(e.getClass().getSimpleName())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether a PSI class name has "Import" as a whole camel-case word, as in PsiImportStatementImpl or ES6ImportDeclaration,
	 * so a word that only contains it, like Important or Importer, does not hide a reference in code.
	 */
	static boolean isImportName(String simpleName) {
		return IMPORT_WORD.matcher(simpleName).find();
	}
}
