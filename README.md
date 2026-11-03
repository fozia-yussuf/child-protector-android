# Child Protector

Child Protector is a native Android application and Node.js API for school transport safeguarding. One Android app serves **PARENT**, **DRIVER**, and **ADMIN** accounts, with role-specific navigation after sign-in.

## Architecture

## Application Screenshots

The screenshots below show the Child Protector Android application. They are loaded from image files already stored in this repository.

<table>
  <tr>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.10.jpeg" alt="Child Protector screenshot 1" width="280"><br><sub>Screenshot 1</sub></td>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.11%20(1).jpeg" alt="Child Protector screenshot 2" width="280"><br><sub>Screenshot 2</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.11%20(2).jpeg" alt="Child Protector screenshot 3" width="280"><br><sub>Screenshot 3</sub></td>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.11.jpeg" alt="Child Protector screenshot 4" width="280"><br><sub>Screenshot 4</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.12%20(1).jpeg" alt="Child Protector screenshot 5" width="280"><br><sub>Screenshot 5</sub></td>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.12%20(2).jpeg" alt="Child Protector screenshot 6" width="280"><br><sub>Screenshot 6</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.12%20(3).jpeg" alt="Child Protector screenshot 7" width="280"><br><sub>Screenshot 7</sub></td>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.12.jpeg" alt="Child Protector screenshot 8" width="280"><br><sub>Screenshot 8</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.13%20(1).jpeg" alt="Child Protector screenshot 9" width="280"><br><sub>Screenshot 9</sub></td>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.13%20(2).jpeg" alt="Child Protector screenshot 10" width="280"><br><sub>Screenshot 10</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.13.jpeg" alt="Child Protector screenshot 11" width="280"><br><sub>Screenshot 11</sub></td>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.14%20(1).jpeg" alt="Child Protector screenshot 12" width="280"><br><sub>Screenshot 12</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="./WhatsApp%20Image%202026-10-22%20at%2022.25.14.jpeg" alt="Child Protector screenshot 13" width="280"><br><sub>Screenshot 13</sub></td>
    <td></td>
  </tr>
</table>


```text
app/                         Android application (Kotlin, Jetpack Compose, Material 3)
  src/main/java/.../Api.kt   HTTPS API client, bearer authorization, encrypted sessions
  src/main/java/.../ui/      Auth, admin, driver, parent, and common Compose screens
  src/main/java/.../location/ Driver GPS foreground service
  src/main/java/.../realtime/ Authenticated WebSocket client
  src/test/                  JVM unit tests
  src/androidTest/           Compose UI tests
backend/                     Node.js 20, TypeScript, Express, PostgreSQL service
  migrations/                SQL schema migrations
  src/                       Routes, auth, database, WebSocket, and tests
.github/workflows/           Android CI and debug APK artifact
render.yaml                  Render web service and PostgreSQL Blueprint
```

The Android app uses CameraX, OpenCV YuNet/SFace for experimental face matching, Fused Location, and a JavaScript-enabled WebView restricted to embedded Google Maps. Driver GPS runs in a notification-backed foreground service. Realtime events use a bearer-authenticated WebSocket with polling fallback.

Face matching has no liveness detection and accuracy can vary. Drivers must confirm boarding, manual overrides are recorded as `MANUAL`, and a match must not be the only safety control.

## Requirements

- Android Studio with Android SDK 35; app minimum SDK is 26.
- JDK 17 for Gradle.
- Node.js 20.x and npm for the backend.
- PostgreSQL for a local backend. Backend automated tests use `pg-mem` and do not require a running database or Docker.

## Android setup

Set `API_BASE_URL` in the root `gradle.properties` to the HTTPS URL of the backend, for example:

```properties
API_BASE_URL=https://your-service-name.onrender.com
```

The committed service URL is a placeholder until deployment. The app derives its secure socket endpoint as `wss://<API host>/ws`. Do not put credentials in the URL or commit secrets.

Open the repository in Android Studio and run the `app` configuration, or from the repository root run:

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Android tests

JVM tests cover API errors and authorization, session persistence and migration (production uses encrypted preferences), the 3-of-5 match window, image-quality rules, and `AppVM` state. Compose UI tests cover role tabs, Admin signup visibility, and boarding confirmation gating.

Run JVM tests with `.\gradlew.bat test`. Compose UI tests run on an emulator/device:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

The GitHub Actions workflow runs both suites on an Android emulator and uploads the debug APK.

## Backend setup

Copy the example environment file and set values for a local PostgreSQL instance:

```powershell
Copy-Item backend\.env.example backend\.env
Set-Location backend
npm ci
npm run migrate
npm run dev
```

The API listens on port `PORT` (default `3000`); `GET /health` checks database connectivity.

### Backend environment variables

| Variable | Required | Default | Description |
|---|---:|---|---|
| `DATABASE_URL` | Yes | — | PostgreSQL connection string |
| `ADMIN_PASSWORD` | Yes | — | Password provisioned for fixed username `admin` |
| `SESSION_TTL_HOURS` | No | `24` | Session lifetime |
| `PORT` | No | `3000` | HTTP port |
| `NODE_ENV` | No | `development` | Set to `production` on Render |
| `MIN_FACE_MATCH_SCORE` | No | `0.363` | Minimum SFace cosine score for automatic scans |
| `CORS_ORIGIN` | No | `*` | Comma-separated allowed browser origins |

Passwords are stored as salted scrypt hashes. Session tokens are random 32-byte values; only their SHA-256 hashes and expiration times are stored. Send tokens in `Authorization: Bearer <token>`. Signup is limited to Parent and Driver roles; Admin is provisioned from `ADMIN_PASSWORD`.

### Backend tests and build

```powershell
Set-Location backend
npm ci
npm test
npm run build
```

Supertest tests exercise the HTTP API, role isolation, scan-rule ordering, GPS updates, emergency and parent-link CRUD, and WebSocket authentication/filtering.

## Deploy to Render

1. Push the repository, including `render.yaml` and `backend/`, to GitHub.
2. In Render, choose **New +** → **Blueprint** and connect the repository.
3. Review the Blueprint resources: the `child-protector-api` Node web service and `child-protector-db` PostgreSQL database.
4. Before the service is publicly used, set `ADMIN_PASSWORD` in the Render service environment to a strong, unique value and retain it securely. Render generates an initial value by default.
5. Apply the Blueprint. Render runs `npm ci && npm run build`; the service applies SQL migrations on startup.
6. Wait for the service health check at `/health` to pass.
7. Copy the service HTTPS URL into root `gradle.properties` as `API_BASE_URL`, then rebuild and install the Android app.
8. The app connects to `wss://<API host>/ws`; no separate WebSocket secret is configured.

## Security and privacy

- Helmet security headers, CORS configuration, JSON validation, and rate-limited login/signup.
- Every protected endpoint checks session and role. Parent endpoints and parent WebSocket events are restricted to linked children and their routes.
- Raw passwords are never logged. Scan image payloads and session tokens are excluded from application logs and WebSocket scan events.
- Configure strong production secrets, HTTPS, PostgreSQL backups, and a restrictive `CORS_ORIGIN` appropriate to any browser clients.
