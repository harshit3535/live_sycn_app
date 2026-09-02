# GitSync

Two-way sync between a folder on your Android phone and a GitHub repo. Built to be compiled entirely through GitHub Actions — no Android Studio needed.

## Features

- Pick the folder to sync by browsing (in-app folder browser) or typing the path directly.
- Repo URL, GitHub token (encrypted), folder path, and all settings persist even after the app is closed or force-stopped.
- Configurable check interval: on-change (checks every 1 min), or a fixed 30s / 1 / 2 / 3 / 5 / 10 min.
- On every check: compares local files vs GitHub (via git blob SHA hashes) and only pushes/pulls/deletes what actually changed.
  - Remote-only file → pulled to phone.
  - Local-only file → pushed to GitHub.
  - Changed on both sides → local (phone) version wins (single-device use case, no conflict UI).
  - Deleted on one side → deleted on the other side too, but ONLY if that file was part of a previous successful sync (tracked in a small per-profile manifest). A file neither side has seen synced before is always treated as new (added), never as a deletion — this keeps the very first sync of a non-empty folder safe.
- Optional: pick specific apps — when any of them opens or closes, a sync is triggered immediately (via Accessibility Service).
- Start/Stop button controls a real foreground background service — keeps running even if you close the app; only Stop actually kills it.
- Live status: seconds since last sync, seconds to next check, push/pull progress percentage — shown in-app and in the notification.
- Activity log (push/pull/trigger/errors with timestamps), kept until you tap Clear.
- Dark UI (Jetpack Compose + Material 3).

## Required permissions (granted manually after install)

- **All files access** (Settings → Apps → GitSync → Permissions) — needed to read/write the folder you pick.
- **Accessibility Service** (Settings → Accessibility → GitSync) — only needed if you use the app-open/close trigger feature.
- **Notifications** — asked on first launch, needed to show the live sync/progress notification.

The app links directly to both permission screens from its UI when needed.

## GitHub token

Create a fine-grained personal access token scoped to the one repo, with **Contents: Read and write** permission. Paste it into the app — it's stored encrypted (Android Keystore) and never leaves the device except in API calls to `api.github.com`.

## Building the APK

Push to `main` (or run the workflow manually) — GitHub Actions builds `app-debug.apk` and uploads it as a workflow artifact. No local Android Studio/SDK setup required.

## Notes / current limitations

- Single-device use only — no multi-device conflict resolution.
- Deletions ARE synced both ways, but only for files the app has previously seen synced (see above) — be deliberate when deleting.
- Binary and text files are both supported (synced as raw bytes).
