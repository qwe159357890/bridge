# Bridge 总体架构

> 待补充：应用模块架构、分层依赖、技术选型、数据流。

## 应用模块架构（代码分层）

```
app/                      壳工程：入口、导航、DI 装配
core/
  ├── core-common/        通用工具、常量
  ├── core-model/         数据模型
  ├── core-network/       网络封装
  ├── core-storage/       本地存储
  ├── core-ui/            设计系统
  └── core-keepalive/     保活模块
feature/                  （未来）业务特性层
```

依赖方向：`app → feature → core-*`；core 间只向更基础模块依赖。

## 技术选型

- Kotlin 2.0.21 + Jetpack Compose + Hilt（KSP）
- AGP 8.13.1 / Gradle 8.10.2 / JDK 17
- minSdk 24 / targetSdk 36
- 网络：OkHttp/Retrofit；存储：DataStore/Room；日志：Timber

## 模块依赖关系图

（待补充，详见 module-map.md）
