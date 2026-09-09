# GitHubK Studio 5.0.4 工具链安装修复

## 修复内容

### 1. ARM64 原生工具 `apt: Permission denied`

- 在运行 `apt` / `apt-get` 前再次执行 Java `setExecutable()`。
- 增加 Android `/system/bin/chmod 755` 修复路径。
- Shizuku 可用时继续执行额外 chmod。
- 安装前输出 `apt` / `apt-get` 是否仍不可执行。

### 2. Android Command-line Tools 下载损坏

之前的大文件下载器会复用旧缓存并尝试 Range 续传。在部分 Android/运营商代理环境下，服务器会忽略 Range 或返回 gzip 响应，旧数据和新数据拼接后会触发：

`ID1ID2: actual ... != expected 0x1f8b`

5.0.4 改为：

- 使用 `.part` 临时文件。
- 不再盲目续传旧文件。
- 请求 `Accept-Encoding: identity`，避免 gzip 内容编码干扰。
- 下载后验证 SHA-256。
- ZIP 下载额外验证 `PK` 文件头。
- 使用 `ZipFile` 验证 central directory。
- 校验失败自动完整重试一次。
- 校验成功后才替换正式 ZIP。

### 3. Flutter/JDK

ARM64 Flutter 工具链额外安装 `openjdk-21`，并写入 `FLUTTER_JAVA_HOME`；Android 构建继续保留 JDK 17 环境。

## Android Command-line Tools

当前使用 Google Android Developers 公布的：

`commandlinetools-linux-15859902_latest.zip`

SHA-256：

`4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583`

来源：Android Developers 官方下载页。
