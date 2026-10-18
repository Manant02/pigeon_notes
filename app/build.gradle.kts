plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Number of commits on HEAD: increases with every commit and is the same on every machine (CI
// needs a full clone), so a local build never counts as a downgrade of a CI build or vice versa.
val commitCount: Int = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1

android {
    namespace = "ch.antoinette.pigeonnotes"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "ch.antoinette.pigeonnotes"
        minSdk = 34
        targetSdk = 37
        versionCode = commitCount
        versionName = "0.1.$commitCount"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Same key as local debug builds (CI restores ~/.android/debug.keystore from a secret),
            // so release and debug APKs install over each other without losing app data.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.testLogging { showStandardStreams = true } }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.browser)

    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.jb.markdown)
    implementation(libs.jlatexmath)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
