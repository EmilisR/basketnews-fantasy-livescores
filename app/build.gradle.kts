plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------- version ----------
// versionName: -PversionName=..., else the git tag (v1.2.3 -> 1.2.3) when CI builds a tag, else 0.1.0-dev.
// versionCode: -PversionCode=..., else derived from the tag (1.2.3 -> 10203), else 1.
val tagName: String? = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" && it.startsWith("v") }
    ?.removePrefix("v")
val appVersionName: String = (findProperty("versionName") as String?) ?: tagName ?: "0.1.0-dev"
val appVersionCode: Int = (findProperty("versionCode") as String?)?.toIntOrNull()
    ?: tagName?.let { name ->
        val parts = name.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } + listOf(0, 0, 0)
        parts[0] * 10000 + parts[1] * 100 + parts[2]
    }?.takeIf { it > 0 }
    ?: 1

// ---------- signing ----------
// Release signing comes only from the environment (set by CI from GitHub secrets).
// Without KEYSTORE_PATH the release build is simply unsigned.
val keystorePath: String? = System.getenv("KEYSTORE_PATH")?.takeIf { it.isNotBlank() && file(it).exists() }

android {
    namespace = "com.elflive.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.elflive.app"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // Play's target-API deadline is a store policy, not a build problem; bump targetSdk before publishing.
        disable += "ExpiredTargetSdkVersion"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}
