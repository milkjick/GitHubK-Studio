# GitHubK Studio · 原生 Termux 终端增强（本次增量改动）

版本基线：`5.0.16-full-terminal-dl`（versionCode 37）。

本文档记录相对上一版源码包的本轮增量改动。目标：在保留原有离线
工具链 / proot 崩溃防护 / 伪安装校验的前提下，把内嵌终端打磨成
“原生 Termux 体验 + IDE 构建环境严格隔离”的可维护形态。

## 核心原则

1. 终端会话 = 纯 Termux 环境。PATH 只含 `$PREFIX/bin` 与系统目录，
   绝不注入 IDE 的 `JAVA_HOME/ANDROID_HOME/GRADLE_HOME/DART_HOME`。
2. IDE 构建 = 纯工具链环境。JDK/SDK/Gradle/Flutter 只由
   `BuildEngine` / `ToolchainManager` 经 `toolchainEnv()` 注入。
   两套环境在进程与 PATH 层面互不干扰。
3. 包管理走 Termux 官方 `bin/pkg` 脚本 + `apt-get`（非 Debian 包），
   保留官方 root 检查 / 镜像机制；崩溃残留的 `dpkg/apt` 锁自动清理。

## 增量改动清单

### 1. `terminal/BuiltinRuntime.kt`

- 安装（解压 bootstrap / 本地 ZIP 导入）成功入口统一调用
  `markTermuxAptUpdatePending()`，为“首启自动拉取软件源”提供待办标记。
- 新增终端包管理辅助函数：
  - `markTermuxAptUpdatePending()` / `isTermuxAptUpdatePending()`
  - `termuxAptListsReady()`：`var/lib/apt/lists` 是否已有有效列表
  - `termuxPkgReady()`：`bin/pkg`、`bin/bash`、`sources.list`、
    `trusted.gpg.d` 信任链是否齐备
  - `updateTermuxRepositories(onOutput, force=false)`：执行
    `apt-get update`；保留当前 `sources.list`，镜像不可用自动按
    清华 → 中科大 → 北外 → packages-cf 顺序切换
  - `scheduleAptUpdateIfPending()`：首次安装后由终端入口**后台**
    执行一次 `apt update`（不阻塞 UI、不弹 Shizuku 授权，仅走
    proot Termux 沙盒通道）
- 离线 `.deb` 伪安装校验加强：从 `assets/offline-toolchain/` 复制
  内置包时逐包比对 SHA256SUMS.txt，不符的包直接删除拒绝，
  杜绝“被替换 / 混入 Debian 包”的伪安装。

### 2. `terminal/TermuxSandbox.kt`

- 新增 `GUEST_HOME = /data/data/com.termux/files/home` 常量。
- `baseArgs()` 登录参数向原生 Termux proot 对齐：
  - 增加 `--link2symlink`（bind 符号链接转 symlink，避免 glue 实体文件）
  - 增加 `$PREFIX/../home` 的双重挂载：`.../home -> /data/data/com.termux/files/home`
    与 `.../home -> /home`，使 guest 内 `HOME` 与原生 Termux 一致
  - 增加 App 工作区挂载：`filesDir/workspace -> /workspace`
    （/sdcard 之外的文件互通入口）
- `processEnv()`：`HOME` 固定指向官方 Termux home 视图，保证
  `.bashrc/.profile`、`pkg` 缓存目录与原生一致。

### 3. `ui/TerminalPage.kt`

- `startTerminal()` 的 Termux+proot 分支改用**纯净 Termux env**
  `rt.env()` 构造终端进程，不再混入 IDE 工具链环境；
  启动前先 `cleanupDpkgLocks()`（杀进程残留锁清理）并
  `scheduleAptUpdateIfPending()`（首启后台拉软件源）。
- 兜底非 proot 直跑分支同样改为使用 `rt.env()`（不再注入
  toolchain 环境），确保任何模式下终端都不被 IDE 路径污染。

### 4. `ui/ToolchainPage.kt`

- 新增 “Termux 终端 · pkg/apt 包管理器” 操作区：
  - “更新 Termux 软件源（apt update）”：显式触发，支持多镜像切换
  - “打开终端执行 pkg install”：直达纯净 Termux 终端
- 状态卡片增加终端行：`终端 pkg/apt ✓/× 软件源 ✓已拉取/待 update`，
  直观展示“IDE 构建环境”与“Termux 终端环境”两个独立状态。

## 关键行为

- 首次进入终端（bootstrap 已安装）会自动后台 `apt update`，
  完成后 `pkg install git python nodejs ...` 立即可用。
- 用户手动点击“更新 Termux 软件源”时强制重跑（force=true），
  不会被“lists 已就绪”短路跳过。
- 所有 apt/dpkg 崩溃残留锁（lock / lock-frontend / lists lock /
  archives lock）在每次启动终端或执行软件源更新前自动删除，
  防止杀进程后包管理器永久卡死。

## 验证方式（离线构建环境）

```bash
cd /root/githubk
gradle :app:assembleDebug --no-daemon \
  -Dorg.gradle.vfs.watch=false \
  -Djava.io.tmpdir=/root/githubk/.gradle-tmp
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

真机冒烟用例：
1. 环境中心 → 终端，确认提示符为 Termux login（非 root）。
2. `pkg search hello` / `pkg install -y hello` 成功。
3. `env | grep -i 'java\|android\|gradle'` 为空 → IDE 路径未混入终端。
4. 项目构建一次，确认 JDK/SDK 仍从工具链环境注入、编译正常。
5. 终端内 `ls /workspace` 可看到 App 工作区文件。
