# Bridge 混淆规则
# 保活模块：KeepAliveService 通过反射注册入口 Activity，保持相关类
-keep class my.bridge.com.keepalive.** { *; }
