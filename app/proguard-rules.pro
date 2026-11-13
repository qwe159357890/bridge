# Bridge 混淆规则
# 保活模块：KeepAliveService 通过反射注册入口 Activity，保持相关类
-keep class my.bridge.com.keepalive.** { *; }
# VNT 组网（v0.2.131，2.x）：com.vnt.* 是官方 VntApp 2.0 的 JNI 绑定，
# native 侧符号按「包名+类名」硬编码（Java_com_vnt_VntManager_nativeCreateNetwork 等）。
# R8 混淆或移除会直接抛 UnsatisfiedLinkError。
-keep class com.vnt.** { *; }

# SSH 服务器（v0.2.34）：MINA SSHD / Apache MINA / BouncyCastle 内部大量反射与
# 安全 provider 注册，整包 keep 换取 release 构建确定性（R8 只影响 release，debug 不混淆）。
-keep class org.apache.sshd.** { *; }
-keep class org.apache.mina.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.apache.sshd.**
-dontwarn org.apache.mina.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
