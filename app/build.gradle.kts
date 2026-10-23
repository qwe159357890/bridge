import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "my.bridge.com"
    compileSdk = 36

    defaultConfig {
        applicationId = "my.bridge.com"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 签名配置：优先读根目录 key.properties（本地），其次读 CI 注入的临时 keystore 文件。
    // 参照 aurelay 的做法，签名信息不硬编码进仓库。
    signingConfigs {
        create("release") {
            val props = Properties()
            val propsFile = rootProject.file("key.properties")
            if (propsFile.exists()) {
                props.load(FileInputStream(propsFile))
                storeFile = props["storeFile"]?.let { rootProject.file(it.toString()) }
                storePassword = props["storePassword"]?.toString()
                keyAlias = props["keyAlias"]?.toString()
                keyPassword = props["keyPassword"]?.toString()
            } else {
                // CI 场景：由 workflow 从 Secrets 解码出 release.keystore 与相关参数
                val ciStoreFile = rootProject.file("release.keystore")
                val ciStorePassword = System.getenv("KEYSTORE_PASSWORD")
                val ciKeyAlias = System.getenv("KEY_ALIAS")
                val ciKeyPassword = System.getenv("KEY_PASSWORD")
                if (ciStoreFile.exists() && !ciStorePassword.isNullOrBlank()) {
                    storeFile = ciStoreFile
                    storePassword = ciStorePassword
                    keyAlias = ciKeyAlias
                    keyPassword = ciKeyPassword
                }
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
            // 有可用签名才设置；否则 release 无签名（仅用于本地验证构建链路）
            signingConfigs.getByName("release").let { sc ->
                if (sc.storeFile != null) signingConfig = sc
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    // ---- Hilt ----
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ---- 日志 ----
    implementation(libs.timber)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
