# GitHubK Studio 5.0.0 — Full Terminal + On-device IDE

This source is based on the supplied 4.8.1 project. It does **not** embed the entire Termux/ZeroTermux application; instead it follows their architecture: a private POSIX prefix, package-manager based native tools, PTY terminal, and a separate IDE/toolchain layer.

## Implemented

- Native Linux PTY terminal with ANSI/VT rendering, 256 colors, TrueColor, alternate screen, Ctrl/Alt/function/navigation keys, IME input, copy/paste, scrollback, swipe scrolling and pinch-to-zoom.
- Termux-compatible private runtime and package installation.
- ZeroTermux-style environment center: package sources, proot/X11 helpers, backup/restore and diagnostics remain available.
- One-click full IDE environment:
  - OpenJDK 17
  - Gradle 9.3.1-compatible environment
  - Git
  - Python / Node.js
  - Clang / CMake / Ninja / Make
  - Android Command-line Tools
  - Android API 34/35/36
  - Android Build Tools 34.0.0 / 35.0.0 / 36.0.0
  - ARM64 Termux android-tools (adb/fastboot)
  - ARM64 Termux aapt2 override for Gradle/AGP
  - Flutter stable, architecture detected at runtime
  - Flutter bundled Dart SDK, plus standalone Dart fallback
- Resumable HTTP downloads with SHA-256 verification for the pinned Android CLI archive.
- Flutter release discovery from the official Linux release manifest.
- Dart stable release discovery from the official Dart archive endpoint.
- Dedicated **IDE Environment** page with progress log and Toolchain Doctor.
- IDE top toolbar redesigned to the supplied Android Code Studio reference: hamburger, Run, Sync, Save, overflow.
- File tabs, project drawer, line numbers, syntax highlighting, completion and build/problem bottom sheet.
- **Live Preview** bar with theme/device/problems/refresh controls and HTML/Markdown/XML preview.
- Self-bootstrapping `gradlew` that works with system Gradle or downloads Gradle when `gradle-wrapper.jar` is unavailable.

## ARM64 note

Google's Linux Android SDK native tools are primarily distributed for x86_64. The installer therefore uses Termux's native ARM64 `aapt2` and `android-tools` where appropriate and writes `android.aapt2FromMavenOverride` into the app's Gradle user home. This is the same class of workaround used by current on-device Termux Android build environments.

Projects that require native `aidl`, `zipalign` or other x86_64-only SDK executables may still need an ARM64 repack of those individual binaries or a compatible build environment. The IDE reports the environment state instead of claiming that every SDK binary is ARM64-native.

## First run

1. Open **设置 → 完整 IDE 环境中心**.
2. Tap **一键安装完整环境**.
3. Wait for the streamed installation log to finish.
4. Open the terminal and run `java -version`, `gradle --version`, `adb version`, `flutter --version`, and `dart --version`.
5. Open a project and use Run/Build from the IDE toolbar.

## Sources used for architecture/reference

- Termux app: https://github.com/termux/termux-app
- Termux packages: https://github.com/termux/termux-packages
- ZeroTermux: https://github.com/hanxinhao000/zerotermux

The implementation is an independent integration and does not copy the full Termux/ZeroTermux application into GitHubK Studio.
