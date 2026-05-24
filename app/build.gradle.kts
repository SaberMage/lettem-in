plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lettemin"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lettemin"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "0.2.1"
    }

    signingConfigs {
        create("release") {
            storeFile = file("${rootDir}/keystore/lettemin-release.jks")
            storePassword = "lettemin"
            keyAlias = "lettemin"
            keyPassword = "lettemin"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.7.3")
}
