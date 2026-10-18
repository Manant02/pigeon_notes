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
  undo/redo and a source mode.
- **Three tiers of notes** on one home screen: quick notes, pages and tagged notes (see below).
- **Share target.** Text shared from other apps becomes a new quick note.
- **Login Flow v2.** Sign-in happens in the browser and the app only ever receives an app password.

Requires Android 14 (API 34) or newer and a Nextcloud server.

## Notes folder layout

The app works on one folder on the server (default `/Notes`):

| Location | Tier | Shown as |
| --- | --- | --- |
| `/Notes/*.md` | Quick notes (short-term) | Titled by their first line; file names are generated (`note-yyMMdd-HHmm.md`) |
| `/Notes/Pages/` | Pages (long-term) | A single `.md` file, or a folder of notes (`Pages/Recipes/…`) |
| `/Notes/Tags/` | Tagged notes (medium-term) | Directly in `Tags/` = untagged, `Tags/<Tag>/` = tagged with `<Tag>` (one tag per note) |

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
included Gradle wrapper. Point `local.properties` (`sdk.dir=…`) or `ANDROID_HOME` at the SDK.

> `gradle.properties` currently sets `org.gradle.java.home` to a JDK path on the original
> development machine. Adjust or remove that line when building elsewhere.

```bash
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM unit tests
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

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

The unit tests in `app/src/test/` cover Markdown parsing, the editor's document model, the sync
decision table and the sync engine against an in-memory fake WebDAV server (`FakeDav`) and
OkHttp's MockWebServer.

## Tech stack

Kotlin, Jetpack Compose (Material 3), Room, DataStore, WorkManager, OkHttp, Coil,
JetBrains Markdown, JLaTeXMath.
