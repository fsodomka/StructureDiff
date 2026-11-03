package com.fsodomka.structurediff;

import com.fsodomka.structurediff.UsagesPanel.Entry;
import com.fsodomka.structurediff.UsagesPanel.Heading;
import com.fsodomka.structurediff.diff.FileDiff;
import com.fsodomka.structurediff.diff.MemberDiff;
import com.fsodomka.structurediff.diff.StructureDiffComputer;
import com.fsodomka.structurediff.source.ChangeSource;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.impl.NonBlockingReadActionImpl;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.vcs.FileStatus;
import com.intellij.openapi.vcs.changes.Change;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.changes.SimpleContentRevision;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import com.intellij.vcsUtil.VcsUtil;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Show Usages in the panel: the usages follow edits to the selected member when the tree is refreshed. */
public class UsagesFlowTest extends BasePlatformTestCase {
	private static final String SHOW_USAGES_KEY = "structureDiff.showUsages";
	private static final String BEFORE = """
			package p;

			public class Money {
				int round(int cents) { return cents; }

				int helper() { return 1; }

				int format() { return 2; }
			}
			""";

	private VirtualFile money;
	private StructureDiffPanel panel;

	@Override
	protected void setUp() throws Exception {
		super.setUp();
		PropertiesComponent.getInstance().setValue(SHOW_USAGES_KEY, true);
		money = myFixture.addFileToProject("p/Money.java", BEFORE.replace("return cents;", "return cents + helper();")).getVirtualFile();
		panel = new StructureDiffPanel(getProject(), new NoChanges());
		Disposer.register(getTestRootDisposable(), panel);
	}

	@Override
	protected void tearDown() throws Exception {
		try {
			PropertiesComponent.getInstance().unsetValue(SHOW_USAGES_KEY);
		}
		finally {
			super.tearDown();
		}
	}

	public void testUsagesAreSearchedAgainWhenTheSelectedMemberIsEdited() {
		refresh();
		select("round(int): int");
		waitForUsages();
		assertEquals(List.of("helper(): int"), uses());

		WriteCommandAction.runWriteCommandAction(getProject(), () -> {
			Document document = FileDocumentManager.getInstance().getDocument(money);
			document.setText(document.getText().replace("cents + helper();", "cents + helper() + format();"));
			PsiDocumentManager.getInstance(getProject()).commitDocument(document);
		});
		refresh();
		waitForUsages();
		assertEquals(List.of("helper(): int", "format(): int"), uses());
	}

	public void testAnUnchangedRefreshKeepsTheUsagesWithoutSearching() {
		refresh();
		select("round(int): int");
		waitForUsages();
		DefaultMutableTreeNode shown = panel.usagesPanel().root();

		refresh();
		waitForUsages();
		assertSame("no new search, so the same links are shown", shown, panel.usagesPanel().root());
	}

	/** Shows the working copy of Money.java against {@link #BEFORE}, as a refresh does. */
	private void refresh() {
		Document document = FileDocumentManager.getInstance().getDocument(money);
		CurrentContentRevision after = new CurrentContentRevision(VcsUtil.getFilePath(money.getPath(), false)) {
			@Override
			public VirtualFile getVirtualFile() {
				return money; // the light fixture's files are not on the local file system
			}
		};
		Change change = new Change(new SimpleContentRevision(BEFORE, after.getFile(), "base"), after);
		FileDiff file = new FileDiff(change, money.getPath(), money.getFileType(), FileStatus.MODIFIED, BEFORE, document.getText(), 1, 1, null);
		panel.showLoaded(List.of(StructureDiffComputer.withMembers(getProject(), file)));
	}

	private void select(String memberKey) {
		DefaultMutableTreeNode root = (DefaultMutableTreeNode) panel.tree().getModel().getRoot();
		for (var nodes = root.depthFirstEnumeration(); nodes.hasMoreElements(); ) {
			DefaultMutableTreeNode node = (DefaultMutableTreeNode) nodes.nextElement();
			if (node.getUserObject() instanceof MemberDiff member && member.key().equals(memberKey)) {
				panel.tree().setSelectionPath(new TreePath(node.getPath()));
				return;
			}
		}
		fail("no member " + memberKey + " in the tree");
	}

	private static void waitForUsages() {
		for (int i = 0; i < 2; i++) {
			NonBlockingReadActionImpl.waitForAsyncTaskCompletion();
			PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue();
		}
	}

	/** The labels of the linked members under "Uses", in order. */
	private List<String> uses() {
		DefaultMutableTreeNode root = panel.usagesPanel().root();
		for (int i = 0; i < root.getChildCount(); i++) {
			DefaultMutableTreeNode section = (DefaultMutableTreeNode) root.getChildAt(i);
			if (section.getUserObject() instanceof Heading heading && heading.text().equals("Uses")) {
				List<String> labels = new ArrayList<>();
				for (var nodes = section.preorderEnumeration(); nodes.hasMoreElements(); ) {
					if (((DefaultMutableTreeNode) nodes.nextElement()).getUserObject() instanceof Entry entry && entry.link() != null) {
						labels.add(entry.label());
					}
				}
				return labels;
			}
		}
		return List.of();
	}

	private static final class NoChanges implements ChangeSource {
		@Override
		public Collection<Change> changes() {
			return List.of();
		}

		@Override
		public void subscribe(Runnable onChange, Disposable parent) {
		}

		@Override
		public boolean highlightChanges() {
			return false;
		}
	}
}
