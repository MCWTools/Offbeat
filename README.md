# Offbeat

Offline music player for Android (Kotlin, Jetpack Compose, Media3).

## Build / release
- Push to GitHub -> Actions builds the APK (Actions tab -> "Build APK" -> Artifacts).
- Make a release: `git tag v1.0.0 && git push origin v1.0.0` -> a GitHub Release with `Offbeat-v1.0.0.apk` is created.
- Locally: `./gradlew assembleRelease` -> `app/build/outputs/apk/release/app-release.apk`.

The APK is signed with `release.keystore` (committed on purpose, personal app) so every build can update the previous one.
