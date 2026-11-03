package com.fsodomka.structurediff;

import com.fsodomka.structurediff.StructureDiffComputer.Member;
import com.intellij.openapi.vcs.FileStatus;
import org.jetbrains.jetCheck.Generator;
import org.jetbrains.jetCheck.IntDistribution;
import org.jetbrains.jetCheck.PropertyChecker;
import org.jetbrains.jetCheck.PropertyFalsified;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Properties of {@link StructureDiffComputer#diff} on generated member trees, checked with jetCheck. Names, signatures and
 * body tokens come from small alphabets, so keys, names and texts collide often and every matching pass (key, name, text
 * without name) runs. The name "re" is part of the token "return", so blanking a name must match whole tokens.
 */
public class MemberDiffProperties {
	private static final List<String> NAMES = List.of("a", "b", "c", "re");
	/** A name no generated member has, for renames. */
	private static final String FRESH_NAME = "z";
	private static final List<String> SIGNATURES = List.of("", "()", "(x)");
	private static final List<String> TOKENS = List.of("return", "x", "1", "a", "b", "+", ";");

	private static final Generator<List<Member>> MEMBERS = siblings(siblings(Generator.constant(List.of()), 3), 5);
	private static final Generator<Versions> VERSIONS = Generator.from(env -> new Versions(env.generate(MEMBERS), env.generate(MEMBERS)));
	private static final Generator<Edit> EDITS = Generator.from(env -> new Edit(env.generate(MEMBERS), env.generate(Generator.integers(0, 100)),
			env.generate(Generator.booleans())));

	private record Versions(List<Member> before, List<Member> after) {
	}

	/** Members, which of them to edit, and whether to edit its body too where that is optional. */
	private record Edit(List<Member> members, int pick, boolean editBody) {
	}

	@Test
	public void identicalTreesHaveNoChanges() {
		forAll(MEMBERS, members -> assertEquals(List.of(), StructureDiffComputer.diff(members, members)));
	}

	@Test
	public void everythingIsAddedToAnEmptyFile() {
		forAll(MEMBERS, members -> {
			List<MemberDiff> diff = StructureDiffComputer.diff(List.of(), members);
			assertEquals(members.size(), diff.size());
			assertTrue(diff.stream().allMatch(d -> d.status() == FileStatus.ADDED));
			assertEquals(countMembers(members), count(diff));
		});
	}

	@Test
	public void everythingIsDeletedFromAFileEmptied() {
		forAll(MEMBERS, members -> {
			List<MemberDiff> diff = StructureDiffComputer.diff(members, List.of());
			assertEquals(members.size(), diff.size());
			assertTrue(diff.stream().allMatch(d -> d.status() == FileStatus.DELETED));
		});
	}

	/** Each member shows up at most once on its side: an after member as added or modified, a before member as deleted or modified. */
	@Test
	public void eachMemberIsReportedAtMostOnce() {
		forAll(VERSIONS, v -> {
			List<MemberDiff> diff = StructureDiffComputer.diff(v.before, v.after);
			Set<String> afterKeys = new HashSet<>();
			Set<String> beforeKeys = new HashSet<>();
			for (MemberDiff d : diff) {
				if (d.status() != FileStatus.DELETED) {
					assertTrue("after key twice: " + d.key(), afterKeys.add(d.key()));
				}
				if (d.status() != FileStatus.ADDED) {
					assertTrue("before key twice: " + d.beforeKey(), beforeKeys.add(d.beforeKey()));
				}
			}
			assertTrue(keys(v.after).containsAll(afterKeys));
			assertTrue(keys(v.before).containsAll(beforeKeys));
		});
	}

	/** Swapping the versions swaps additions and deletions; the pairing does not depend on the direction. */
	@Test
	public void swappingTheVersionsSwapsAddedAndDeleted() {
		forAll(VERSIONS, v -> {
			Map<FileStatus, Integer> forward = statuses(StructureDiffComputer.diff(v.before, v.after));
			Map<FileStatus, Integer> backward = statuses(StructureDiffComputer.diff(v.after, v.before));
			assertEquals(forward.getOrDefault(FileStatus.DELETED, 0), backward.getOrDefault(FileStatus.ADDED, 0));
			assertEquals(forward.getOrDefault(FileStatus.ADDED, 0), backward.getOrDefault(FileStatus.DELETED, 0));
			assertEquals(forward.getOrDefault(FileStatus.MODIFIED, 0), backward.getOrDefault(FileStatus.MODIFIED, 0));
		});
	}

	/** Editing the body of one top-level member reports exactly that member, as modified, under its own key. */
	@Test
	public void editingOneBodyModifiesOnlyThatMember() {
		forAll(EDITS, e -> {
			List<Member> members = e.members;
			if (members.isEmpty()) {
				return;
			}
			int index = e.pick % members.size();
			Member m = members.get(index);
			List<Member> edited = new ArrayList<>(members);
			edited.set(index, new Member(m.key(), m.label(), null, null, m.text() + "\nedited", m.normalizedText() + "edited ", m.offset(),
					m.container(), m.children()));
			List<MemberDiff> diff = StructureDiffComputer.diff(members, edited);
			assertEquals(1, diff.size());
			MemberDiff d = diff.getFirst();
			assertEquals(FileStatus.MODIFIED, d.status());
			assertEquals(m.key(), d.key());
			assertEquals(m.key(), d.beforeKey());
			assertEquals(1, d.linesAdded());
			assertEquals(List.of(), d.children());
		});
	}

	/**
	 * Changing the signature of one member pairs it by name: one modified member, under its new and old key. With the body
	 * edited too, only the name pass can pair it; with the body unchanged, the text pass would as well.
	 */
	@Test
	public void changingOneSignatureModifiesOnlyThatMember() {
		forAll(EDITS, e -> {
			List<Member> members = e.members;
			if (members.isEmpty()) {
				return;
			}
			int index = e.pick % members.size();
			Member m = members.get(index);
			String name = StructureDiffComputer.name(m.label());
			Set<String> taken = new HashSet<>();
			members.forEach(o -> taken.add(o.label()));
			String label = SIGNATURES.stream().map(sig -> name + sig).filter(l -> !taken.contains(l)).findFirst().orElse(null);
			if (label == null) {
				return;
			}
			List<Member> edited = new ArrayList<>(members);
			String text = e.editBody ? m.text() + "\nedited" : m.text();
			String normalized = e.editBody ? m.normalizedText() + "edited " : m.normalizedText();
			edited.set(index, new Member(label, label, null, null, text, normalized, m.offset(), m.container(), m.children()));
			assertOneModified(StructureDiffComputer.diff(members, edited), label, m.key());
		});
	}

	/** Renaming one member, and its uses in its body, pairs it by the text without the name: one modified member. */
	@Test
	public void renamingOneMemberModifiesOnlyThatMember() {
		forAll(EDITS, e -> {
			List<Member> members = e.members;
			if (members.isEmpty()) {
				return;
			}
			int index = e.pick % members.size();
			Member m = members.get(index);
			String name = StructureDiffComputer.name(m.label());
			// known limitation: the name is blanked by a non-overlapping replace, so of two adjacent uses only the first is
			if ((" " + m.normalizedText()).contains(" " + name + " " + name + " ")) {
				return;
			}
			String label = FRESH_NAME + m.label().substring(name.length());
			List<Member> edited = new ArrayList<>(members);
			edited.set(index, new Member(label, label, null, null, renamed(m.text(), "\n", name), renamed(m.normalizedText(), " ", name),
					m.offset(), m.container(), m.children()));
			assertOneModified(StructureDiffComputer.diff(members, edited), label, m.key());
		});
	}

	/** Editing the body of a nested member reports its parent as modified, with only that child modified. */
	@Test
	public void editingANestedBodyModifiesItsParent() {
		forAll(EDITS, e -> {
			List<Member> members = e.members;
			List<Integer> parents = new ArrayList<>();
			for (int i = 0; i < members.size(); i++) {
				if (!members.get(i).children().isEmpty()) {
					parents.add(i);
				}
			}
			if (parents.isEmpty()) {
				return;
			}
			int index = parents.get(e.pick % parents.size());
			Member parent = members.get(index);
			List<Member> children = new ArrayList<>(parent.children());
			Member child = children.get(e.pick % children.size());
			children.set(children.indexOf(child), new Member(child.key(), child.label(), null, null, child.text() + "\nedited",
					child.normalizedText() + "edited ", child.offset(), child.container(), child.children()));
			List<Member> edited = new ArrayList<>(members);
			edited.set(index, new Member(parent.key(), parent.label(), null, null, parent.text(), parent.normalizedText(), parent.offset(),
					parent.container(), children));
			List<MemberDiff> diff = StructureDiffComputer.diff(members, edited);
			assertOneModified(diff, parent.key(), parent.key());
			assertEquals(1, diff.getFirst().children().size());
			MemberDiff d = diff.getFirst().children().getFirst();
			assertEquals(FileStatus.MODIFIED, d.status());
			assertEquals(child.key(), d.key());
		});
	}

	@Test
	public void nameHasNoParametersOrType() {
		forAll(Generator.stringsOf(IntDistribution.uniform(0, 12), Generator.charsFrom("ab (x):,")), label -> {
			String name = StructureDiffComputer.name(label);
			assertFalse(name.contains("(") || name.contains(":"));
			assertEquals(name, StructureDiffComputer.name(name));
		});
	}

	/**
	 * Checks {@code check} on generated values; it fails by throwing, such as by a failed assertion, and returns to skip a value.
	 * The test report shows the cause of {@link PropertyFalsified} rather than its message, which holds the shrunk
	 * counterexample and how to replay it, so this rethrows the message alone (it includes the cause's trace).
	 */
	private static <T> void forAll(Generator<T> generator, Consumer<T> check) {
		try {
			PropertyChecker.forAll(generator, value -> {
				check.accept(value);
				return true;
			});
		} catch (PropertyFalsified e) {
			throw new AssertionError(e.getMessage());
		}
	}

	/** Up to {@code max} siblings with unique keys: the label, with "#n" on its repeats, as in the computer. */
	private static Generator<List<Member>> siblings(Generator<List<Member>> children, int max) {
		Generator<String> labels = Generator.zipWith(Generator.sampledFrom(NAMES), Generator.sampledFrom(SIGNATURES), String::concat);
		Generator<List<String>> bodies = Generator.listsOf(IntDistribution.uniform(0, 4), Generator.sampledFrom(TOKENS));
		Generator<Spec> spec = Generator.from(env -> new Spec(env.generate(labels), env.generate(bodies), env.generate(children)));
		return Generator.listsOf(IntDistribution.uniform(0, max), spec).map(specs -> {
			Map<String, Integer> repeats = new HashMap<>();
			List<Member> result = new ArrayList<>();
			for (Spec s : specs) {
				int n = repeats.merge(s.label, 1, Integer::sum);
				String key = n == 1 ? s.label : s.label + "#" + n;
				// as computed: each token followed by a space
				String normalized = s.body.stream().map(t -> t + " ").collect(Collectors.joining());
				result.add(new Member(key, s.label, null, null, String.join("\n", s.body), normalized, result.size(), !s.children.isEmpty(),
						s.children));
			}
			return result;
		});
	}

	private record Spec(String label, List<String> body, List<Member> children) {
	}

	private static void assertOneModified(List<MemberDiff> diff, String key, String beforeKey) {
		assertEquals(diff.toString(), 1, diff.size());
		MemberDiff d = diff.getFirst();
		assertEquals(FileStatus.MODIFIED, d.status());
		assertEquals(key, d.key());
		assertEquals(beforeKey, d.beforeKey());
	}

	/** {@code text} with each whole token {@code name} replaced by {@link #FRESH_NAME}. */
	private static String renamed(String text, String separator, String name) {
		if (text.isEmpty()) {
			return text;
		}
		return Arrays.stream(text.split(separator, -1)).map(t -> t.equals(name) ? FRESH_NAME : t).collect(Collectors.joining(separator));
	}

	private static Set<String> keys(List<Member> members) {
		Set<String> keys = new HashSet<>();
		members.forEach(m -> keys.add(m.key()));
		return keys;
	}

	private static Map<FileStatus, Integer> statuses(List<MemberDiff> diff) {
		Map<FileStatus, Integer> result = new HashMap<>();
		diff.forEach(d -> result.merge(d.status(), 1, Integer::sum));
		return result;
	}

	private static int count(List<MemberDiff> diff) {
		return diff.stream().mapToInt(d -> 1 + count(d.children())).sum();
	}

	private static int countMembers(List<Member> members) {
		return members.stream().mapToInt(m -> 1 + countMembers(m.children())).sum();
	}
}
