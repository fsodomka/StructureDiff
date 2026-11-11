package com.fsodomka.structurediff.coverage;

import com.fsodomka.structurediff.CoverageLines;
import com.intellij.coverage.CoverageDataManager;
import com.intellij.coverage.CoverageSuiteListener;
import com.intellij.coverage.CoverageSuitesBundle;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.rt.coverage.data.ClassData;
import com.intellij.rt.coverage.data.LineData;
import com.intellij.rt.coverage.data.ProjectData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** {@link CoverageLines} from the bundled Coverage plugin; registered by {@code structurediff-coverage.xml}. */
public final class IdeCoverageLines implements CoverageLines {
	private final Project project;
	/** The last run loaded, reused while it is the selected one; guarded by {@link #load}'s lock. */
	private @Nullable Loaded loaded;

	private record Loaded(ProjectData source, Data data) {
	}

	public IdeCoverageLines(Project project) {
		this.project = project;
	}

	@Override
	public @Nullable Run run() {
		CoverageSuitesBundle bundle = CoverageDataManager.getInstance(project).getCurrentSuitesBundle();
		return bundle == null ? null : new Run(shortName(bundle.getPresentableName()), bundle.getLastCoverageTimeStamp());
	}

	/** "StructureDiff [test] Coverage Results" is "StructureDiff [test]". */
	static String shortName(String name) {
		return StringUtil.trimEnd(name, " Coverage Results");
	}

	/** Synchronized: back-to-back refreshes each load on a pooled thread; the first indexes the run, the others wait and reuse it. */
	@Override
	public synchronized @Nullable Data load() {
		CoverageSuitesBundle bundle = CoverageDataManager.getInstance(project).getCurrentSuitesBundle();
		// reads the run's file the first time, then the suites keep it
		ProjectData source = bundle == null ? null : bundle.getCoverageData();
		if (source == null) {
			return null;
		}
		Loaded last = loaded;
		if (last != null && last.source() == source) {
			return last.data();
		}
		// the run of a plugin's tests holds the IDE's classes too, tens of thousands; index them once, not per file
		Map<String, List<ClassData>> byTopLevel = new HashMap<>();
		for (ClassData classData : source.getClassesCollection()) {
			byTopLevel.computeIfAbsent(topLevel(classData.getName()), k -> new ArrayList<>()).add(classData);
		}
		Data data = file -> hits(file, byTopLevel);
		loaded = new Loaded(source, data);
		return data;
	}

	private @Nullable Hits hits(VirtualFile file, Map<String, List<ClassData>> byTopLevel) {
		PsiFile psi = PsiManager.getInstance(project).findFile(file);
		if (!(psi instanceof PsiClassOwner owner)) {
			return null;
		}
		Set<String> roots = new HashSet<>();
		for (PsiClass psiClass : owner.getClasses()) {
			if (psiClass.getQualifiedName() != null) {
				roots.add(psiClass.getQualifiedName());
			}
		}
		// Kotlin's top-level code, in case the facade class is not among the light classes
		String packagePrefix = owner.getPackageName().isEmpty() ? "" : owner.getPackageName() + ".";
		roots.add(packagePrefix + StringUtil.capitalize(file.getNameWithoutExtension()) + "Kt");
		// line → covered; a line in several classes (a lambda in a method) is covered when any of them ran it
		Map<Integer, Boolean> lines = new TreeMap<>();
		for (String root : roots) {
			for (ClassData classData : byTopLevel.getOrDefault(root, List.of())) {
				Object[] classLines = classData.getLines();
				if (classLines == null) {
					continue;
				}
				for (Object line : classLines) {
					if (line instanceof LineData lineData) {
						lines.merge(lineData.getLineNumber() - 1, lineData.getHits() > 0 || lineData.getStatus() != 0, Boolean::logicalOr);
					}
				}
			}
		}
		if (lines.isEmpty()) {
			return null;
		}
		int[] numbers = new int[lines.size()];
		boolean[] covered = new boolean[lines.size()];
		int i = 0;
		for (Map.Entry<Integer, Boolean> entry : lines.entrySet()) {
			numbers[i] = entry.getKey();
			covered[i++] = entry.getValue();
		}
		return new Hits(numbers, covered);
	}

	/** The top-level class of the JVM class {@code name}: inner classes, lambdas and companions are in their outer one. */
	static String topLevel(String name) {
		int dollar = name.indexOf('$', 1);
		return dollar < 0 ? name : name.substring(0, dollar);
	}

	@Override
	public void onChange(Runnable changed, Disposable parent) {
		CoverageDataManager.getInstance(project).addSuiteListener(new CoverageSuiteListener() {
			@Override
			public void afterSuiteChosen() {
				ApplicationManager.getApplication().invokeLater(changed, project.getDisposed());
			}

			@Override
			public void coverageDataCalculated(CoverageSuitesBundle bundle) {
				ApplicationManager.getApplication().invokeLater(changed, project.getDisposed());
			}
		}, parent);
	}
}
