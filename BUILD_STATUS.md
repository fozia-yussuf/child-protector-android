# Build and test status

## Build

The Android debug APK is produced with:

```powershell
.\gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Android verification

- `.\gradlew.bat test` runs JVM unit tests.
- `.\gradlew.bat connectedDebugAndroidTest` runs Compose UI tests on an attached emulator/device.
- `.github/workflows/build-apk.yml` runs unit and Compose UI tests on an Android 35 emulator, assembles the debug APK, and uploads it as the `child-protector-debug-apk` workflow artifact.

Unit coverage includes API error/auth behavior, session persistence and migration (production uses encrypted preferences), face-match window behavior, image-quality checks, and ViewModel state. Compose UI coverage includes role navigation, Admin signup visibility, and boarding confirmation enablement.

Latest local verification:

- Passed `.\gradlew.bat test` (17 JVM tests for each of Debug and Release).
- Passed `.\gradlew.bat :app:compileDebugAndroidTestKotlin assembleDebug`.
- Compose UI tests were compiled but not run locally; `adb devices` reported no attached emulator or device.

## Backend verification

From `backend/`:

```powershell
npm ci
npm test
npm run build
```

The backend tests use `pg-mem` with Supertest and do not require a local PostgreSQL server. The Render deployment provisions PostgreSQL using `render.yaml`.

## Configuration required before deployment

- Set root `API_BASE_URL` in `gradle.properties` to the deployed HTTPS backend URL.
- Configure `DATABASE_URL`, `ADMIN_PASSWORD`, and `SESSION_TTL_HOURS` in the backend environment. See `backend/.env.example` for local development and the README for Render deployment steps.
- Set a restrictive `CORS_ORIGIN` when browser clients need access.

No live Render deployment or physical-device validation is implied by a successful local build or automated test run.
