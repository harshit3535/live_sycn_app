# GitSync

GitHub repository ↔ one Android phone folder two-way synchronization.

- Saves repository URL and folder path between app launches.
- Encrypts the saved GitHub token with Android Keystore.
- Performs an immediate sync when Start Sync is pressed.
- Checks again every 2 minutes while the foreground sync service is running.
- Uses the GitHub Git database REST API instead of an embedded Git command line.
- Local changes are pushed to the repository; remote-only changes are pulled to the phone.
- When both sides changed the same path, the local phone version wins, matching the one-device use case.

## First run

Use an empty or disposable test folder for the first sync. The repository should already contain the files you want on the phone. The app uses the repository's default branch.

The token must have Contents read/write permission for the selected repository.
