# GitHubK Studio 4.8 ZeroTermux extensions

This release keeps the interactive terminal on a real Linux PTY and adds a ZeroTermux-style feature layer.

## Included

- real PTY / fork / setsid / controlling TTY / resize / signals
- VT/ANSI terminal rendering
- Ctrl-C / Ctrl-Z / Ctrl-D / job control
- vim / top / htop / tmux compatible terminal I/O
- multiple shell sessions through the existing page/session architecture
- Termux-like PREFIX/HOME/PATH/LD_LIBRARY_PATH environment
- APT/PKG mirror switching (official/TUNA/USTC/BFSU)
- package tool shortcuts for git/python/node/vim/nano/less/htop/tmux
- PRoot-Distro helper and container diagnostics
- runtime tar.gz backup and restore through Android SAF
- Termux storage setup helper
- Termux:X11 environment helper
- QEMU user-mode package/diagnostic helper
- Termux:API/Styling/Tasker/Widget/Boot/X11 plugin detection
- one-click environment diagnostics

## Important

The app does not bundle third-party Linux distributions or QEMU binaries by default. The feature center detects and invokes packages installed in the GitHubK private Termux-compatible PREFIX. This keeps the APK small and avoids silently downloading large rootfs images.

The interactive commands are executed by the native PTY terminal; non-interactive backup/restore operations use Android's process API only for tar archiving and are never used as the terminal backend.

## Source / license notes

ZeroTermux is GPLv3-only. Its repository states that it is based on Termux and lists terminal-emulator and terminal-view as Apache-2.0 components. This implementation adds an independent integration layer and does not copy ZeroTermux source files. See the upstream project for the original license and attribution requirements:
https://github.com/hanxinhao000/ZeroTermux
https://github.com/termux/termux-app
