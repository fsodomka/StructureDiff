# Structure Diff

IntelliJ plugin that shows uncommitted changes at member level: added, modified and removed classes, methods and fields
of each changed file, in a tree like the Structure tool window. It works for every language with a Structure view
(Java, TypeScript, JavaScript, Kotlin, ...) and refreshes automatically when the VCS change list updates.

## Features

### Toolbar

- **Refresh**: recompute the tree now.
- **Show Nested Changes**: show changes inside functions, components and object literals, not only inside classes.
- **Show Unversioned Files**: include new files not yet added to VCS.
- **Flash Changes**: briefly highlight members and files that appeared, changed status or disappeared. Removed nodes
  stay visible, struck out, until the highlight fades.

### Tree

- The summary line below the toolbar shows the number of changed files, added (`+`), modified (`~`) and removed (`−`)
  members, and the total number of changed lines.
- **File nodes** show the file name in its VCS status color, the same member counts and changed line count, and the
  directory. A renamed or moved file shows its old name or path after `←`. The tooltip lists lines added and removed
  and where the file was moved or renamed from.
- **Member nodes** show the member in its status color; removed members are struck out. The tooltip shows the old and
  new signature when it changed, and the lines added and removed.
- An added or removed member with children counts once; a modified container counts only through its changed children.
- When changes span several VCS roots, files are grouped by root.

### Navigation

- **Double-click** or **Enter**: jump to the member in the source.
- **Middle-click**: open the diff scrolled to that member.
- Context menu: **Show Diff**, **Jump to Source** and **Copy Path**, with the IDE's shortcuts for Show Diff and Jump to
  Source.

## Requirements

- IntelliJ-based IDE, build 262 (2026.2) or newer
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
