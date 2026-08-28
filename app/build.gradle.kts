plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.lumennotes"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.lumennotes"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }

    packaging {
        jniLibs {
            // Indispensable pour le support 16 KB : les libs natives 
            // doivent être stockées non compressées et alignées.
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)

    // ML Kit Digital Ink Recognition (pour la transcription)
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}