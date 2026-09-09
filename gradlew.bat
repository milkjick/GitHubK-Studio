@echo off
setlocal
set VERSION=9.3.1
if defined GRADLE_HOME if exist "%GRADLE_HOME%\bin\gradle.bat" (
  call "%GRADLE_HOME%\bin\gradle.bat" %*
  exit /b %ERRORLEVEL%
)
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
echo GitHubK Studio: Gradle %VERSION% is not installed. Install Gradle or use the Android/Termux toolchain center.
exit /b 1
