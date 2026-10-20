# Pigeon Notes

The owner's personal Android app (used on a Pixel 8) that replaces the Nextcloud Notes app.
[README.md](README.md) describes the features, the notes folder layout, how sync works and the code
layout. Read it first.

## Decisions already made (don't revisit without asking)

- Sync uses **WebDAV** (not the Nextcloud Notes REST API), and sign-in uses **Login Flow v2**.
- The editor is a **native Compose block-hybrid editor**: rendered blocks, one block edited as raw
  Markdown. No WebView.
- There's **no Android Studio**. Everything is built from the CLI with the Gradle wrapper.
- **Folder layout** on the server under `/Notes`: quick notes directly in the root (generated
  `note-yyMMdd-HHmm.md` names, titled by their first line), long-term pages in `Pages/` (a single
  file or a folder of notes), tagged notes in `Tags/` (directly there = untagged, `Tags/<Tag>/` =
  tagged, one tag per note), `_archive/` flat. Per-note assets live in `Name.assets/` next to
  `Name.md`. An assets folder is only moved or deleted when no sibling note links into it.
- Dot-files and dot-folders (`.obsidian/`, `.ztr-directory`) are skipped by sync: the same folder
  is used with Obsidian and Zettlr on the desktop.
- The owner's notes are real data. Sync changes must never lose content: on doubt, keep both
  versions (conflict copy).

## Build and test

```bash
./gradlew testDebugUnitTest    # JVM unit tests: run before every commit
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK (signed with the debug key, see below)
```

- JDK 21 and Android SDK `platforms;android-37.2` + `build-tools;37.0.0` (compileSdk 37.2,
  minSdk 34, AGP 9.4, Gradle 9.7). The SDK is found through `ANDROID_HOME` or `local.properties`.
- `versionCode` is the git commit count (`git rev-list --count HEAD`). A shallow clone gives a
  wrong number, which matters only for installing, not for compiling or testing.
- Pure logic is kept free of Android so it can be unit-tested: `SyncDecision`, `DocumentModel`, the
  Markdown parser. `SyncEngineTest` runs the engine against `FakeDav` (in-memory WebDAV server).
  Add tests there when changing sync or editor behaviour.

## In a Claude Code cloud session

- The environment installs the SDK with [scripts/cloud-setup.sh](scripts/cloud-setup.sh). If
  `ANDROID_HOME` is unset or the SDK is missing, run that script.
- There's no emulator, no phone and no access to the Nextcloud server (it's only reachable over
  Tailscale). Verification is limited to compiling and unit tests. Say so in the summary, and list
  what the owner should try on the phone.
- Work on a branch and open a PR. Every push runs the unit tests in GitHub Actions
  ([.github/workflows/build.yml](.github/workflows/build.yml)). Merging into `main` builds a
  release APK and publishes it as a GitHub Release (`build-<n>`), which the owner installs on the
  phone. Every push to another branch publishes a test build as a pre-release (`test-<branch>`)
  with the version of the `main` commit the branch is based on, so the owner can try a change
  before merging. Merge `main` into the branch when it has moved on, or the test build has a lower
  version than the installed release and won't install.

## Signing

Every APK (local debug, CI release) is signed with the same debug keystore, so they install over
each other without losing app data. CI restores it from the `DEBUG_KEYSTORE_BASE64` secret. Never
commit a keystore and never change the signing setup: an APK signed with another key can only be
installed after uninstalling the app, which deletes its local data.

- The key in the secret is the reference: the app on the phone was reinstalled from a CI release
  (`build-18`), so it is signed with that key. A local build only installs over it if the local
  `~/.android/debug.keystore` is the same file (decode the secret into it to get there).
- A key mismatch shows on the phone as Play Protect's "hasn't seen an app from this developer"
  followed by "App not installed" (`INSTALL_FAILED_UPDATE_INCOMPATIBLE` through adb). A versionCode
  lower than the installed one fails the same way (`INSTALL_FAILED_VERSION_DOWNGRADE`); the
  installed version is shown at the bottom of the app's page in the system settings.
- Uninstalling also deletes settings kept only on the phone: the Nextcloud login, page order, page
  colours, page rows and the app settings. Notes come back from the server, but only after a sync
  with nothing pending before the uninstall.

## Conventions

- Commit messages: a short subject in the imperative or "Area: change" form, then a body in prose
  explaining what changed and why.
- Match the surrounding code: KDoc on classes and non-obvious functions, comments explain *why*.
