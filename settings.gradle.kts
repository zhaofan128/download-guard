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
        // 仅 LSPosed 可选分支需要；content filter 保证不影响主工程依赖解析
        maven {
            url = uri("https://api.xposed.info/")
            content { includeGroup("de.robv.android.xposed") }
        }
    }
}

rootProject.name = "DownloadGuard"
include(":app")

// 可选增强分支：LSPosed 模块默认不参与构建。
// 需要时执行： ./gradlew -PenableXposed :xposed:assembleRelease
if (providers.gradleProperty("enableXposed").isPresent) {
    include(":xposed")
}
