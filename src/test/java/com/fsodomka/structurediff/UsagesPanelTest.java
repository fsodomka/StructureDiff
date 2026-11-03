package com.fsodomka.structurediff;

import com.fsodomka.structurediff.MemberLinks.Link;
import com.fsodomka.structurediff.UsagesPanel.Entry;
import com.fsodomka.structurediff.UsagesPanel.Heading;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.Collections;
import java.util.List;

public class UsagesPanelTest extends BasePlatformTestCase {
	private static Link link(VirtualFile file, boolean internal, String module, String... keys) {
		return new Link(file, 0, 0, List.of(keys), List.of(keys), Collections.nCopies(keys.length, null), module, 1, internal, false);
	}

	/** The rows below {@code node}, indented by depth; a member without references in brackets, a file name after a colon. */
	private static void describe(DefaultMutableTreeNode node, String indent, StringBuilder out) {
		Object item = node.getUserObject();
		if (item instanceof Heading heading) {
			out.append(indent).append(heading.text()).append(' ').append(heading.detail()).append('\n');
		}
		else if (item instanceof Entry entry) {
			String label = entry.link() == null ? "[" + entry.label() + "]" : entry.label();
			out.append(indent).append(label).append(entry.file() == null ? "" : " : " + entry.file()).append('\n');
		}
		for (int i = 0; i < node.getChildCount(); i++) {
			describe((DefaultMutableTreeNode) node.getChildAt(i), indent + "  ", out);
		}
	}

	public void testMembersNestByPathAndModulesShowOnlyWhenSeveral() {
		VirtualFile money = myFixture.addFileToProject("Money.java", "").getVirtualFile();
		VirtualFile util = myFixture.addFileToProject("Util.java", "").getVirtualFile();
		DefaultMutableTreeNode section = UsagesPanel.section(null, "Uses", List.of(
				link(money, true, "main", "Money", "round()"),
				link(money, true, "main", "Money", "Inner", "x"),
				link(money, true, "main", "Money"),
				link(util, false, "main", "Helpers", "help()")), null);
		StringBuilder out = new StringBuilder();
		describe(section, "", out);
		assertEquals("""
				Uses 4 members
				  Internal 3
				    Money
				      round()
				      [Inner]
				        x
				  External 1
				    [Helpers] : Util.java
				      help()
				""", out.toString());
	}
}
