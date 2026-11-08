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

// The commit the APK is built from, and its time: the update check uses them to recognise the
// installed build among the GitHub releases (each release records its commit) and to tell which
// test builds came after it.
val commitId: String = providers.exec {
    commandLine("git", "rev-parse", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim()
val commitTime: Long = providers.exec {
    commandLine("git", "log", "-1", "--format=%ct")
    isIgnoreExitValue = true
}.standardOutput.asText.get().trim().toLongOrNull() ?: 0L

android {
    namespace = "ch.antoinette.pigeonnotes"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "ch.antoinette.pigeonnotes"
        minSdk = 34
        targetSdk = 37
        // CI test builds of a branch pass the version of the main commit they are based on (build.yml).
        versionCode = providers.gradleProperty("pigeon.versionCode").orNull?.toInt() ?: commitCount
        versionName = providers.gradleProperty("pigeon.versionName").orNull ?: "0.1.$commitCount"

        buildConfigField("String", "GIT_COMMIT", "\"$commitId\"")
        buildConfigField("long", "GIT_COMMIT_TIME", "${commitTime}L")
        // Where the app looks for updates: the releases CI publishes (see .github/workflows/build.yml).
        buildConfigField("String", "UPDATE_REPO", "\"Manant02/pigeon_notes\"")
    }

    signingConfigs {
        getByName("debug") {
            // CI passes the keystore it restored from the DEBUG_KEYSTORE_BASE64 secret. Relying on the
            // default location (~/.android/debug.keystore) is not enough there: on the GitHub runners
            // the Android plugin looked elsewhere and silently signed every build with a new key.
            providers.gradleProperty("pigeon.keystore").orNull?.let { storeFile = file(it) }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Same key as local debug builds (CI passes the one from the secret), so release and
            // debug APKs install over each other without losing app data.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    // Android's org.json is only a stub in JVM unit tests; the update check parses GitHub's JSON with it.
    testImplementation(libs.json)
}
