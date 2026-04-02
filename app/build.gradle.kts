
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.firebase.appdistribution)
}

import java.util.Properties

// Read API key from local.properties
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}
android {
    namespace = "com.nickpulido.rcrm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nickpulido.rcrm"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.7"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Make the API key available in BuildConfig
        buildConfigField("String", "GEMINI_API_KEY", "\"${localProperties.getProperty("GEMINI_API_KEY")?.trim('"') ?: ""}\"")
    }

    buildTypes {
        getByName("debug") {
            firebaseAppDistribution {
                artifactType = "APK"
                groups = "qa-testers"
                releaseNotes = "Bug fixes and improvements."
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
    
    buildFeatures {
        buildConfig = true // Enable BuildConfig generation
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Firebase
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Google Gemini AI
    implementation("com.google.ai.client.generativeai:generativeai:0.9.0")

    // ML Kit Text Recognition
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
