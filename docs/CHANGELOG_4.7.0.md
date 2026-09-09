# 4.7.0 Native Termux Terminal

## Replaced
- Removed the interactive `ProcessBuilder(shell, "-i")` transport from the terminal UI.
- Removed the `TextView + ScrollView + EditText` terminal implementation.

## Added
- Native NDK PTY backend using `/dev/ptmx`.
- fork/setsid/controlling-tty setup.
- Dynamic `TIOCSWINSZ` resizing.
- Real stdin/stdout/stderr attached to the PTY slave.
- Native signal support.
- VT/xterm emulator state machine.
- Alternate screen for full-screen programs.
- ANSI 16/256/true-color SGR support.
- Cursor movement, erase, insert/delete characters/lines, scrolling and DEC cursor visibility.
- Android IME input connection.
- Hardware Ctrl-C/Ctrl-Z/Ctrl-D and navigation keys.
- Termux-style extra-keys toolbar.
- Native shell environment (`TERM`, `COLORTERM`, `HOME`, `TMPDIR`, toolchain PATH).
- Upstream Termux sync script pinned to v0.118.3 for projects that need byte-for-byte upstream terminal modules.

## Root cause fixed
`/system/bin/sh -i` connected to ordinary pipes is not a controlling terminal. The new PTY backend gives the child process a real tty, so `bash`, `vim`, `top`, `htop`, `tmux` and job-control signals can operate through the terminal device.
