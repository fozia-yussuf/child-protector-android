# Build status

The native Android source project has been created.

The current ChatGPT execution environment does not contain the Android SDK or Gradle executable, and external package downloads are unavailable, so this environment cannot produce a compiled `.apk` binary.

The project includes `.github/workflows/build-apk.yml` so a GitHub Actions runner can compile `app-debug.apk` with Gradle 8.10.2 and the Android SDK available on the runner.
