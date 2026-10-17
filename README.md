# Structure Diff

IntelliJ plugin for reviewing what AI coding agents changed — **Claude Code**, **OpenAI Codex**, **DeepSeek**, Cursor,
Copilot, Gemini CLI and others. It shows changes at member level (added, modified and removed classes, methods and
fields) in a tree like the Structure tool window, and shows AI code-review findings (SARIF) right in the editor.
Works for every language with a Structure view (Java, Kotlin, TypeScript, JavaScript, ...).

## Structure Diff tool window

- **Modes**: **Uncommitted** changes; **Branch vs main** — everything the branch changed since it forked from
  `main`/`master`, like a merge request (needs Git); **Follow Git Log** — the commits selected in the Git log.
- **Export Diff for Review** — saves the changes to `reviews/<timestamp>-changes.diff` and copies the
  review skill command for it to paste into your agent. A new `reviews/` gets a `.gitignore`. The directory, the
  command (default `/adversarial-review`) and whether to copy it are set in **Settings | Tools | Structure
  Diff**.
- Toggles: **Show Nested Changes** (inside functions and object literals), **Show Unversioned Files** (except files
  directly in the repository root),
  **Flash Changes** (briefly highlight what appeared, changed or disappeared).
- Per file and member: `+` added, `~` modified, `−` removed counts, changed lines, renames/moves, and old vs new
  signature in the tooltip. Multiple VCS roots are grouped.
- **Double-click**/**Enter** jumps to source, **middle-click** opens the diff at the member; context menu: Show Diff,
  Jump to Source, Copy Path.

## Review Findings

- When a `reviews/*.sarif` (or `.sarif.json`) file appears in a repository root, or you pick one from anywhere with
  **Open Review › Open File...**, its findings are highlighted in the editor with a gutter
  icon; the tooltip shows the finding and suggested fix. A newer review replaces the previous one.
- The **Review Findings** tool window lists them by file and severity; double-click or Enter jumps to the code.
  Open an older review, Reload, or Clear All.
- Findings follow your edits; when the code is gone they are greyed as possibly fixed.
- **Dismiss**, **Restore** and **Copy** findings; dismissed ones stay dismissed in later reviews.
- The Structure Diff tree marks members and files that have findings (`⚠N`), also in Follow Git Log mode.

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
