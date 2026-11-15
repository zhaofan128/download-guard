plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dlguard.xposed"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dlguard.xposed"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.1"
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
