# 4.8 build fix

The 4.6 NativeTermux archive was missing `app/src/main/AndroidManifest.xml`. That caused `processDebugMainManifest` to fail before Kotlin/NDK compilation.

This release restores a complete manifest and uses the known-good native-Termux project layout:

- app/src/main/AndroidManifest.xml
- app/src/main/cpp/Android.mk
- app/src/main/cpp/termux_pty.c
- app/src/main/java/.../terminal/pty/NativePty.kt
- app/src/main/java/.../ui/terminal/NativeTerminalView.kt
- app/src/main/java/.../terminal/vt/AnsiTerminal.kt

The previous 4.6 Java NativePty/C JNI package mismatch is also removed: both now use `com.example.myempty.githubk.terminal.pty.NativePty`.
