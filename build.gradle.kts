plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

// Fail early with a useful message instead of an unresolved-dependency error.
val sherpaAar = file("third_party/maven/com/k2fsa/sherpa/sherpa-onnx/2.0.1/sherpa-onnx-2.0.1.aar")
if (!sherpaAar.exists()) {
    throw GradleException("sherpa-onnx AAR missing: run tools/fetch_deps.sh once (see README.md)")
}
