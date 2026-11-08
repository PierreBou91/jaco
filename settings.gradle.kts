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
        // sherpa-onnx is not on Maven Central: tools/fetch_deps.sh puts its release AAR here.
        maven { url = uri("third_party/maven") }
    }
}

rootProject.name = "stt_app"
include(":app", ":engine")
