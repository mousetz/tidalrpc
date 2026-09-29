plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val tidalClientId = providers.gradleProperty("tidalClientId").orElse("orgBtxBKnY6lSmjP").get()
require(tidalClientId.matches(Regex("[A-Za-z0-9_-]*"))) {
    "tidalClientId must contain only letters, digits, underscores, or hyphens"
}

android {
    namespace = "com.mousetz.tidalrpc"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.mousetz.tidalrpc"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TIDAL_CLIENT_ID", "\"$tidalClientId\"")
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation(files("libs/discord_partner_sdk.aar"))
    implementation("com.tidal.sdk:tidalapi:0.3.57")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
