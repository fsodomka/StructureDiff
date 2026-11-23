# Structure Diff

IntelliJ plugin for reviewing what AI coding agents changed — **Claude Code**, **OpenAI Codex**, **DeepSeek**, Cursor,
Copilot, Gemini CLI and others. It shows changes at member level (added, modified and removed classes, methods and
fields) in a tree like the Structure tool window, and shows AI code-review findings (SARIF) right in the editor.
Works for every language with a Structure view (Java, Kotlin, TypeScript, JavaScript, ...).

[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/35062-structure-diff.svg)](https://plugins.jetbrains.com/plugin/35062-structure-diff)

## Structure Diff tool window

- **Modes**: **Uncommitted** changes; **Branch vs main** — everything the branch changed since it forked from
  `main`/`master`, like a merge request (needs Git); **Follow Git Log** — the commits selected in the Git log.
- **Export Diff for Review** — saves the changes to `reviews/<timestamp>-changes.diff` and copies the
  review skill command for it to paste into your agent. Changes in several repositories: the selected repository is
  exported on its own, otherwise each repository gets a `<timestamp>-<repo>-changes.diff` and the command lists them
  all. A new `reviews/` gets a `.gitignore`. The directory, the
  command (default `/adversarial-review`) and whether to copy it are set in **Settings | Tools | Structure
  Diff**.
- Toggles, in the toolbar's **View Options** dropdown: **Show Nested Changes** (inside functions and object literals), **Show Unversioned Files** (except files
  directly in the repository root),
  **Flash Changes** (briefly highlight what appeared, changed or disappeared; in the treemap, new boxes grow in and
  removals are not shown).
- Per file and member: `+` added, `~` modified, `−` removed counts, changed lines, renames/moves, and old vs new
  signature in the tooltip; test files have the test icon. Multiple VCS roots are grouped.
- The IDE's errors and warnings on changed members, as icons and in the tooltip (levels in **Settings | Tools |
  Structure Diff**); only files open in an editor have them, as the IDE analyses no others.
- **Double-click**/**Enter** jumps to source, **middle-click** opens the diff at the member; context menu: Show Diff,
  Jump to Source, Show Member History, Copy Path.
- **Show as Treemap** shows the changes as nested boxes sized by changed lines and colored by status (removed members
  red); test files have the test icon. Click a box's header to zoom in, the breadcrumb or **Backspace** to zoom out; arrows move between sibling
  boxes, **Enter** zooms in or jumps to source. Double-click, middle-click, the context menu and finding icons work as
  in the tree.
- **Treemap colors**, chosen in the summary row while the treemap shows, with a legend below it: **Status**;
  **File Type** (one hue per type, the
  most changed first); **Coverage** (with the bundled Coverage plugin: the share of each member's lines the selected
  *Run with Coverage* executed, orange for none to blue for all, hatched when the file was edited since; reopen a
  closed run in **Run | Manage Coverage Reports**); **Churn** (in Branch vs main and Follow Git Log: how often the
  lines of each member were changed by the commits shown, plus the working copy in Branch vs main, following renames;
  green for new code, orange for 2+ changes per line, computed in the background). The border and label keep the
  status color.

## Show Usages

- **Show Usages** in the Structure Diff toolbar opens a panel below the tree or treemap with what the selected member
  uses and what uses it: **Used by** and **Uses**, each split into internal (the member's own file) and external
  (other files, by module), with members nested under their class and test code marked.
- The changed members related to the selected one are highlighted in the tree and the treemap, so you see
  which parts of a change belong together: amber (`↑`) for members that use it, teal (`↓`) for members it uses,
  lavender (`↕`) for both. In the treemap the other members fade to half strength.
- Imports, generated sources and build, configuration and text files are left out. The usages are searched again
  after you edit the files. Large searches are capped (200 callers, 2000 references resolved), shown as "capped".
- Double-click or Enter in the panel jumps to the usage; click the header to jump to the member itself. Not available
  in Follow Git Log mode, as usages need the working-tree files.

## Member History

- **Show Member History** in the editor or tree context menu lists the commits that changed one class, method or
  field, newest first, following it across renames, signature changes and file renames, with a diff of the member in the
  selected commit. Double-click shows the commit in the Git log.

## Review Findings

- When a `reviews/*.sarif` (or `.sarif.json`) file appears in a repository root, or you pick one from anywhere with
  **Open Review › Open File...**, its findings are highlighted in the editor with a gutter
  icon; the tooltip shows the finding and suggested fix. A newer review replaces the previous one.
- The **Review** tool window lists them by file and severity; double-click or Enter jumps to the code.
  Open an older review, Reload, or Clear Findings (edit reasons stay).
- **Next / Previous Occurrence** (Ctrl+Alt+Down / Up, or the toolbar arrows) steps through findings and edit reasons,
  opening each one.
- Findings follow your edits; when the code is gone they are greyed as possibly fixed.
- **Dismiss**, **Restore** and **Copy** findings; dismissed ones stay dismissed in later reviews.
- The Structure Diff tree marks members and files that have findings (`⚠N`), also in Follow Git Log mode.

## Edit Reasons

- Shows **why** an agent made each change: the reasons it records in `reviews/edits/*.jsonl` (one file per session)
  from the last 7 days. Skills can write marks in the same
  format; those are purple everywhere.
- The Structure Diff tree and treemap mark members and files with reasons (`✎`, `✎N` for several); the tooltip lists
  them under **Why**, newest first, with their time: up to 5, each cut to 200 characters, the rest counted. A change
  whose reason the agent hasn't recorded yet shows "reason pending"; agents record reasons at the end of their turn.
- The editor shows a pencil in the gutter for each reason, at the first line of one of its changes; its tooltip names
  the reason, and so does hovering the thin bars it draws over its other changed ranges. The pencil's color tells the
  kind of change: green for added code, blue for modified and red for removed, like the tree and the treemap, grey when
  the kind isn't known. A click on the pencil, or on one of its bars, tints every changed range of the reason. It
  follows the code through later edits.
- Reasons are found again by their text when later edits moved their lines. They follow your edits while the IDE
  runs; ones recorded before it started are placed at their recorded lines and marked "(approximate)".
- The **Review** tool window lists them in an **Edit Reasons** section: one node per session or skill run
  with its time span, then each reason with its time and the places it covers (file and line), in the order the
  changes were made (a skill run in the order it wrote them). Only reasons on code the shown snapshot changes are
  listed, as in the gutter. Select one for its full text; double-click, Enter or Next / Previous Occurrence opens it
  with its range selected. A click on a pencil in the editor selects its place in the list. Grey places, like
  "(approximate)" in the gutter, may have drifted from their recorded lines.
- Rows show a session's title and each reason's first clause; the rest is in the details pane.
- Reloaded when an edits file changes, when the reviews directory changes in settings, and with the toolbar's
  **Refresh**, which also re-reads them from disk.

## Requirements

- IntelliJ-based IDE, build 242 (2024.2) or newer
- JDK 25 for building (Gradle toolchain; the plugin targets Java 21 bytecode)

## Build

```sh
./gradlew buildPlugin      # plugin zip in build/distributions/
./gradlew verifyPlugin     # IntelliJ Plugin Verifier
./gradlew runIde           # sandbox IDE with the plugin installed
```

By default Gradle downloads IntelliJ IDEA `platformVersion` from `gradle.properties`. To build against a local
installation instead, set `platformLocalPath` in `~/.gradle/gradle.properties` or pass it on the command line:

```sh
./gradlew buildPlugin -PplatformLocalPath="C:/Program Files/JetBrains/IntelliJ IDEA"
```

## Install

In the IDE: **Settings | Plugins | ⚙ | Install Plugin from Disk...** and pick
`build/distributions/StructureDiff-<version>.zip`. Then open the **Structure Diff** tool window.

## License

MIT, see [LICENSE](LICENSE). The plugin icon comes from the IntelliJ Platform (Apache 2.0).
