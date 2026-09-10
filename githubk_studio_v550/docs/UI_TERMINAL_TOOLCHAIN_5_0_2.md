# GitHubK Studio 5.0.2 UI / Terminal / Toolchain fixes

- IDE actions that were previously in the bottom Live Preview bar/floating controls are now in the top command area.
- Search is available in the top command area; the bottom search FAB was removed.
- Live Preview, Problems, Build Output, Terminal, Environment and Git are top actions.
- TerminalPage is presented as a Studio Console rather than a Termux clone; the PTY backend remains native and compatible with interactive programs.
- Runtime bootstrap is pinned to an official Termux apt-android-7 bootstrap and SHA-256 verified.
- Official Termux bootstrap package-manager paths are rewritten to the GitHubK private prefix and `pkg` is replaced by a portable wrapper so package installation does not depend on `/data/data/com.termux`.
- Android command-line tools use the official Android 2026.1.4 package (15859902) and sdkmanager.
- ARM64 Flutter uses the maintained Termux ARM64 Flutter 3.44.9 package instead of the incompatible official Linux glibc SDK.
