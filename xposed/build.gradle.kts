import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 必须与主 App 用同一发布密钥签名：模块持有的 SEND_CAPTURED_URL 是 signature 权限，
// 不同签名就转发不了广播，回传链路整条不通。keystore.properties 缺失时回退 debug
// 签名（纯源码构建可用，但回传只在主 App 也用 debug 签名时才能通）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.dlguard.xposed"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dlguard.xposed"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.2.0"
    }

    signingConfigs {
        // 发布签名必须与主 App 同一个 keystore：签名不一致时，广播的
        // signature 权限校验在系统层直接失败（这正是 3.2.0 链路不通的根因之一）
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystorePropsFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/**"
        }
    }
}

dependencies {
    // 仅编译期可见，运行期由 LSPosed 框架注入；不会打进 APK
    compileOnly("de.robv.android.xposed:api:82")
}
