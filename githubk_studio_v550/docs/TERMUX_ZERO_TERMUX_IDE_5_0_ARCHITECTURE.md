# GitHubK Studio 5.0 — Termux / ZeroTermux integration architecture

## Terminal stack

```text
Android UI
  └─ NativeTerminalView
      ├─ Android IME / hardware keys
      ├─ pinch zoom
      ├─ swipe scrollback
      ├─ copy / paste
      └─ VT renderer (AnsiTerminal)
          └─ native PTY (termux_pty.c)
              └─ built-in Termux-compatible runtime
                  └─ bash / sh / pkg / apt / git / ssh / vim / tmux / etc.
```

This follows the separation used by Termux: the application owns terminal UI/emulation, while the package system supplies the Linux environment and tools. The official Termux project describes itself as an Android terminal emulator and Linux environment; its package system is maintained separately. See `README_5_0_0_FULL_TERMUX_IDE.md` for source references.

## Toolchain stack

```text
IDE Environment Center
   ├─ Termux native packages
   │   ├─ OpenJDK 17
   │   ├─ Gradle
   │   ├─ Git / Python / Node
   │   ├─ Clang / CMake / Ninja / Make
   │   ├─ android-tools (ARM64 adb/fastboot)
   │   └─ aapt2 (ARM64)
   ├─ Android SDK command-line tools
   │   ├─ SDK platforms 34/35/36
   │   └─ Build Tools 34/35/36
   ├─ Flutter stable discovery
   │   └─ bundled Dart SDK
   └─ standalone Dart stable fallback
```

The installer uses Android's `sdkmanager` layout and a pinned official Command-line Tools archive. The SDK Manager is the official command-line mechanism for installing/updating SDK packages.

## ARM64 build handling

Linux Android SDK native binaries are not all ARM64. The installer therefore:

1. installs native ARM64 `adb`/`fastboot` through the Termux package environment;
2. installs native ARM64 `aapt2` through Termux;
3. places `android.aapt2FromMavenOverride` in the app-owned Gradle user home;
4. refuses to install an official x86_64 Flutter host archive on ARM64;
5. optionally discovers an experimental ARM64 Flutter archive from the configured third-party compatibility release source;
6. still installs standalone Dart ARM64 from the official Dart archive when Flutter host binaries are unavailable.

This prevents the common Android-on-ARM failure mode where an x86_64 `aapt2` is downloaded and later fails with `exec format error`.

## IDE reference UI

The editor is intentionally organized like the supplied Android Code Studio reference:

- top command row: hamburger → Run → Sync → Save → overflow;
- horizontal file tabs;
- project tree drawer;
- line-numbered code editor;
- completion row;
- Live Preview bar;
- build/problems bottom sheet;
- Problems Report area.

## Wrapper behavior

The repository now contains a self-bootstrapping `gradlew` script. It first uses an installed matching Gradle, then `GRADLE_HOME`, then downloads Gradle 9.3.1 into the user-owned Gradle cache. This avoids depending on a missing `gradle-wrapper.jar` and works around `/storage/emulated/0` noexec mounts by allowing the build engine to invoke the wrapper through `/system/bin/sh`.
