package com.example.myempty.githubk.core

import java.io.File

/**
 * ProjectTemplate v3.0
 * 生成完整可构建的 Android / Flutter / 空项目骨架，替代 2.4 只生成 settings.gradle 的问题。
 */
object ProjectTemplate {

    fun create(root: File, name: String, type: String) {
        root.mkdirs()
        when (type) {
            "Android" -> android(root, name)
            "Flutter" -> flutter(root, name)
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
}