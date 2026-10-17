package com.fsodomka.structurediff.review;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Reads the findings of a SARIF 2.1.0 file written by the review skill; other SARIF works as well. */
final class SarifReader {
	/** The skill appends the suggested fix to the message after this separator. */
	private static final String FIX_SEPARATOR = "\n\nFix: ";
	/** The skill numbers its rules {@code <CATEGORY>-<n>}. */
	private static final Pattern RULE_NUMBER = Pattern.compile("-\\d+$");

	private SarifReader() {
	}

	/**
	 * @param rootPath path of the reviewed repository root, which the fingerprints start with
	 * @throws com.google.gson.JsonParseException when the text is not JSON
	 */
	static List<Finding> read(String json, String rootPath) {
		List<Finding> findings = new ArrayList<>();
		JsonElement root = JsonParser.parseString(json);
		if (!root.isJsonObject()) {
			return findings;
		}
		for (JsonObject run : objects(root.getAsJsonObject(), "runs")) {
			for (JsonObject result : objects(run, "results")) {
				Finding finding = toFinding(result, rootPath);
				if (finding != null) {
					findings.add(finding);
				}
			}
		}
		return findings;
	}

	/** A result without a file location is skipped: there is nothing to highlight. */
	private static @Nullable Finding toFinding(JsonObject result, String rootPath) {
		List<JsonObject> locations = objects(result, "locations");
		JsonObject physical = locations.isEmpty() ? null : object(locations.getFirst(), "physicalLocation");
		String uri = string(object(physical, "artifactLocation"), "uri");
		if (uri == null) {
			return null;
		}
		String path = path(uri);
		JsonObject region = object(physical, "region");
		String ruleId = Objects.requireNonNullElse(string(result, "ruleId"), "REVIEW");
		String text = StringUtil.convertLineSeparators(Objects.requireNonNullElse(string(object(result, "message"), "text"), ""));
		int fixAt = text.indexOf(FIX_SEPARATOR);
		String message = fixAt < 0 ? text : text.substring(0, fixAt);
		String fix = fixAt < 0 ? null : text.substring(fixAt + FIX_SEPARATOR.length());
		int line = integer(region, "startLine");
		String snippet = string(object(region, "snippet"), "text");
		JsonObject properties = object(result, "properties");
		ReviewSeverity severity = ReviewSeverity.of(string(properties, "ideaSeverity"), string(result, "level"));
		int rank = ReviewSeverity.rank(string(properties, "qodanaSeverity"), severity);
		snippet = snippet == null ? null : StringUtil.convertLineSeparators(snippet);
		return new Finding(fingerprint(rootPath, ruleId, path, line, snippet), ruleId, severity, rank, message, fix, path, line,
				integer(region, "startColumn"), integer(region, "charOffset"), integer(region, "charLength"), snippet);
	}

	/**
	 * Identifies a finding across reviews, for dismissals. Not {@code equalIndicator/v1}: the skill hashes it over the
	 * message, which carries the finding number, the verdict and wording that change between reviews. Built from the
	 * category without the skill's {@code -<number>} suffix, the path, the line and the reviewed code; the line keeps a
	 * dismissal of a finding on a common line such as a closing brace from hiding later findings elsewhere in the file.
	 * Starts with the root: the same relative path in another repository is another file. That also keeps findings of
	 * two roots apart where they are keys.
	 */
	private static String fingerprint(String rootPath, String ruleId, String path, int line, @Nullable String snippet) {
		String category = RULE_NUMBER.matcher(ruleId).replaceFirst("");
		return rootPath + '|' + category + ':' + path + ':' + line + (snippet == null || snippet.isBlank() ? "" : ':' + snippet.strip());
	}

	/**
	 * Decodes a relative URI reference and turns a {@code file:} URI into an absolute path; other URIs, and text that is
	 * not a valid URI, such as a path with spaces, are kept.
	 */
	static String path(String uri) {
		try {
			URI parsed = new URI(uri);
			String scheme = parsed.getScheme();
			if (scheme == null) {
				return parsed.getPath() == null || parsed.getPath().isEmpty() ? uri : parsed.getPath();
			}
			if (scheme.equalsIgnoreCase("file")) {
				return FileUtil.toSystemIndependentName(Path.of(parsed).toString());
			}
		} catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
			// not a URI, or a file URI with a host
		}
		return uri;
	}

	private static @Nullable JsonObject object(@Nullable JsonObject parent, String name) {
		JsonElement element = parent == null ? null : parent.get(name);
		return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
	}

	private static List<JsonObject> objects(JsonObject parent, String name) {
		JsonElement element = parent.get(name);
		if (element == null || !element.isJsonArray()) {
			return List.of();
		}
		List<JsonObject> objects = new ArrayList<>();
		for (JsonElement item : element.getAsJsonArray()) {
			if (item.isJsonObject()) {
				objects.add(item.getAsJsonObject());
			}
		}
		return objects;
	}

	private static @Nullable String string(@Nullable JsonObject parent, String name) {
		JsonElement element = parent == null ? null : parent.get(name);
		return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
	}

	/** -1 when missing, or not an int: Gson's getAsInt would truncate 1.5 and wrap 4294967297 to 1. */
	private static int integer(@Nullable JsonObject parent, String name) {
		JsonElement element = parent == null ? null : parent.get(name);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
			return -1;
		}
		try {
			return element.getAsBigDecimal().intValueExact();
		} catch (ArithmeticException | NumberFormatException e) {
			return -1;
		}
	}
}
