plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.pbou.stt.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // sherpa-onnx runs every open model; its API types (configs) stay internal to this module.
    implementation(libs.sherpa.onnx)
    implementation(libs.coroutines.android)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
