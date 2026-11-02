# Bridge 混淆规则
# 保活模块：KeepAliveService 通过反射注册入口 Activity，保持相关类
-keep class my.bridge.com.keepalive.** { *; }
# VNT 组网（v0.2.32，1.x）：top.wherewego.vnt.jni.* 是官方 VntApp v1.2.9 的 JNI 绑定，
# native 侧符号按「包名+类名」硬编码（Java_top_wherewego_vnt_jni_Vnt_new0 等）。
# R8 混淆或移除会直接抛 UnsatisfiedLinkError。
-keep class top.wherewego.vnt.jni.** { *; }
