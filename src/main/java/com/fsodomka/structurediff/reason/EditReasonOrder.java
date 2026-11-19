package com.fsodomka.structurediff.reason;

import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * The walkthrough order of edit reasons: grouped into runs by session, runs by their earliest reason. Within an agent
 * session by the time of each change, one tool call's ranges together in record order; within a skill run by record
 * order, which is the reading order the skill wrote (its times are all the same). Pending and fileless reasons are left
 * out. Pure: no platform calls.
 */
public final class EditReasonOrder {
	/**
	 * One session's reasons in order.
	 *
	 * @param session null for records that name none and came from no file
	 * @param skill   a skill run rather than an agent session
	 */
	public record Run<T>(@Nullable String session, boolean skill, Instant earliest, List<T> items) {
	}

	/** The runs of {@code items}, each item's reason given by {@code reasonOf}. */
	public static <T> List<Run<T>> runs(Collection<T> items, Function<T, EditReason> reasonOf) {
		Map<String, List<T>> bySession = new LinkedHashMap<>();
		for (T item : items) {
			EditReason reason = reasonOf.apply(item);
			if (!reason.pending() && reason.file() != null) {
				bySession.computeIfAbsent(Objects.requireNonNullElse(reason.session(), ""), key -> new ArrayList<>()).add(item);
			}
		}
		List<Run<T>> runs = new ArrayList<>();
		bySession.forEach((session, list) -> {
			boolean skill = list.stream().allMatch(item -> reasonOf.apply(item).skill());
			Comparator<T> bySeq = Comparator.comparingInt(item -> reasonOf.apply(item).seq());
			list.sort(skill ? bySeq : Comparator.<T, Instant>comparing(item -> reasonOf.apply(item).time()).thenComparing(bySeq));
			Instant earliest = list.stream().map(item -> reasonOf.apply(item).time()).min(Comparator.naturalOrder()).orElseThrow();
			runs.add(new Run<>(session.isEmpty() ? null : session, skill, earliest, List.copyOf(list)));
		});
		runs.sort(Comparator.<Run<T>, Instant>comparing(Run::earliest)
				.thenComparing(run -> Objects.requireNonNullElse(run.session(), "")));
		return runs;
	}

	private EditReasonOrder() {
	}
}
