# Child Protector — Android Driver + Parent App

This project is the native Android client for the existing Child Protector backend.

## What is included
- Native Android/Kotlin application.
- Parent and Driver roles in one Android app.
- Username/password signup and login.
- Parent accounts only receive children linked by the school administrator.
- Driver student roster and active trip management.
- Camera-assisted boarding check using a live camera preview. It does **not** perform automated biometric child identification.
- Boarding attendance through the existing `/api/scan` endpoint.
- Live GPS updates through the existing `/api/trips/:id/location` endpoint while the GPS screen is active.
- Parent child status, attendance and active-route tracking.
- Transport SOS/emergency reporting.
- Same AppDeploy backend and database used by the Admin dashboard.

## Backend
API base URL is configured in `app/build.gradle.kts`:
`https://child-protector-6dys9t.v2.appdeploy.ai`

The existing Admin web dashboard remains separate and is not replaced by this project.

## Android Studio
Open this folder in Android Studio. Let Gradle sync, then run the `app` configuration on an Android 8.0+ device/emulator.

The current ChatGPT build environment does not contain the Android SDK/Gradle toolchain, so an APK could not be compiled in this environment. The complete native Android project source is provided for Android Studio/CI compilation.

## Important implementation boundary
The camera feature is camera-assisted verification only. The app deliberately does not implement automated facial recognition or biometric identification of children.
