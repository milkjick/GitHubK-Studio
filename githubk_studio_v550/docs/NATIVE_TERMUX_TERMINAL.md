# GitHubK Studio 4.7 — Native Termux-style terminal

This version replaces the old `ProcessBuilder(..., "-i") + pipe` terminal with a real Linux PTY.

## Architecture

`NativeTerminalView` -> `NativePty.Session` -> JNI -> `/dev/ptmx` -> fork/setsid/controlling tty -> shell

The rendering path is independent:

`PTY bytes -> AnsiTerminal (VT100/xterm subset) -> Canvas`

Input path:

`Android hardware/IME -> escape/control bytes -> PTY master`

Therefore Ctrl-C/Ctrl-Z are terminal line-discipline signals, not string commands. Interactive programs such as bash, vim, top/htop and tmux can run inside the same PTY rather than inside a captured stdout pipe.

## Why Shizuku is not used for PTY creation

Shizuku is useful for privileged file/toolchain operations, but it should not be the terminal transport. A terminal session must be owned by the app process so the PTY, shell, HOME and runtime filesystem have one consistent UID/SELinux context.

## Runtime requirements

The selected shell is `$filesDir/runtime/bin/bash` when it exists and is executable, otherwise `/system/bin/sh`. The environment includes:

- `TERM=xterm-256color`
- `COLORTERM=truecolor`
- `HOME`
- `TMPDIR`
- toolchain `PATH` / `LD_LIBRARY_PATH`

The runtime bootstrap must preserve executable bits (755 for binaries, 644 for shared libraries/data). Do not attempt to run a packaged bash through `/system/bin/sh -c` as the interactive transport.

## Upstream Termux relationship

The design follows the same core split used by Termux: native PTY process creation plus a terminal-emulator state machine plus a terminal view. The official Termux repository currently contains separate `terminal-emulator` and `terminal-view` modules, and its JNI layer creates `/dev/ptmx`, forks a child, creates a session/controlling TTY, sets the window size, and executes the shell. See the upstream repository and license before redistributing upstream source.

This GitHubK implementation is intentionally independent of the upstream source files; it is not claimed to be a byte-for-byte copy of Termux's `TerminalView`/`TerminalEmulator`.

## Important limitation

A literal 100% upstream Termux terminal means vendoring the full upstream `terminal-emulator` and `terminal-view` modules (plus their exact JNI/native code and licenses). This archive instead provides a self-contained native PTY implementation and a compact VT/xterm renderer so the project can remain source-buildable without shipping a large upstream module tree. If exact upstream parity is required, run `tools/sync-termux-terminal.sh` and replace the adapter according to the instructions in that script.
