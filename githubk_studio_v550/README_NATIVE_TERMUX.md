# GitHubK Studio 4.7.0 — Native PTY Terminal

本版本从根上删除旧的 `ProcessBuilder + Pipe + sh -i` 交互终端。

## 核心

- Linux `/dev/ptmx` master
- `grantpt()` / `unlockpt()` / `ptsname_r()`
- `fork()` + `setsid()` + controlling TTY
- `dup2(0/1/2)`
- `TIOCSWINSZ` 动态窗口尺寸
- 真正的 shell job control
- Ctrl-C / Ctrl-Z / Ctrl-D
- ANSI/xterm 颜色、光标、清屏、滚屏、备用屏幕
- Android hardware keyboard + IME
- vim/top/htop/tmux 使用真实 PTY，而不是读取 stdout 的伪交互模式

## 与截图错误的关系

旧代码：

```text
ProcessBuilder
  -> /system/bin/sh -i
  -> stdin/stdout pipe
```

这不是 TTY，所以会出现：

```text
can't find tty fd
warning: won't have full job control
```

新代码：

```text
Android TerminalView
        |
        v
NativePty JNI
        |
        v
/dev/ptmx
        |
      fork
        |
      setsid
        |
 controlling tty
        |
      exec bash
```

因此 shell 通过 `isatty(0/1/2)` 可以看到真正的终端。

## 运行时

优先使用：

```text
/files/runtime/bin/bash
```

并设置：

```text
TERM=xterm-256color
COLORTERM=truecolor
HOME=...
TMPDIR=...
```

如果 runtime bash 不可执行，才回退 `/system/bin/sh`。

## 精确 Termux upstream

官方 Termux 的架构也是 `terminal-emulator` + `terminal-view` + JNI PTY。官方仓库目前仍然独立维护这两个模块。

如果你要求源码与官方 Termux 的 `TerminalEmulator` / `TerminalView` 完全一致，请运行：

```bash
tools/sync-termux-terminal.sh
```

它会同步 Termux `v0.118.3` 的两个模块到 `vendor/termux-terminal/`，然后可以进一步替换本项目的 adapter。该 upstream 代码的许可证和版权声明必须随源码保留。
