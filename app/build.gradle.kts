import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.youngmin.signagekiosk"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.youngmin.signagekiosk"
        minSdk = 26 // WebViewClient.onRenderProcessGone (API 26)
        targetSdk = 34
        // CI passes -PkioskVersionCode/-PkioskVersionName from the git tag (scripts/version-from-tag.sh).
        versionCode = (findProperty("kioskVersionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("kioskVersionName") as String?) ?: "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // Release key from <repo>/keystore.properties (never in git): storeFile, storePassword, keyAlias, keyPassword.
    // Every TV update must be signed with this SAME key. Without the file the release APK is left unsigned.
    val keystoreFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) } }
    if (keystoreFile.exists()) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
