plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
    id("com.google.gms.google-services") // Google Services plugin
}

android {
    namespace = "com.vaishnava.alarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vaishnava.alarm"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Optional release signing from Gradle properties (RELEASE_*). If not provided, release remains unsigned.
    val ksPath = project.findProperty("RELEASE_STORE_FILE") as String?
    val ksPass = project.findProperty("RELEASE_STORE_PASSWORD") as String?
    val keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
    val keyPass = project.findProperty("RELEASE_KEY_PASSWORD") as String?

    signingConfigs {
        if (!ksPath.isNullOrBlank() && !ksPass.isNullOrBlank() && !keyAlias.isNullOrBlank() && !keyPass.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksPass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            // Temporarily disable code/resource shrinking to avoid R8 removing
            // lifecycle/Compose classes that are required at runtime.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
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
        buildConfig = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE.txt"
        }
    }

    // Use App Bundle splits so devices download only what's needed
    bundle {
        language {
            enableSplit = true
        }
        density {
            enableSplit = true
        }
        abi {
            enableSplit = true
        }
    }

    applicationVariants.all {
        if (buildType.name == "debug") {
            outputs.all {
                val outputImpl = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
                outputImpl?.outputFileName = "Chrona.apk"
            }
        }
    }
}

dependencies {
    // Core + Material
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Compose UI + Material3 (BOM supplies versions)
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-graphics:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")

    // Material icons (extended) — ADDED so Icons.Filled.Delete works
    implementation("androidx.compose.material:material-icons-extended:1.6.8")

    // Compose utilities
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.foundation:foundation:1.6.8")
    implementation("androidx.compose.runtime:runtime:1.6.8")

    // Lifecycle + JSON
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("com.google.code.gson:gson:2.10.1")

    // Google Mobile Ads (AdMob) for InterstitialAd, AdRequest, etc.
    implementation("com.google.android.gms:play-services-ads:23.4.0")
    
    // Google Drive REST client (for appData sync)
    implementation("com.google.api-client:google-api-client-android:2.6.0")
    implementation("com.google.http-client:google-http-client-gson:1.43.3")
    implementation("com.google.apis:google-api-services-drive:v3-rev20230815-2.0.0")


    // Debug & Test
    debugImplementation("androidx.compose.ui:ui-tooling:1.6.8")
    debugImplementation("androidx.compose.ui:ui-test-manifest:1.6.8")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4:1.6.8")
    
    // Google Sign-In
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    
    // Note: Firebase removed; using plain Google Sign-In without Firebase

    // Coil for loading network images in Compose
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Local AutoSizeText composable implemented in project (no external dependency required)
}