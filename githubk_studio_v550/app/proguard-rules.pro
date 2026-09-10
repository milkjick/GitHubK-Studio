# ============================================================
# GitHubK Studio ProGuard / R8 规则（保守以保障运行时稳定）
# ============================================================
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, SourceFile, LineNumberTable

# ---- Gson 反射序列化（大量 Store/消息/项目模型使用）----
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.reflect.TypeToken { *; }
-keepclassmembers class * { @com.google.gson.annotations.SerializedName <fields>; }
-keepclasseswithmembers,allowshrinking,includedescriptorclasses class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- 应用核心逻辑：镜像/序列化/审计/会话等反射与分类加载频繁，整体保留以保证可运行 ----
-keep class com.example.myempty.githubk.** { *; }

# ---- 保留 Activity/Service/Receiver 组件（AGP 已处理，兜底）----
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# ---- 优先保留 AIDL / 跨进程接口 ----
-keep class * implements android.os.IInterface

# ---- 保留 native 方法与 JNI 绑定类 ----
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.example.myempty.githubk.terminal.** { *; }

# ---- 保留 Java/Jang / Lombok（如引入）----
# -dontwarn javax.**

# ---- 保留 Kotlin 协程/反射常用 ----
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.reflect.**
