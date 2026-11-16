// Bridge —— 企业级多模块 Android 项目
//
// 模块拓扑：
//   app                 壳工程：入口、导航、DI 装配
//   core/core-common    通用工具与扩展
//   core/core-model     数据模型
//   core/core-network   网络封装
//   core/core-storage   本地存储封装
//   core/core-ui        设计系统与通用组件
//   core/core-keepalive 保活模块（借鉴 Aurelay + SmsForwarder）
//
// 依赖方向：app → feature(未来) → core-*；core-* 之间仅允许向更基础的模块依赖。
pluginManagement {
    repositories {
        // 阿里云镜像优先（Maven Central 直连国内不通，实测 coil 2.7.0 拉取超时）；
        // 镜像未命中时回落官方源，保证冷门包仍可拉取
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像优先（同上，官方源兜底）
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "Bridge"

include(":app")
include(":core:core-common")
include(":core:core-model")
include(":core:core-network")
include(":core:core-storage")
include(":core:core-ui")
include(":core:core-keepalive")
