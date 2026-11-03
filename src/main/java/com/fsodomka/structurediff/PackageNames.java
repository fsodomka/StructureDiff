package com.fsodomka.structurediff;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Package names of changed files, for grouping them in the treemap. */
final class PackageNames {
	private PackageNames() {
	}

	static final Pattern CONVENTIONAL_SOURCE = Pattern.compile("(?:^|.*/)src/[^/]+/([^/]+)/(?:(.*)/)?[^/]+");
	static final Set<String> PACKAGE_LANGUAGES = Set.of("java", "kotlin", "scala", "groovy");

	/**
	 * The package of {@code path} below a Maven style {@code src/<set>/<language>/} directory, dotted for JVM languages,
	 * else the directory below it; {@code null} when it is in no such directory.
	 */
	static String conventionalPackage(String path) {
		Matcher matcher = CONVENTIONAL_SOURCE.matcher(path);
		if (!matcher.matches()) {
			return null;
		}
		String directory = StringUtil.notNullize(matcher.group(2));
		return PACKAGE_LANGUAGES.contains(matcher.group(1)) ? directory.replace('/', '.') : directory;
	}

	/** {@link #relativePackages(Collection, Map, char)} per VCS root, first of dotted packages, then of directories. */
	static Map<String, String> relativePackages(Map<String, String> packages, Map<String, VirtualFile> roots) {
		Map<String, String> result = new HashMap<>(packages);
		// a HashMap, as files outside any root have the null key
		Map<VirtualFile, List<String>> byRoot = new HashMap<>();
		packages.keySet().forEach(path -> byRoot.computeIfAbsent(roots.get(path), root -> new ArrayList<>()).add(path));
		for (List<String> paths : byRoot.values()) {
			relativePackages(paths, result, '.');
			relativePackages(paths, result, '/');
		}
		return result;
	}

	/**
	 * The {@code names} of {@code paths} without the package they all start with, {@code com.acme.app.ui -> ui}; only names
	 * with a {@code separator} decide it, as a resource directory like {@code META-INF} is no code package, and keeps its name.
	 */
	static void relativePackages(Collection<String> paths, Map<String, String> names, char separator) {
		String sep = String.valueOf(separator);
		String common = null;
		for (String path : paths) {
			String name = names.get(path);
			if (!name.contains(sep)) {
				continue;
			}
			if (common == null) {
				common = name;
			}
			while (!common.isEmpty() && !name.equals(common) && !name.startsWith(common + sep)) {
				common = common.contains(sep) ? common.substring(0, common.lastIndexOf(separator)) : "";
			}
		}
		if (common == null || common.isEmpty()) {
			return;
		}
		for (String path : paths) {
			String name = names.get(path);
			if (name.equals(common)) {
				names.put(path, "");
			}
			else if (name.startsWith(common + sep)) {
				names.put(path, name.substring(common.length() + 1));
			}
		}
	}
}
