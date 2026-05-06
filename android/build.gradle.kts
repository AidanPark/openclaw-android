// Top-level build file — no plugins applied here
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.ktlint) apply false
}

allprojects {
    configurations.configureEach {
        // Fix for AGP 8.7+ "deprecated for consumption" warnings
        // and "Configurations should not act as both a resolution root and a variant"
        // caused by internal AGP configurations like debugRuntimeClasspathCopy.
        if (name.contains("RuntimeClasspathCopy")) {
            isCanBeConsumed = false
        }
    }
}
