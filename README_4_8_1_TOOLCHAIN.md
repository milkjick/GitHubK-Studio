# GitHubK Studio 4.8.1 — Toolchain / Terminal / Shizuku update

This source is based on `GitHubK_Studio_4_8_0_ZeroTermux_PTY_Fixed_Source_FIXED`.

## Changes

- Native terminal supports pinch-to-zoom. Font size is clamped to 8–28 px and the PTY window is resized after zooming.
- Shizuku provider declaration now matches the official V3 provider requirements: exported, `INTERACT_ACROSS_USERS_FULL`, API permission and V3 metadata.
- Shizuku permission requests are fully asynchronous; permission refresh/final checks are moved off the main thread to avoid the long authorization/ANR-like state.
- Dhizuku is no longer incorrectly treated as an `IShizukuService` binder. It is integrated through the official Dhizuku API and has a separate authorization center in Settings.
- Toolchain Center now exposes JDK, Gradle, Git, Android SDK, ADB, Dart SDK and Flutter SDK status.
- Dart/Flutter paths are part of the common terminal/build environment (`DART_HOME`, `DART_SDK`, `FLUTTER_HOME`, PATH).
- Offline toolchain bundles may contain `dart-sdk/` and `flutter-sdk/` (or `flutter/`). Flutter's bundled Dart SDK is preferred when available.
- BuildEngine now consumes the same ToolchainManager environment as the terminal, so Flutter/Gradle builds no longer depend on a separately configured shell PATH.
- Added a GitHubK Studio launcher icon based on a terminal prompt + code bracket + K monogram.
- Version bumped to `4.8.1-toolchain-shizuku`.

## Offline toolchain layout

Place this folder at `/sdcard/Download/githubk-offline-toolchain/`:

```text
githubk-offline-toolchain/
├── jdk/
├── gradle/
├── android-sdk/
├── git/
├── dart-sdk/
└── flutter-sdk/
```

The existing JDK/Gradle/SDK/Git installer remains compatible with its previous layout.

## Important privilege model

Shizuku and Dhizuku are deliberately separate:

- **Shizuku**: shell/root-backed Binder and UserService; used for the privileged terminal/toolchain worker.
- **Dhizuku**: DeviceOwner/ProfileOwner API; used for DevicePolicy operations and authorization, not as a generic shell.

Dhizuku API dependency:

`io.github.iamr0s:Dhizuku-API:2.5.4`

Dhizuku must already be activated on the device. The app cannot create DeviceOwner permission by itself.
