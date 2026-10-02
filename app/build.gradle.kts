import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreProperties = Properties().apply {
    val keystorePropertiesFile = rootProject.file("keystore.properties")

    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use {
            load(it)
        }
    }
}

android {
    namespace = "com.hightempalarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hightempalarm"

        minSdk = 29
        targetSdk = 35

        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(
                keystoreProperties.getProperty("storeFile")
            )

            storePassword =
                keystoreProperties.getProperty("storePassword")

            keyAlias =
                keystoreProperties.getProperty("keyAlias")

            keyPassword =
                keystoreProperties.getProperty("keyPassword")
        }
    }

    buildTypes {

        release {
            isMinifyEnabled = true
            isShrinkResources = true

            signingConfig =
                signingConfigs.getByName("release")

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
}
