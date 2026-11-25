plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kartiks.animiru.miruroll"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.kartiks.animiru.miruroll"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "18.0.1"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly("com.github.kartik-commits:extensions-lib:18")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    compileOnly("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    compileOnly("androidx.preference:preference:1.2.1")
    compileOnly("com.github.mihonapp:injekt:91edab2317")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.github.kartik-commits:extensions-lib:18")
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    testImplementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.14")
    testImplementation("androidx.preference:preference:1.2.1")
}
