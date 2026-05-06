import com.github.triplet.gradle.androidpublisher.ReleaseStatus

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.kapt")
    id("com.github.triplet.play")
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

    signingConfigs {
        create("release") {
            storeFile = file(requiredEnvOrDotenv("ANDROID_KEYSTORE_PATH"))
            storePassword = requiredEnvOrDotenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = requiredEnvOrDotenv("ANDROID_KEY_ALIAS")
            keyPassword = requiredEnvOrDotenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

play {
    serviceAccountCredentials.set(file(requiredEnvOrDotenv("PLAY_SERVICE_ACCOUNT_JSON_PATH")))
    track.set(envOrDotenv("PLAY_TRACK") ?: "internal")
    releaseStatus.set(ReleaseStatus.valueOf(envOrDotenv("PLAY_RELEASE_STATUS") ?: "DRAFT"))
    defaultToAppBundles.set(true)
}

tasks.register("publishAab") {
    group = "publishing"
    description = "Builds the signed release AAB and uploads it to the configured Google Play track."
    dependsOn("publishReleaseBundle")
}

kapt {
    arguments {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
}

dependencies {
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
    kapt("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
