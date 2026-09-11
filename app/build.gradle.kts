import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing is read from an untracked keystore.properties (see
// keystore.properties.example). When it is absent — a fresh clone, or CI — the
// release build is left unsigned on purpose: falling back to the debug key would
// produce an artifact that looks shippable and that Play rejects on upload.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.containsKey("storeFile")

android {
    namespace = "com.childpaint"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.johanneshoh.childpaint"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        // The parent panel prints the version, so a build that never finished
        // installing is visible instead of looking like a missing feature.
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Pinned to the newest versions that build against AGP 8.13.2 / compileSdk 36.
// The current androidx releases require AGP 9.1 and compileSdk 37; that upgrade
// is a separate piece of work (AGP 9 changes the DSL) and is not needed to
// satisfy Play's target-API rule, which targetSdk 36 already meets.
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // PickVisualMedia: the system photo picker, which needs no gallery permission
    // and shows the child's whole library to nobody but the person holding it.
    implementation("androidx.activity:activity:1.8.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    testImplementation("junit:junit:4.13.2")
}
