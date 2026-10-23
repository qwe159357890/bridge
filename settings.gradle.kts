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
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
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
