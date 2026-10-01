plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
android {
    namespace = "com.pixelpad.app"
    compileSdk = 35
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    defaultConfig {
        applicationId = "com.pixelpad.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 5
        versionName = "1.1.3"
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")   // sideload build: signed with the standard debug key so it installs directly
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }  // the camera view only; we draw the retro UI ourselves
    implementation("com.google.zxing:core:3.5.3")
}
