# GitHubK Studio 4.8.1 — Native Termux PTY + ZeroTermux extensions

This source is a repaired successor to the 4.6 NativeTermux archive.

## The build failure fixed here

The previous archive did not contain:

`app/src/main/AndroidManifest.xml`

so Gradle failed at `:app:processDebugMainManifest` before Kotlin/NDK compilation. This source includes the manifest and the complete Android app source tree.

The previous 4.6 PTY JNI naming mismatch is also removed. The Kotlin bridge is:

`com.example.myempty.githubk.terminal.pty.NativePty`

and the JNI symbols use the exact same package/class name.

## Terminal architecture

`Android View -> VT/ANSI emulator -> NativePty Kotlin -> JNI -> /dev/ptmx -> fork/setsid/TIOCSCTTY -> bash`

Interactive terminal I/O never uses `ProcessBuilder` pipes.

Supported terminal behavior includes:

- real PTY
- controlling TTY
- window-size (`TIOCSWINSZ`) updates
- Ctrl-C / Ctrl-Z / Ctrl-D
- foreground job-control signals
- ANSI/VT screen updates
- alternate screen for vim/top/tmux
- 16/256/true-color SGR rendering
- terminal extra keys

## ZeroTermux feature center

- APT mirror switching: official, TUNA, USTC, BFSU
- pkg update/upgrade and developer tool bundle
- PRoot-Distro helper
- runtime backup/restore using tar.gz + Android SAF
- Termux storage setup helper
- Termux:X11 environment helper
- QEMU user-mode helper/diagnostics
- external plugin detection: API, Styling, Tasker, Widget, Boot, X11
- diagnostics for PREFIX/HOME/bash/pkg/apt/proot/qemu

The APK does not silently bundle arbitrary Linux distribution rootfs or QEMU binaries. The feature center manages packages in the private Termux-compatible PREFIX.

## Build

Use an Android build environment with NDK installed because the native PTY is built from `app/src/main/cpp/termux_pty.c`.

The project targets Android 34 and Java/Kotlin 17, matching the previous project configuration.

## 4.8.1 Toolchain / Shizuku update

- Native terminal pinch-to-zoom (8–28 px) with PTY resize.
- Shizuku V3 provider/permission lifecycle fixes and non-blocking permission refresh.
- Real Dhizuku API integration as a separate DeviceOwner/ProfileOwner authorization channel.
- Unified JDK / Gradle / Git / Android SDK / ADB / Dart / Flutter environment.
- BuildEngine and terminal now share ToolchainManager PATH and SDK variables.
- Offline toolchain bundle supports `dart-sdk/` and `flutter-sdk/`.
- New GitHubK Studio terminal/code launcher icon.

See `README_4_8_1_TOOLCHAIN.md` for the complete toolchain layout and privilege model.
