package com.fsodomka.structurediff;

import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/** The nodes of the Structure Diff tree: their user objects, stable keys across refreshes, and lookups. */
final class DiffTreeNodes {
	private DiffTreeNodes() {
	}

	/** Group node of one VCS root. */
	record RepoNode(VirtualFile root) {
	}

	/**
	 * Whether files are grouped under {@link RepoNode}s: when any comes from a VCS root other than the project's own, which
	 * includes files from several roots, so a change outside the project's repository always shows which repository it is in.
	 * Without a {@code projectRoot}, when the project is in no repository, only when the files come from several roots.
	 */
	static boolean groupByRoot(Collection<VirtualFile> fileRoots, @Nullable VirtualFile projectRoot) {
		return projectRoot == null ? fileRoots.stream().distinct().count() > 1 : fileRoots.stream().anyMatch(r -> !r.equals(projectRoot));
	}

	/** User object of a node that disappeared in the last refresh. */
	record Ghost(Object item) {
	}

	/**
	 * Path of node keys from the root, stable across refreshes. Without the repository node above a file, as a file path
	 * is unique across repositories: the keys stay the same when the tree starts or stops grouping files by repository.
	 */
	static List<String> keyOf(DefaultMutableTreeNode node) {
		List<String> key = new ArrayList<>();
		Object[] path = node.getUserObjectPath();
		for (int i = 0; i < path.length; i++) {
			if (i == path.length - 1 || !(path[i] instanceof RepoNode)) {
				key.add(keyPart(path[i]));
			}
		}
		return key;
	}

	static String keyPart(Object userObject) {
		if (userObject instanceof Ghost(Object item)) {
			return keyPart(item);
		}
		if (userObject instanceof RepoNode(VirtualFile root)) {
			return "repo:" + root.getPath();
		}
		if (userObject instanceof FileDiff file) {
			return file.path();
		}
		return userObject instanceof MemberDiff member ? member.key() : "";
	}

	/**
	 * Whether {@code userObject} is a file, whose path is unique across repositories: keying collapsed nodes from it
	 * keeps them collapsed when the tree starts or stops grouping files by repository.
	 */
	static boolean isFile(Object userObject) {
		return (userObject instanceof Ghost(Object item) ? item : userObject) instanceof FileDiff;
	}

	static FileStatus statusOf(Object userObject) {
		if (userObject instanceof FileDiff file) {
			return file.status();
		}
		return userObject instanceof MemberDiff member ? member.status() : null;
	}

	/** Collects nodes by key, without ghosts; the key of the invisible root is [""]. */
	static void collectNodes(DefaultMutableTreeNode node, List<String> key, Map<List<String>, DefaultMutableTreeNode> nodes) {
		List<String> nodeKey = key.isEmpty() ? List.of("") : key;
		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			if (child.getUserObject() instanceof Ghost) {
				continue;
			}
			List<String> childKey = new ArrayList<>(nodeKey);
			childKey.add(keyPart(child.getUserObject()));
			nodes.put(childKey, child);
			// see keyOf
			collectNodes(child, child.getUserObject() instanceof RepoNode ? key : childKey, nodes);
		}
	}

	/** Finds the node with the given {@link #keyOf} path; the first part is the invisible root. */
	static DefaultMutableTreeNode find(DefaultMutableTreeNode root, List<String> key) {
		DefaultMutableTreeNode node = root;
		for (String part : key.subList(1, key.size())) {
			node = child(node, part);
			if (node == null) {
				return null;
			}
		}
		return node;
	}

	/** The child of {@code node} with the key part {@code part}, also one under a repository node (see {@link #keyOf}). */
	static DefaultMutableTreeNode child(DefaultMutableTreeNode node, String part) {
		for (int i = 0; i < node.getChildCount(); i++) {
			DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
			if (keyPart(child.getUserObject()).equals(part)) {
				return child;
			}
			if (child.getUserObject() instanceof RepoNode) {
				DefaultMutableTreeNode found = child(child, part);
				if (found != null) {
					return found;
				}
			}
		}
		return null;
	}

	/** Hides changes below non-container members (functions, components, variables); shown with "Show Nested Changes". */
	static MemberDiff prune(MemberDiff member) {
		return member.withChildren(member.container()
				? member.children().stream().map(DiffTreeNodes::prune).toList()
				: List.of());
	}

	/** Counts added/modified/removed members; an added or removed subtree counts once, a container only through its children. */
	static void count(List<MemberDiff> members, int[] counts) {
		for (MemberDiff member : members) {
			if (member.status() == FileStatus.ADDED) {
				counts[0]++;
			}
			else if (member.status() == FileStatus.DELETED) {
				counts[2]++;
			}
			else if (member.children().isEmpty()) {
				counts[1]++;
			}
			else {
				count(member.children(), counts);
			}
		}
	}

	static DefaultMutableTreeNode memberNode(MemberDiff member) {
		DefaultMutableTreeNode node = new DefaultMutableTreeNode(member);
		for (MemberDiff child : member.children()) {
			node.add(memberNode(child));
		}
		return node;
	}

	/** The file of {@code node} or of its nearest file ancestor; null when there is none. */
	static FileDiff fileOf(DefaultMutableTreeNode node) {
		DefaultMutableTreeNode fileNode = fileNode(node);
		return fileNode == null ? null : (FileDiff) fileNode.getUserObject();
	}

	/** {@code node} if it is a file node, else its nearest file ancestor; null when there is none. */
	static DefaultMutableTreeNode fileNode(DefaultMutableTreeNode node) {
		while (node != null && !(node.getUserObject() instanceof FileDiff)) {
			node = (DefaultMutableTreeNode) node.getParent();
		}
		return node;
	}

	/** The member of {@code node} and its enclosing members, outermost first; empty when it is no member. */
	static List<MemberDiff> memberPath(DefaultMutableTreeNode node) {
		List<MemberDiff> path = new ArrayList<>();
		for (; node != null && node.getUserObject() instanceof MemberDiff pathMember; node = (DefaultMutableTreeNode) node.getParent()) {
			path.addFirst(pathMember);
		}
		return path;
	}
}
