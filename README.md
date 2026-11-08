# Pigeon Notes

A personal Android note-taking app for Markdown notes stored in a Nextcloud folder. It replaces the
official Nextcloud Notes app with an offline-first editor and a home screen organised around how
long a note is meant to live.

- **Offline-first sync over WebDAV.** Every note is a plain `.md` file, mirrored to app-private
  storage and synced in both directions with the server. Edits land on disk immediately and are
  pushed whenever the server is reachable; WorkManager runs a background sync every 15 minutes.
- **Block-hybrid Markdown editor.** Native Jetpack Compose, no WebView: the note is shown
  rendered, and only the block you tap turns into raw Markdown for editing. Supports headings,
  lists, checkboxes, code, links, images, strikethrough and LaTeX (`$…$`, `$$…$$`), plus
  undo/redo (in the formatting bar), a table of contents that jumps to a heading, and a source
  mode. Tap the title at the top to rename the note's file. The Markdown is kept verbatim: only the text you edit changes, so
  notes stay as written for Obsidian, Typora and Nextcloud Notes on the desktop.
- **Three tiers of notes** on one home screen: quick notes, pages and tagged notes (see below).
- **Share target.** Text shared from other apps becomes a new quick note.
- **Login Flow v2.** Sign-in happens in the browser and the app only ever receives an app password.

Requires Android 14 (API 34) or newer and a Nextcloud server.

## Notes folder layout

The app works on one folder on the server (default `/Notes`):

| Location | Tier | Shown as |
| --- | --- | --- |
| `/Notes/*.md` | Quick notes (short-term) | File names are generated: `note-yyMMdd-HHmm_Title.md` |
| `/Notes/Pages/` | Pages (long-term) | A single `.md` file, or a folder of notes (`Pages/Recipes/…`), which can hold folders of its own (`Pages/Recipes/Asian/…`) |
| `/Notes/Tags/` | Tagged notes (medium-term) | Directly in `Tags/` = untagged, `Tags/<Tag>/` = tagged with `<Tag>` (one tag per note) |

Every note is titled by its first `# Heading`. Only a note without one falls back to its file name: the
title part of a `note-…_Title` name, the first line of an older `note-yyMMdd-HHmm.md`, or (when it is
empty) the moment it was made. A new note starts as `# New Note` with the title selected, so typing
names it. When an edited note is closed, its file name follows the heading: `note-yyMMdd-HHmm_Title.md`
for generated names, just `Title.md` for a standalone page directly in `Pages/` (spaces become dashes).
Notes inside a page folder (at any depth) always keep their file name; only a note just created there
gets one from its heading, once, when it is first closed. Other names are left alone, and a new note
closed without any change is dropped again.

A page folder opens as a tree, with a breadcrumb of where it is at the top: its folders first (the
round button on the right opens one in place, tapping it opens it on its own screen, a long-press
renames or deletes it), then its notes, each with its title, file name and first lines.

Images and other attachments of a note live in `Name.assets/` next to `Name.md`. Other files and
folders (such as `_archive/`) are synced but not shown, and dot-files and dot-folders (such as
`.obsidian/`) are skipped entirely. This keeps the folder usable with Obsidian, Zettlr or any other
Markdown editor on the desktop.

## How sync works

The engine walks the tree with `PROPFIND` (depth 1) and only descends into directories whose ETag
changed, since Nextcloud propagates ETag changes upward. Each path then gets a three-way decision
(local state, last agreed ETag, current remote ETag) that results in a download, upload, move,
delete or conflict. Uploads are guarded with `If-Match` / `If-None-Match`, so a remote change is
never silently overwritten. On a real conflict both versions are kept: the server's copy keeps the
name and the local one is saved as a `(conflict …)` sibling. Syncing pauses while a note is open in
the editor.

## Building

Toolchain: JDK 21, Android SDK with `platforms;android-37.2` and `build-tools;37.0.0`, and the
included Gradle wrapper. Point `local.properties` (`sdk.dir=…`) or `ANDROID_HOME` at the SDK. If
the default JDK isn't 21, set `org.gradle.java.home` in your user-level `~/.gradle/gradle.properties`.

```bash
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`versionCode` is the number of commits on `HEAD`, so a newer commit always installs as an update.

## Releases

GitHub Actions ([build.yml](.github/workflows/build.yml)) runs the unit tests on every push. Every
push to `main` also builds a release APK and publishes it on the repo's Releases page as
`pigeon-notes-0.1.<n>.apk`. Open that page on the phone to download and install it.

Every push to any other branch publishes a **test build** of it as a pre-release ("Test: <branch>"),
so a change can be tried on the phone before its pull request is merged. Each push replaces the
branch's test build, and deleting the branch removes it. A test build carries the version of the
`main` commit the branch is based on: it installs over that release, and the next release installs
over it. If `main` has moved on since, merge `main` into the branch first, otherwise the phone
refuses the test build as a downgrade.

Local and CI builds are all signed with the same key (the debug keystore, which CI restores from the
`DEBUG_KEYSTORE_BASE64` secret and checks every APK against), so they install over each other and
keep the app's data. Releases up to `build-20` were signed with a different throwaway key each; an
app installed from one of them has to be uninstalled once before a newer build installs.

## Project structure

```
app/src/main/java/ch/antoinette/pigeonnotes/
├── data/
│   ├── local/    FileStore (on-disk mirror), Room database (sync bookkeeping, list index), settings
│   ├── remote/   WebDAV client, PROPFIND XML parsing, Nextcloud Login Flow v2
│   └── sync/     SyncEngine, SyncDecision (pure decision table), SyncWorker, connectivity
├── domain/       NotesRepository (all note operations), Markdown block parser and inline styler
└── ui/           Compose screens: home, note lists, editor, settings
```

The unit tests in `app/src/test/` cover Markdown parsing, the editor's document model (plus random
editing sessions in `EditorFuzzTest` that check it stays consistent and never loses text), the sync
decision table and the sync engine against an in-memory fake WebDAV server (`FakeDav`) and
OkHttp's MockWebServer.

## Tech stack

Kotlin, Jetpack Compose (Material 3), Room, DataStore, WorkManager, OkHttp, Coil,
JetBrains Markdown, JLaTeXMath.
