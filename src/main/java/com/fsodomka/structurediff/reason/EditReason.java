package com.fsodomka.structurediff.reason;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Why an agent made one change: a record of a {@code reviews/edits/*.jsonl} file, written by the edit-reasons Claude
 * Code mod (contract: docs/specs/2026-11-15-edit-reasons-mod.md, "Hand-over to StructureDiff").
 *
 * @param file  relative to the repository root, forward slashes; null for a change kept by tool call
 * @param start first line, 1-based, in the file as it was right after the edit; -1 without lines
 * @param end   last line, inclusive; -1 without lines
 * @param text  the changed lines as they were right after the edit, to find them again once they moved; the first whole
 *              lines of a long change (its extent is {@code start}..{@code end}); null when the mod didn't record
 *              them (a first line over its limit, or an older mod)
 * @param reason null while pending
 * @param source the record's {@code source}: "skill" for marks a skill wrote, null for the mod's own records
 * @param session the record's {@code session}: an agent session's id, or a skill run's name; the file's name (without
 *                {@code .jsonl}) when the record has none
 * @param title   the session's title (Claude Code's {@code ai-title}) as the mod last wrote it in the record's file; null
 *                for a file without one (an older mod, a skill run)
 * @param seq     the position of the change's first record in its file, 0-based
 */
public record EditReason(String toolUseId, String tool, @Nullable String file, int start, int end, @Nullable String text,
						 @Nullable String reason, Instant time, @Nullable String source, @Nullable String session,
						 @Nullable String title, int seq) {

	/** Written by a skill (e.g. a code walkthrough's marks) rather than for an agent's edit: drawn in its own color. */
	public boolean skill() {
		return "skill".equals(source);
	}

	/** A change's identity: a later record with the same one replaces the earlier (pending, then the reason). */
	String identity() {
		return toolUseId + "|" + file + "|" + start + "-" + end;
	}

	public boolean pending() {
		return reason == null;
	}

	/**
	 * The last record per identity, in the order first seen, with the time of the first: a record's time is when it was
	 * written, so the change was made at its first (pending) one; the text too is the first's, only the pending record
	 * has it. Lines that don't parse (e.g. half written) are skipped; a change whose last record says it was undone is
	 * dropped, as the agent took it back and gave it no reason.
	 */
	static List<EditReason> read(String text) {
		return read(text, null);
	}

	/** As {@link #read(String)}, with {@code session} for records that name none: the file's name. */
	static List<EditReason> read(String text, @Nullable String session) {
		Map<String, EditReason> byIdentity = new LinkedHashMap<>();
		String[] lines = text.split("\n");
		String title = null;
		for (int seq = 0; seq < lines.length; seq++) {
			String line = lines[seq];
			EditReason reason = parse(line.trim(), session, seq);
			if (reason != null && reason.title() != null) {
				title = reason.title();
			}
			if (reason != null && undone(line)) {
				byIdentity.remove(reason.identity());
			}
			else if (reason != null) {
				byIdentity.merge(reason.identity(), reason, (first, last) -> new EditReason(last.toolUseId(), last.tool(),
						last.file(), last.start(), last.end(), first.text() != null ? first.text() : last.text(), last.reason(),
						first.time(), last.source(), last.session(), last.title(), first.seq()));
			}
		}
		// the title is the session's, and Claude Code rewrites it as the session goes on: the file's last one names every reason
		String latest = title;
		return byIdentity.values().stream().<EditReason>map(reason -> latest == null ? reason : new EditReason(reason.toolUseId(),
				reason.tool(), reason.file(), reason.start(), reason.end(), reason.text(), reason.reason(), reason.time(),
				reason.source(), reason.session(), latest, reason.seq())).collect(Collectors.toCollection(ArrayList::new));
	}

	private static @Nullable EditReason parse(String line, @Nullable String fallbackSession, int seq) {
		if (line.isEmpty()) {
			return null;
		}
		try {
			JsonObject json = JsonParser.parseString(line).getAsJsonObject();
			String toolUseId = string(json, "toolUseId");
			Instant time = Instant.parse(string(json, "time"));
			if (toolUseId == null) {
				return null;
			}
			int start = -1;
			int end = -1;
			JsonElement lines = json.get("lines");
			if (lines != null && lines.isJsonArray() && lines.getAsJsonArray().size() == 2) {
				start = lines.getAsJsonArray().get(0).getAsInt();
				end = lines.getAsJsonArray().get(1).getAsInt();
			}
			String reason = string(json, "reason");
			String session = string(json, "session");
			String title = string(json, "title");
			return new EditReason(toolUseId, String.valueOf(string(json, "tool")), string(json, "file"), start, end,
					string(json, "text"), reason == null || reason.isBlank() ? null : reason.strip(), time,
					string(json, "source"), session == null || session.isBlank() ? fallbackSession : session,
					title == null || title.isBlank() ? null : title.strip(), seq);
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	/** True for the record the edit-reasons mod writes for a change the agent made and then undid. */
	private static boolean undone(String line) {
		try {
			return "undone".equals(string(JsonParser.parseString(line.trim()).getAsJsonObject(), "source"));
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	private static @Nullable String string(JsonObject json, String key) {
		JsonElement value = json.get(key);
		return value == null || value.isJsonNull() ? null : value.getAsString();
	}

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());
	/** Within the last week, where the weekday names the day. */
	private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("EEE HH:mm").withZone(ZoneId.systemDefault());
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());

	/** The time alone today, with the weekday within the last week, with the date before: the records span days; the gutter hover and the tree's tooltips both name it so. */
	public static String when(Instant time) {
		LocalDate day = time.atZone(ZoneId.systemDefault()).toLocalDate();
		LocalDate today = LocalDate.now();
		return day.equals(today) ? TIME.format(time)
				: day.isAfter(today.minusDays(7)) ? DAY_TIME.format(time)
				: DATE_TIME.format(time);
	}
}
