import com.github.triplet.gradle.androidpublisher.ReleaseStatus

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.github.triplet.play")
    alias(libs.plugins.kotlin.compose)
}

fun envOrDotenv(name: String): String? {
    val envValue = System.getenv(name)?.takeIf { it.isNotBlank() }
    if (envValue != null) return envValue

    val dotenv = rootProject.file(".env")
    if (!dotenv.isFile) return null

    return dotenv.readLines()
        .asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
        .map {
            val key = it.substringBefore("=").trim()
            val value = it.substringAfter("=").trim().trim('"', '\'')
            key to value
        }
        .firstOrNull { it.first == name }
        ?.second
        ?.takeIf { it.isNotBlank() }
}

fun requiredEnvOrDotenv(name: String): String =
    envOrDotenv(name) ?: error("Missing $name. Add it to .env or the process environment.")

fun missingEnvOrDotenv(names: List<String>): List<String> =
    names.filter { envOrDotenv(it).isNullOrBlank() }

val releaseSigningEnvNames = listOf(
    "ANDROID_KEYSTORE_PATH",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)
val playEnvNames = listOf("PLAY_SERVICE_ACCOUNT_JSON_PATH")

android {
    namespace = "com.lss.onmyplate.nativeplanner"
    compileSdk = 35

    defaultConfig {
        applicationId = envOrDotenv("ANDROID_APPLICATION_ID") ?: "com.lss.onmyplate.nativeplanner"
        minSdk = 26
        targetSdk = 35
        versionCode = envOrDotenv("ANDROID_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = envOrDotenv("ANDROID_VERSION_NAME") ?: "0.1.0"
        buildConfigField("String", "GEMINI_API_KEY", "\"${envOrDotenv("GEMINI_API_KEY").orEmpty()}\"")
        buildConfigField("String", "GEMINI_MODEL", "\"${envOrDotenv("GEMINI_MODEL") ?: "gemini-3-27b-it"}\"")
        buildConfigField(
            "String",
            "GEMINI_API_BASE_URL",
            "\"${envOrDotenv("GEMINI_API_BASE_URL") ?: "https://generativelanguage.googleapis.com/v1beta"}\"",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    signingConfigs {
        create("release") {
            if (missingEnvOrDotenv(releaseSigningEnvNames).isEmpty()) {
                storeFile = file(requiredEnvOrDotenv("ANDROID_KEYSTORE_PATH"))
                storePassword = requiredEnvOrDotenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = requiredEnvOrDotenv("ANDROID_KEY_ALIAS")
                keyPassword = requiredEnvOrDotenv("ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

play {
    envOrDotenv("PLAY_SERVICE_ACCOUNT_JSON_PATH")?.let {
        serviceAccountCredentials.set(file(it))
    }
    track.set(envOrDotenv("PLAY_TRACK") ?: "internal")
    releaseStatus.set(ReleaseStatus.valueOf(envOrDotenv("PLAY_RELEASE_STATUS") ?: "DRAFT"))
    defaultToAppBundles.set(true)
}

tasks.register("publishAab") {
    group = "publishing"
    description = "Builds the signed release AAB and uploads it to the configured Google Play track."
    dependsOn("publishReleaseBundle")
}

gradle.taskGraph.whenReady {
    val taskNames = allTasks.map { it.name }.toSet()
    val needsReleaseSigning = taskNames.any { it in setOf("assembleRelease", "bundleRelease", "publishReleaseBundle", "publishAab") }
    if (needsReleaseSigning) {
        val missing = missingEnvOrDotenv(releaseSigningEnvNames)
        check(missing.isEmpty()) {
            "Release signing requires ${missing.joinToString()}. Add them to .env or the process environment."
        }
    }

    val needsPlayCredentials = taskNames.any { it in setOf("publishReleaseBundle", "publishAab") }
    if (needsPlayCredentials) {
        val missing = missingEnvOrDotenv(playEnvNames)
        check(missing.isEmpty()) {
            "Google Play publishing requires ${missing.joinToString()}. Add it to .env or the process environment."
        }
    }
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
    implementation(libs.androidx.compose.ui.graphics)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

    implementation("com.google.android.play:app-update-ktx:2.1.0")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.robolectric:robolectric:4.13")
}
