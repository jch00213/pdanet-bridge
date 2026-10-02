// Top-level build file where plugins are declared but not applied directly to the root project.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Clean task to delete the build directory across all modules
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
