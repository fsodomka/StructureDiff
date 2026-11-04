package com.fsodomka.structurediff;

import com.fsodomka.structurediff.ChangeFlashes.Flash;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.intellij.openapi.vcs.FileStatus;
import org.jetbrains.jetCheck.Generator;
import org.junit.Test;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.*;

import static com.fsodomka.structurediff.PropertyChecks.forAll;
import static org.junit.Assert.*;

/**
 * Properties of {@link ChangeFlashes#newFlashes} on generated pairs of trees, checked with jetCheck. Paths, member keys,
 * statuses and change ids come from small sets, so the two trees share many nodes, and a node kept often changes its
 * status or content. A file's members are null, when not computed yet, or its member nodes.
 */
public class ChangeFlashesProperties {
	private static final List<String> PATHS = List.of("/p/a.java", "/p/b.java", "/p/c.java");
	private static final List<String> KEYS = List.of("m", "n");
	private static final List<FileStatus> STATUSES = List.of(FileStatus.ADDED, FileStatus.MODIFIED, FileStatus.DELETED);
	private static final long NOW = 1000;

	private static final Generator<DefaultMutableTreeNode> TREES = Generator.from(env -> {
		DefaultMutableTreeNode root = new DefaultMutableTreeNode();
		for (String path : PATHS) {
			if (env.generate(Generator.booleans())) {
				List<MemberDiff> members = env.generate(Generator.booleans()) ? env.generate(members(2)) : null;
				DefaultMutableTreeNode file = new DefaultMutableTreeNode(
						new FileDiff(null, path, null, env.generate(Generator.sampledFrom(STATUSES)), "", "", 1, 1, members));
				addMembers(file, members == null ? List.of() : members);
				root.add(file);
			}
		}
		return root;
	});

	/** The running flashes are of nodes of the old tree: {@link ChangeFlashes#update} drops a ghost's flash when its key returns. */
	private static final Generator<Change> CHANGES = Generator.from(env -> {
		DefaultMutableTreeNode before = env.generate(TREES);
		DefaultMutableTreeNode after = env.generate(TREES);
		Set<List<String>> running = new HashSet<>();
		for (List<String> key : sorted(nodes(before).keySet())) {
			if (env.generate(Generator.booleans())) {
				running.add(key);
			}
		}
		return new Change(before, after, running);
	});

	private record Change(DefaultMutableTreeNode before, DefaultMutableTreeNode after, Set<List<String>> running) {
		@Override
		public String toString() {
			return "before:\n" + describe(before, "") + "after:\n" + describe(after, "") + "running: " + sorted(running);
		}
	}

	private static Generator<List<MemberDiff>> members(int depth) {
		return Generator.from(env -> {
			List<MemberDiff> members = new ArrayList<>();
			for (String key : KEYS) {
				if (env.generate(Generator.booleans())) {
					List<MemberDiff> children = depth > 1 ? env.generate(members(depth - 1)) : List.of();
					members.add(new MemberDiff(key, key, key, key, null, env.generate(Generator.sampledFrom(STATUSES)), 0, 0, 1, 1, true,
							env.generate(Generator.integers(0, 1)), children));
				}
			}
			return members;
		});
	}

	private static void addMembers(DefaultMutableTreeNode parent, List<MemberDiff> members) {
		for (MemberDiff member : members) {
			DefaultMutableTreeNode node = new DefaultMutableTreeNode(member);
			parent.add(node);
			addMembers(node, member.children());
		}
	}

	private static Map<List<String>, DefaultMutableTreeNode> nodes(DefaultMutableTreeNode root) {
		Map<List<String>, DefaultMutableTreeNode> nodes = new HashMap<>();
		DiffTreeNodes.collectNodes(root, new ArrayList<>(), nodes);
		return nodes;
	}

	private static Map<List<String>, Flash> flashes(Change c, Set<List<String>> running) {
		return ChangeFlashes.newFlashes(nodes(c.before), nodes(c.after), running, NOW);
	}

	private static FileStatus status(DefaultMutableTreeNode node) {
		return DiffTreeNodes.statusOf(node.getUserObject());
	}

	private static DefaultMutableTreeNode copy(DefaultMutableTreeNode node) {
		DefaultMutableTreeNode copy = new DefaultMutableTreeNode(node.getUserObject());
		for (int i = 0; i < node.getChildCount(); i++) {
			copy.add(copy((DefaultMutableTreeNode) node.getChildAt(i)));
		}
		return copy;
	}

	private static List<List<String>> sorted(Collection<List<String>> keys) {
		return keys.stream().sorted(Comparator.comparing(Object::toString)).toList();
	}

	private static String describe(DefaultMutableTreeNode node, String indent) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			text.append(indent).append(switch (child.getUserObject()) {
				case FileDiff file -> file.path() + " " + file.status() + (file.members() == null ? " (members not computed)" : "");
				case MemberDiff member -> member.key() + " " + member.status() + " #" + member.changeId();
				default -> String.valueOf(child.getUserObject());
			}).append('\n').append(describe(child, indent + "  "));
		}
		return text.toString();
	}

	@Test
	public void anUnchangedTreeDoesNotFlash() {
		forAll(CHANGES, c -> assertEquals(Map.of(),
				ChangeFlashes.newFlashes(nodes(c.before), nodes(copy(c.before)), c.running, NOW)));
	}

	/** A flash shows its node: the new status of one there, the old status and place of one that vanished. */
	@Test
	public void eachFlashDescribesItsNode() {
		forAll(CHANGES, c -> {
			Map<List<String>, DefaultMutableTreeNode> before = nodes(c.before);
			Map<List<String>, DefaultMutableTreeNode> after = nodes(c.after);
			ChangeFlashes.newFlashes(before, after, c.running, NOW).forEach((key, flash) -> {
				DefaultMutableTreeNode was = before.get(key);
				DefaultMutableTreeNode is = after.get(key);
				assertEquals(key.toString(), was == null, flash.appeared());
				assertEquals(NOW, flash.start());
				if (is == null) {
					assertNotNull(key.toString(), was);
					assertSame(key.toString(), was.getUserObject(), flash.vanished());
					assertEquals(key.toString(), was.getParent().getIndex(was), flash.index());
					assertEquals(key.toString(), status(was), flash.status());
				}
				else {
					assertNull(key.toString(), flash.vanished());
					assertEquals(key.toString(), status(is), flash.status());
				}
			});
		});
	}

	/** A removed subtree flashes as one ghost, of its topmost node, so the ghost has a parent to be shown under. */
	@Test
	public void aGhostIsTheTopmostRemovedNode() {
		forAll(CHANGES, c -> {
			Map<List<String>, DefaultMutableTreeNode> after = nodes(c.after);
			flashes(c, c.running).forEach((key, flash) -> {
				List<String> parent = key.subList(0, key.size() - 1);
				if (flash.vanished() != null) {
					assertTrue(key.toString(), parent.size() == 1 || after.containsKey(parent));
				}
			});
		});
	}

	/** A file flashes when it appears, vanishes or changes status; not when it stays modified with other content. */
	@Test
	public void aFileFlashesExactlyWhenItAppearsVanishesOrChangesStatus() {
		forAll(CHANGES, c -> {
			Map<List<String>, DefaultMutableTreeNode> before = nodes(c.before);
			Map<List<String>, DefaultMutableTreeNode> after = nodes(c.after);
			Map<List<String>, Flash> flashes = flashes(c, Set.of());
			Set<List<String>> files = new HashSet<>(before.keySet());
			files.addAll(after.keySet());
			files.removeIf(key -> key.size() != 2);
			for (List<String> key : files) {
				boolean expected = !before.containsKey(key) || !after.containsKey(key) || status(before.get(key)) != status(after.get(key));
				assertEquals(key.toString(), expected, flashes.containsKey(key));
			}
		});
	}

	/**
	 * A member kept in a file whose members were computed flashes when its status changes or it is modified again, with
	 * a new change id, and not otherwise. Its old node is there, so its file had member nodes, so computed members.
	 */
	@Test
	public void aKeptMemberFlashesExactlyWhenItsStatusOrContentChanged() {
		forAll(CHANGES, c -> {
			Map<List<String>, DefaultMutableTreeNode> before = nodes(c.before);
			Map<List<String>, DefaultMutableTreeNode> after = nodes(c.after);
			Map<List<String>, Flash> flashes = flashes(c, Set.of());
			after.forEach((key, node) -> {
				if (key.size() > 2 && before.containsKey(key)) {
					MemberDiff was = (MemberDiff) before.get(key).getUserObject();
					MemberDiff is = (MemberDiff) node.getUserObject();
					assertEquals(key.toString(), was.status() != is.status() || was.changeId() != is.changeId(), flashes.containsKey(key));
				}
			});
		});
	}

	/**
	 * A running flash suppresses only the content change of its own node: the flashes are those without running flashes,
	 * less the running nodes still there with the same status. A status change or a removal still flashes.
	 */
	@Test
	public void aRunningFlashSuppressesOnlyItsOwnNode() {
		forAll(CHANGES, c -> {
			Map<List<String>, DefaultMutableTreeNode> before = nodes(c.before);
			Map<List<String>, DefaultMutableTreeNode> after = nodes(c.after);
			Set<List<String>> expected = new HashSet<>(flashes(c, Set.of()).keySet());
			expected.removeIf(key -> c.running.contains(key) && after.containsKey(key) && status(before.get(key)) == status(after.get(key)));
			assertEquals(sorted(expected), sorted(flashes(c, c.running).keySet()));
		});
	}

	/**
	 * Members flash only against a known structure: not those of a file new to the tree unless it is modified (they come
	 * with an added file), nor those of a file whose members were not computed on the other side (its revision loading).
	 */
	@Test
	public void membersFlashOnlyAgainstAKnownStructure() {
		forAll(CHANGES, c -> {
			Map<List<String>, DefaultMutableTreeNode> before = nodes(c.before);
			Map<List<String>, DefaultMutableTreeNode> after = nodes(c.after);
			flashes(c, Set.of()).forEach((key, flash) -> {
				if (key.size() == 2) {
					return;
				}
				List<String> fileKey = key.subList(0, 2);
				boolean vanished = flash.vanished() != null;
				DefaultMutableTreeNode file = (vanished ? before : after).get(fileKey);
				DefaultMutableTreeNode otherFile = (vanished ? after : before).get(fileKey);
				if (otherFile == null) {
					assertEquals(key.toString(), FileStatus.MODIFIED, status(file));
				}
				else {
					assertNotNull(key.toString(), ((FileDiff) otherFile.getUserObject()).members());
				}
			});
		});
	}
}
