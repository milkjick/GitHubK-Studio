package com.example.myempty.githubk.core

import java.io.File

/**
 * ProjectTemplate v3.0
 * 生成完整可构建的 Android / Flutter / 空项目骨架，替代 2.4 只生成 settings.gradle 的问题。
 */
object ProjectTemplate {

    fun create(root: File, name: String, type: String) {
        root.mkdirs()
        when (type.lowercase().replace(" ", "").replace("-", "")) {
            "android" -> android(root, name)
            "flutter" -> flutter(root, name)
            "compose", "jetpackcompose", "kotlincompose", "androidcompose" -> compose(root, name)
            "web", "html", "staticweb", "website", "frontend", "vanillajs" -> web(root, name)
            "reactnative", "react", "rn", "reactnativeapp", "reactnativeproject" -> reactNative(root, name)
            "嵌入式c", "embedded", "embeddedc", "cembedded" -> embedded(root, name)
            "linuxtool", "linux", "linuxc", "clitool", "c工具" -> linuxTool(root, name)
            "xposed", "xposedmodule", "xposed模块", "lsposed", "lsposedmodule" -> xposed(root, name)
            else -> emptyProject(root, name)
        }
    }

    private fun write(f: File, content: String) {
        f.parentFile?.mkdirs()
        f.writeText(content.trimIndent() + "\n")
    }

    private fun writeGradleLauncher(root: File) {
        val launcher = File(root, "gradlew")
        launcher.parentFile?.mkdirs()
        launcher.writeText("""#!/usr/bin/env sh
set -eu
VERSION=9.3.1
BASE_DIR=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)
DIST_DIR="${'$'}{GRADLE_USER_HOME:-${'$'}HOME/.gradle}/githubk-distributions/gradle-${'$'}VERSION"
GRADLE_BIN="${'$'}DIST_DIR/bin/gradle"
if [ -x "${'$'}GRADLE_BIN" ]; then exec "${'$'}GRADLE_BIN" "${'$'}@"; fi
if [ -n "${'$'}{GRADLE_HOME:-}" ] && [ -x "${'$'}GRADLE_HOME/bin/gradle" ]; then exec "${'$'}GRADLE_HOME/bin/gradle" "${'$'}@"; fi
if command -v gradle >/dev/null 2>&1; then
  SYS_VER=${'$'}(gradle --version 2>/dev/null | awk '/^Gradle / {print ${'$'}2; exit}') || SYS_VER=""
  if [ "${'$'}SYS_VER" = "${'$'}VERSION" ]; then exec gradle "${'$'}@"; fi
fi
echo "GitHubK Studio: Gradle ${'$'}VERSION is not installed. Open the IDE Environment Center first." >&2
exit 1
""")
        launcher.setExecutable(true, false)
        write(File(root, "gradle/wrapper/gradle-wrapper.properties"), """
            distributionBase=GRADLE_USER_HOME
            distributionPath=wrapper/dists
            distributionUrl=https\\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
            zipStoreBase=GRADLE_USER_HOME
            zipStorePath=wrapper/dists
        """)
    }

    // ---------------- Android 完整项目 ----------------

    private fun android(root: File, name: String) {
        val pkg = "com.example.${name.lowercase().replace(" ", "").replace("-", "")}"

        write(File(root, "settings.gradle"), """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "$name"
            include(":app")
        """)

        write(File(root, "build.gradle"), """
            plugins {
                id 'com.android.application' version '8.2.0' apply false
                id 'org.jetbrains.kotlin.android' version '1.9.20' apply false
            }
        """)

        write(File(root, "gradle.properties"), """
            org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            android.useAndroidX=true
        """)
        writeGradleLauncher(root)

        write(File(root, "app/build.gradle"), """
            plugins {
                id 'com.android.application'
                id 'org.jetbrains.kotlin.android'
            }

            android {
                namespace '$pkg'
                compileSdk 36

                defaultConfig {
                    applicationId '$pkg'
                    minSdk 23
                    targetSdk 36
                    versionCode 1
                    versionName '1.0'
                }

                compileOptions {
                    sourceCompatibility JavaVersion.VERSION_17
                    targetCompatibility JavaVersion.VERSION_17
                }
                kotlinOptions { jvmTarget = '17' }
            }

            dependencies {
            }
        """)

        write(File(root, "app/src/main/AndroidManifest.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:label="$name"
                    android:theme="@style/AppTheme">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """)

        write(File(root, "app/src/main/res/values/themes.xml"), """
            <resources>
                <style name="AppTheme" parent="@android:style/Theme.Material.Light.NoActionBar"/>
            </resources>
        """)

        write(File(root, "app/src/main/res/values/strings.xml"), """
            <resources>
                <string name="app_name">$name</string>
            </resources>
        """)

        write(File(root, "app/src/main/java/${pkg.replace('.', '/')}/MainActivity.kt"), """
            package $pkg

            import android.os.Bundle
            import android.widget.TextView
            import android.app.Activity

            class MainActivity : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    val tv = TextView(this).apply {
                        text = "Hello from $name !"
                        textSize = 22f
                    }
                    setContentView(tv)
                }
            }
        """)
    }

    // ---------------- Jetpack Compose 项目（完整可构建） ----------------

    private fun compose(root: File, name: String) {
        val pkg = "com.example.${name.lowercase().replace(" ", "").replace("-", "")}"

        write(File(root, "settings.gradle"), """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "$name"
            include(":app")
        """)

        write(File(root, "build.gradle"), """
            plugins {
                id 'com.android.application' version '8.2.0' apply false
                id 'org.jetbrains.kotlin.android' version '1.9.20' apply false
                id 'org.jetbrains.kotlin.plugin.compose' version '1.9.20' apply false
            }
        """)

        write(File(root, "gradle.properties"), """
            org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            android.useAndroidX=true
        """)
        writeGradleLauncher(root)

        write(File(root, "app/build.gradle"), """
            plugins {
                id 'com.android.application'
                id 'org.jetbrains.kotlin.android'
                id 'org.jetbrains.kotlin.plugin.compose'
            }

            android {
                namespace '$pkg'
                compileSdk 34

                defaultConfig {
                    applicationId '$pkg'
                    minSdk 23
                    targetSdk 34
                    versionCode 1
                    versionName '1.0'
                }

                compileOptions {
                    sourceCompatibility JavaVersion.VERSION_17
                    targetCompatibility JavaVersion.VERSION_17
                }
                kotlinOptions { jvmTarget = '17' }
                buildFeatures { compose = true }
            }

            dependencies {
                implementation platform('androidx.compose:compose-bom:2023.10.01')
                implementation 'androidx.compose.ui:ui'
                implementation 'androidx.compose.material3:material3'
                implementation 'androidx.activity:activity-compose:1.8.1'
                implementation 'androidx.compose.ui:ui-tooling-preview'
            }
        """)

        write(File(root, "app/src/main/AndroidManifest.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:label="$name"
                    android:theme="@style/AppTheme">
                    <activity android:name=".MainActivity" android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN"/>
                            <category android:name="android.intent.category.LAUNCHER"/>
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
        """)

        write(File(root, "app/src/main/res/values/themes.xml"), """
            <resources>
                <style name="AppTheme" parent="@android:style/Theme.Material.Light.NoActionBar"/>
            </resources>
        """)

        write(File(root, "app/src/main/res/values/strings.xml"), """
            <resources>
                <string name="app_name">$name</string>
            </resources>
        """)

        write(File(root, "app/src/main/java/${pkg.replace('.', '/')}/MainActivity.kt"), """
            package $pkg

            import android.os.Bundle
            import androidx.activity.ComponentActivity
            import androidx.activity.compose.setContent
            import androidx.compose.foundation.layout.Arrangement
            import androidx.compose.foundation.layout.Column
            import androidx.compose.foundation.layout.fillMaxSize
            import androidx.compose.foundation.layout.padding
            import androidx.compose.material3.MaterialTheme
            import androidx.compose.material3.Surface
            import androidx.compose.material3.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.Alignment
            import androidx.compose.ui.Modifier
            import androidx.compose.ui.unit.dp

            class MainActivity : ComponentActivity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContent {
                        MaterialTheme {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                Greeting(name = "$name")
                            }
                        }
                    }
                }
            }

            @Composable
            fun Greeting(name: String) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(text = "Hello, Jetpack Compose!", style = MaterialTheme.typography.headlineMedium)
                    Text(text = "来自 $name", style = MaterialTheme.typography.bodyMedium)
                }
            }
        """)
    }

    // ---------------- Web 项目（静态 HTML/CSS/JS，无需构建） ----------------

    private fun web(root: File, name: String) {
        write(File(root, "index.html"), """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>$name</title>
                <link rel="stylesheet" href="styles.css">
            </head>
            <body>
                <main class="container">
                    <h1>$name</h1>
                    <p>这是一个由 GitHubK Studio 生成的 Web 项目骨架。</p>
                    <button id="cta">点击我</button>
                    <p id="status"></p>
                </main>
                <script src="app.js"></script>
            </body>
            </html>
        """)

        write(File(root, "styles.css"), """
            * { box-sizing: border-box; margin: 0; padding: 0; }
            body {
                font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
                min-height: 100vh; display: flex; align-items: center; justify-content: center;
                background: linear-gradient(135deg, #7950F2 0%, #22C55E 100%); color: #fff;
            }
            .container { text-align: center; padding: 2rem; background: rgba(255,255,255,.12);
                border-radius: 16px; backdrop-filter: blur(6px); }
            h1 { margin-bottom: .5rem; } p { opacity: .9; }
            button { margin-top: 1rem; padding: .6rem 1.2rem; border: none; border-radius: 8px;
                font-size: 1rem; cursor: pointer; background: #fff; color: #7950F2; }
        """)

        write(File(root, "app.js"), """
            const cta = document.getElementById('cta');
            const status = document.getElementById('status');
            let clicks = 0;
            cta.addEventListener('click', () => {
                clicks++;
                status.textContent = '你已经点击了 ' + clicks + ' 次。';
            });
        """)

        write(File(root, "README.md"), """
            # $name

            一个由 GitHubK Studio 生成的静态 Web 项目骨架。

            - `index.html` —— 页面结构
            - `styles.css` —— 样式
            - `app.js` —— 交互逻辑

            ## 使用
            直接在文件查看器中编辑即可预览；也可用 `export_file` 导出整目录。
        """)
    }

    // ---------------- React Native 项目（JS 骨架） ----------------

    private fun reactNative(root: File, name: String) {
        val slug = name.lowercase().replace(" ", "-").replace("-", "-")

        write(File(root, "package.json"), """
            {
              "name": "$slug",
              "version": "1.0.0",
              "private": true,
              "main": "index.js",
              "scripts": {
                "start": "react-native start",
                "android": "react-native run-android",
                "ios": "react-native run-ios",
                "lint": "eslint ."
              },
              "dependencies": {
                "react": "18.2.0",
                "react-native": "0.73.6"
              },
              "devDependencies": {
                "@babel/core": "^7.20.0",
                "eslint": "^8.19.0",
                "react": "18.2.0",
                "react-native": "0.73.6"
              }
            }
        """)

        write(File(root, "index.js"), """
            import { AppRegistry } from 'react-native';
            import App from './App';
            import { name as appName } from './app.json';

            AppRegistry.registerComponent(appName, () => App);
        """)

        write(File(root, "App.js"), """
            import React from 'react';
            import { View, Text, StyleSheet } from 'react-native';

            export default function App() {
              return (
                <View style={styles.container}>
                  <Text style={styles.title}>Hello, React Native!</Text>
                  <Text style={styles.subtitle}>来自 GitHubK Studio 的 React Native 项目</Text>
                </View>
              );
            }

            const styles = StyleSheet.create({
              container: { flex: 1, justifyContent: 'center', alignItems: 'center', backgroundColor: '#7950F2' },
              title: { color: '#fff', fontSize: 22, fontWeight: '700' },
              subtitle: { color: '#fff', fontSize: 16, marginTop: 8 },
            });
        """)

        write(File(root, "app.json"), """
            {
              "name": "$name",
              "displayName": "$name"
            }
        """)

        write(File(root, ".babelrc"), """
            {
              "presets": ["module:metro-react-native-babel-preset"]
            }
        """)

        write(File(root, "README.md"), """
            # $name

            一个由 GitHubK Studio 生成的 React Native 项目骨架。

            包含 `package.json` / `App.js` / `index.js` / 基础配置。

            要运行 `react-native`，需在设备上安装对应的运行时与依赖；在当前环境中可直接编辑源码并用 `export_file` 导出。
        """)
    }

    // ---------------- Flutter 项目（骨架） ----------------

    private fun flutter(root: File, name: String) {
        write(File(root, "pubspec.yaml"), """
            name: ${name.lowercase().replace(" ", "_")}
            description: A new Flutter project.
            version: 1.0.0+1

            environment:
              sdk: '>=3.0.0 <4.0.0'

            dependencies:
              flutter:
                sdk: flutter

            dev_dependencies:
              flutter_test:
                sdk: flutter
              flutter_lints: ^3.0.0

            flutter:
              uses-material-design: true
        """)

        write(File(root, "lib/main.dart"), """
            import 'package:flutter/material.dart';

            void main() {
              runApp(const MyApp());
            }

            class MyApp extends StatelessWidget {
              const MyApp({super.key});

              @override
              Widget build(BuildContext context) {
                return MaterialApp(
                  title: '$name',
                  theme: ThemeData(colorSchemeSeed: Colors.deepPurple, useMaterial3: true),
                  home: const HomePage(),
                );
              }
            }

            class HomePage extends StatelessWidget {
              const HomePage({super.key});

              @override
              Widget build(BuildContext context) {
                return Scaffold(
                  appBar: AppBar(title: const Text('$name')),
                  body: const Center(child: Text('Hello from $name!')),
                );
              }
            }
        """)
    }

    private fun emptyProject(root: File, name: String) {
        write(File(root, "README.md"), "# $name\n\nCreated by GitHubK Studio 2.5\n")
        write(File(root, ".gitignore"), ".gradle/\nbuild/\n.idea/\n*.iml\n")
    }

    /** 嵌入式（C 裸机/MCU 风格）模板：可被本机 gcc 交叉/本机编译，演示寄存器式 GPIO 抽象。 */
    private fun embedded(root: File, name: String) {
        write(File(root, "README.md"), """
            # $name

            GitHubK Studio 嵌入式 C 模板。
            - 纯 C 无外部依赖，本机 `make` 即可构建出 app 可执行文件；
            - `hal/` 演示 GPIO/UART 抽象层，方便迁移到 STM32/ESP32 等平台；
            - 支持在 AI 工作台直接修改、构建、烧录脚本生成。
        """)
        write(File(root, ".gitignore"), "build/\n*.o\napp\n")
        write(File(root, "Makefile"), """
            CC ?= gcc
            CFLAGS = -Wall -Wextra -O2 -Ihal
            TARGET = app
            SRCS = src/main.c hal/gpio.c hal/uart.c
            OBJS = ${'$'}(SRCS:.c=.o)

            ${'$'}(TARGET): ${'$'}(OBJS)
            	${'$'}(CC) ${'$'}(CFLAGS) -o ${'$'}@ ${'$'}(OBJS)

            %.o: %.c
            	${'$'}(CC) ${'$'}(CFLAGS) -c ${'$'}< -o ${'$'}@

            clean:
            	rm -f ${'$'}(TARGET) ${'$'}(OBJS)

            run: ${'$'}(TARGET)
            	./${'$'}(TARGET)
        """)
        write(File(root, "src/main.c"), """
            #include <stdio.h>
            #include "gpio.h"
            #include "uart.h"

            int main(void) {
                gpio_init();
                uart_init();
                printf("[%s] board boot ok\\n", "HAL");
                gpio_write(LED, 1);
                uart_send("Hello embedded world!\\n");
                for (int i = 0; i < 3; i++) {
                    printf("tick %d\\n", i + 1);
                }
                gpio_write(LED, 0);
                return 0;
            }
        """)
        write(File(root, "hal/gpio.h"), """
            #ifndef GPIO_H
            #define GPIO_H
            typedef enum { LED = 0, KEY = 1 } pin_t;
            void gpio_init(void);
            void gpio_write(pin_t p, int on);
            int  gpio_read(pin_t p);
            #endif
        """)
        write(File(root, "hal/gpio.c"), """
            #include "gpio.h"
            #include <stdio.h>

            void gpio_init(void) { puts("gpio: init"); }
            void gpio_write(pin_t p, int on) { printf("gpio: pin %d = %d\\n", (int)p, on); }
            int  gpio_read(pin_t p) { (void)p; return 1; }
        """)
        write(File(root, "hal/uart.h"), """
            #ifndef UART_H
            #define UART_H
            void uart_init(void);
            void uart_send(const char* s);
            #endif
        """)
        write(File(root, "hal/uart.c"), """
            #include "uart.h"
            #include <stdio.h>

            void uart_init(void) { puts("uart: init"); }
            void uart_send(const char* s) { if (s) puts(s); }
        """)
    }

    /** Linux 命令行工具（C）模板：参数解析 + 管道式输出 + Makefile。 */
    private fun linuxTool(root: File, name: String) {
        write(File(root, "README.md"), """
            # $name

            GitHubK Studio Linux 工具模板（C / Makefile）。
            `make` 构建，`./$name --help` 查看用法；可直接在 AI 工作台开发与运行。
        """)
        write(File(root, ".gitignore"), "build/\n*.o\n$name\n")
        write(File(root, "Makefile"), """
            CC ?= gcc
            CFLAGS = -Wall -Wextra -O2
            TARGET = $name
            SRCS = src/main.c
            OBJS = ${'$'}(SRCS:.c=.o)

            ${'$'}(TARGET): ${'$'}(OBJS)
            	${'$'}(CC) ${'$'}(CFLAGS) -o ${'$'}@ ${'$'}(OBJS)

            %.o: %.c
            	${'$'}(CC) ${'$'}(CFLAGS) -c ${'$'}< -o ${'$'}@

            clean:
            	rm -f ${'$'}(TARGET) ${'$'}(OBJS)

            run: ${'$'}(TARGET)
            	./${'$'}(TARGET)
        """)
        write(File(root, "src/main.c"), """
            #include <stdio.h>
            #include <string.h>

            static void usage(const char* prog) {
                printf("usage: %s [--name NAME] [--repeat N]\\n", prog);
            }

            int main(int argc, char** argv) {
                const char* name = "world";
                int repeat = 1;
                for (int i = 1; i < argc; i++) {
                    if (!strcmp(argv[i], "--name") && i + 1 < argc) name = argv[++i];
                    else if (!strcmp(argv[i], "--repeat") && i + 1 < argc) repeat = atoi(argv[++i]);
                    else if (!strcmp(argv[i], "-h") || !strcmp(argv[i], "--help")) { usage(argv[0]); return 0; }
                }
                if (repeat < 1) repeat = 1;
                for (int i = 0; i < repeat; i++) printf("Hello, %s! (line %d)\\n", name, i + 1);
                return 0;
            }
        """)
    }

    // ---------------- Xposed / LSPosed 模块 ----------------

    private fun xposed(root: File, name: String) {
        val pkg = "com.example.${name.lowercase().replace(" ", "").replace("-", "")}"
        write(File(root, "settings.gradle"), """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "$name"
            include(":app")
        """)
        write(File(root, "build.gradle"), """
            plugins {
                id 'com.android.application' version '8.2.0' apply false
            }
        """)
        write(File(root, "gradle.properties"), """
            org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
            android.useAndroidX=true
        """)
        writeGradleLauncher(root)
        write(File(root, "app/build.gradle"), """
            plugins {
                id 'com.android.application'
            }

            android {
                namespace '$pkg'
                compileSdk 34

                defaultConfig {
                    applicationId '$pkg'
                    minSdk 26
                    targetSdk 34
                    versionCode 1
                    versionName '1.0'
                }

                compileOptions {
                    sourceCompatibility JavaVersion.VERSION_17
                    targetCompatibility JavaVersion.VERSION_17
                }

                sourceSets {
                    main {
                        // 若 app/libs 未提供 XposedBridgeApi-82.jar，则启用内置 API 桩（src/main/java-stub），
                        // 保证离线也能编译；一旦放入真实 jar 会自动忽略桩，避免重复类冲突。
                        if (fileTree(dir: 'libs', include: ['*.jar']).files.isEmpty()) {
                            java.srcDir 'src/main/java-stub'
                        }
                    }
                }
            }

            dependencies {
                // Xposed API（编译期）：请将 XposedBridgeApi-82.jar 放到 app/libs/ 目录。
                // 可从官方 XposedBridge 仓库 / LSPosed 文档获取；也可在对话中把 jar 作为附件上传到 app/libs。
                // 若缺失，模板会用内置桩（src/main/java-stub）编译；请务必在发布前换成真实 API。
                compileOnly fileTree(dir: 'libs', include: ['*.jar'])
            }
        """)
        write(File(root, "app/src/main/AndroidManifest.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                <application
                    android:label="$name"
                    android:theme="@android:style/Theme.Material.Light.NoActionBar">
                    <!-- Xposed/LSPosed 模块声明：安装后在 LSPosed 中勾选作用域即可生效 -->
                    <meta-data
                        android:name="xposedmodule"
                        android:value="true" />
                    <meta-data
                        android:name="xposeddescription"
                        android:value="$name module" />
                    <meta-data
                        android:name="xposedminversion"
                        android:value="82" />
                    <meta-data
                        android:name="xposedscope"
                        android:resource="@array/xposed_scope" />
                </application>

            </manifest>
        """)
        write(File(root, "app/src/main/res/values/arrays.xml"), """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <!-- 作用域：要 Hook 的目标应用包名，按需修改/增删 -->
                <string-array name="xposed_scope">
                    <item>com.android.systemui</item>
                </string-array>
            </resources>
        """)
        // xposed_init：入口类清单（每行一个完整类名）
        write(File(root, "app/src/main/assets/xposed_init"), """
            $pkg.HookMain
        """)
        write(File(root, "app/src/main/java/${pkg.replace('.', '/')}/HookMain.java"), """
            package $pkg;

            import android.app.Application;
            import android.content.Context;
            import android.util.Log;

            import de.robv.android.xposed.IXposedHookLoadPackage;
            import de.robv.android.xposed.XC_MethodHook;
            import de.robv.android.xposed.XposedBridge;
            import de.robv.android.xposed.XposedHelpers;
            import de.robv.android.xposed.callbacks.XC_LoadPackage;

            /** Xposed/LSPosed 模块入口：模块被加载时会回调 handleLoadPackage。 */
            public class HookMain implements IXposedHookLoadPackage {

                private static final String TAG = "$name";

                @Override
                public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
                    // 仅对清单中声明的作用域生效（此处示例为 SystemUI）
                    if (!"com.android.systemui".equals(lpparam.packageName)) {
                        return;
                    }
                    XposedBridge.log(TAG + ": loaded into " + lpparam.packageName);
                    log("模块已加载: " + lpparam.packageName);

                    // 示例 Hook：应用 attach 时打日志。真正功能请按需求替换下面的实现。
                    XposedHelpers.findAndHookMethod(
                            Application.class, "attach", Context.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    log("attach: " + param.thisObject.getClass().getName());
                                }
                            });
                }

                private static void log(String msg) {
                    XposedBridge.log(TAG + ": " + msg);
                    Log.i(TAG, msg);
                }
            }
        """)
        // 内置 Xposed API 桩（仅当 app/libs 无真实 jar 时参与编译，占位用，运行时由宿主框架提供真实 API）
        write(File(root, "app/src/main/java-stub/de/robv/android/xposed/IXposedHookLoadPackage.java"), """
            package de.robv.android.xposed;

            import de.robv.android.xposed.callbacks.XC_LoadPackage;

            /** Xposed 模块加载回调接口（桩）：宿主框架在模块被加载时回调。 */
            public interface IXposedHookLoadPackage {
                void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
            }
        """)
        write(File(root, "app/src/main/java-stub/de/robv/android/xposed/XposedBridge.java"), """
            package de.robv.android.xposed;

            /** Xposed 桥接日志工具（桩）。 */
            public final class XposedBridge {
                private XposedBridge() {}
                public static void log(String text) {}
            }
        """)
        write(File(root, "app/src/main/java-stub/de/robv/android/xposed/XposedHelpers.java"), """
            package de.robv.android.xposed;

            /** Xposed 辅助方法（桩）：占位，保证离线可编译。 */
            public final class XposedHelpers {
                private XposedHelpers() {}
                public static Object findAndHookMethod(Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
                    return null;
                }
                public static Object findAndHookMethod(String className, ClassLoader classLoader, String methodName, Object... parameterTypesAndCallback) {
                    return null;
                }
                public static Object findAndHookMethod(Class<?> clazz, String methodName, XC_MethodHook... hooks) {
                    return null;
                }
            }
        """)
        write(File(root, "app/src/main/java-stub/de/robv/android/xposed/XC_MethodHook.java"), """
            package de.robv.android.xposed;

            /** 方法 Hook 回调（桩）。 */
            public abstract class XC_MethodHook {
                public XC_MethodHook() {}
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

                /** Hook 参数（桩）：仅暴露常用字段。 */
                public static class MethodHookParam {
                    public Object thisObject;
                    public Object[] args;
                    public Object result;
                    public Throwable throwable;
                    public Object getResult() throws Throwable { return result; }
                    public void setResult(Object result) throws Throwable { this.result = result; }
                    public void setThrowable(Throwable throwable) throws Throwable { this.throwable = throwable; }
                    public Object getThisObject() { return thisObject; }
                    public Object[] getArgs() { return args; }
                    public int getResultType() { return 0; }
                }

                /** 卸载句柄（桩）。 */
                public static class Unhook {
                    public void unhook() {}
                }
            }
        """)
        write(File(root, "app/src/main/java-stub/de/robv/android/xposed/callbacks/XC_LoadPackage.java"), """
            package de.robv.android.xposed.callbacks;

            import android.content.pm.ApplicationInfo;
            import de.robv.android.xposed.IXposedHookLoadPackage;

            /** 包加载回调（桩）。 */
            public abstract class XC_LoadPackage implements IXposedHookLoadPackage {
                public static class LoadPackageParam {
                    public String packageName;
                    public String processName;
                    public ApplicationInfo appInfo;
                    public ClassLoader classLoader;
                    public boolean isFirstApplication;
                }
            }
        """)
        write(File(root, "README.md"), """
            # $name — Xposed/LSPosed 模块

            GitHubK Studio Xposed 模块模板（纯 Java，无额外 UI）。

            ## 目录
            - `app/src/main/java/$pkg/HookMain.java` —— 模块入口（handleLoadPackage）
            - `app/src/main/assets/xposed_init` —— Xposed 读取的入口类清单
            - `app/src/main/AndroidManifest.xml` —— xposedmodule / 作用域 声明
            - `app/src/main/res/values/arrays.xml` —— 作用域包名列表（xposed_scope）

            ## 使用步骤
            1. 编译依赖：若 `app/libs/` 无 **XposedBridgeApi-82.jar**，模板会用内置 API 桩（`app/src/main/java-stub`）离线编译，此时可先跑通构建流程。
               - 正式开发/发布请在对话中上传该 jar 附件到项目 `app/libs`（或用
                 `download_file {"url":"<jar 直链>","path":"<绝对路径>/app/libs/XposedBridgeApi-82.jar"}` 下载）；
                 放入真实 jar 后构建会自动忽略桩，避免重复类。
            2. 修改 `arrays.xml` 的作用域为你真正要 Hook 的包名；在 `HookMain.java` 写你的 Hook 逻辑。
            3. 构建：AI 工作台执行 build / 打包 APK。
            4. 安装模块 → 在 LSPosed（或 Xposed 框架）中启用并勾选作用域 → 重启目标应用生效。

            > 提示：普通 APK 的 hook 逻辑里不应引用 XposedBridge API 的实现，只保留编译期引用（compileOnly），
            > 模块运行时的 API 由宿主框架注入，因此千万不要把 XposedBridgeApi 打进 dex。
            > 内置桩仅为占位（方法均空实现），只在缺少真实 jar 时启用，生产构建务必放置真实 XposedBridgeApi。
        """)
    }
}