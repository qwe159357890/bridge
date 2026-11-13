plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// 内部测试阶段统一签名配置：本机与 CI 共用同一份 keystore，
// 避免「签名冲突」导致无法覆盖安装。keystore 入库（PKCS12 格式）。
val keystoreFile = rootProject.file("app/bridge-debug.keystore")

android {
    namespace = "my.bridge.com"
    compileSdk = 36

    defaultConfig {
        applicationId = "my.bridge.com"
        minSdk = 24
        targetSdk = 36
        // 版本号单一来源：release.py 会自动递增并写回此处，CI 打包直接读取。
        versionCode = 4
        versionName = "0.2.134"

        // VNT 组网（v0.2.131，2.x）：服务端已升级到 VNT 2.0，客户端随升官方 v2.0.12。
        // 官方 v2.0.12 提供 3 套 ABI 的 libvnt_jni.so（arm64-v8a / armeabi-v7a / x86_64），
        // 全量纳入，避免「装上了但组网加载不到 native 库」的假成功。
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "armeabi-v7a"
            abiFilters += "x86_64"
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // AGP 已内置名为 "debug" 的 signingConfig，用 getByName 覆盖其 keystore，
        // 指向入库的固定 keystore，保证本机与 CI 签名一致（不能用 create 重复建）。
        getByName("debug") {
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // SSH 服务器（v0.2.34）：MINA SSHD 大量使用 java.nio.file.*（API 26+ 才有），
        // core library desugaring（desugar_jdk_libs 2.x 含 NIO）使其在 minSdk 24 可用。
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // SSH 服务器：sshd jar 内带 META-INF/DEPENDENCIES 等引发打包冲突（社区实锤
    // 「3 files found with path 'META-INF/DEPENDENCIES'」），显式排除。
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/ASL2.0",
            )
        }
    }
}

dependencies {
    // ---- core 模块 ----
    implementation(project(":core:core-common"))
    implementation(project(":core:core-model"))
    implementation(project(":core:core-network"))
    implementation(project(":core:core-storage"))
    implementation(project(":core:core-ui"))
    implementation(project(":core:core-keepalive"))

    // ---- AndroidX / Compose ----
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)

    // ---- Hilt ----
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ---- 日志 ----
    implementation(libs.timber)

    // ---- 后台周期任务（位置兜底上报 WorkManager）----
    implementation(libs.androidx.work.runtime)

    // ---- 上报配置持久化（ReportPrefs 直用 DataStore；此前只在 core-storage 内用）----
    implementation(libs.datastore.preferences)

    // ---- 网络（NetworkModule 装配 Retrofit/Gson） ----
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.gson)

    // ---- 快递扫码（CameraX 采集 + ML Kit bundled 识别）----
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode.scanning)

    // ---- SSH 服务器（v0.2.34）：Apache MINA SSHD 2.20.x 纯 Java 内嵌 ----
    // 传输层用 sshd-mina（默认 NIO2 的 AsynchronousSocketChannel 是 API 26+，minSdk 24 不可用）；
    // BouncyCastle 供 ed25519/部分 KEX 使用（Android 9+ 系统 BC provider 被弃用，服务启动时重装）。
    implementation(libs.sshd.core)
    implementation(libs.sshd.sftp)
    implementation(libs.sshd.scp)
    implementation(libs.sshd.mina)
    implementation(libs.bouncycastle)
    implementation(libs.slf4j.android)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
