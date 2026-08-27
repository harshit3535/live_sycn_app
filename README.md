# Android-GitSync

A small Android app that clones a GitHub repository to a phone folder, watches file changes, pushes changes, and periodically pulls from GitHub.

## GitHub upload
Upload the contents of this folder to a GitHub repository. Keep the repository private, especially if you plan to use personal access tokens.

## GitHub Actions
The workflow at `.github/workflows/build-apk.yml` builds a debug APK and stores it as the `GitSync-APK` artifact.

## Important
Do not commit a GitHub token into the repository. This project currently accepts the token through the app UI; use a least-privileged fine-grained token and avoid sharing APK screenshots/logs that expose it.
