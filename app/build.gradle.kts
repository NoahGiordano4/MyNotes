plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.lumennotes"
    compileSdk {
        version = release(37)
    }

    // NDK utilisé pour compiler libhunspell.so (voir externalNativeBuild ci-dessous)
    ndkVersion = "29.0.14033849"

    defaultConfig {
        applicationId = "com.example.lumennotes"
        minSdk = 35
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABIs pour lesquels libhunspell.so est compilé.
        // minSdk = 35 ⇒ plus de support 32 bits, seuls arm64-v8a et x86_64 comptent.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Compile les sources Hunspell embarquées (cpp/hunspell) en libhunspell.so
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
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

    // JNA : liaison avec le correcteur natif Hunspell (libhunspell.so).
    // 1) Le suffixe @aar est OBLIGATOIRE sur Android : c'est la variante qui
    //    embarque libjnidispatch.so (les natives Android ne sont pas dans le
    //    .jar). Sans lui, Native.load("hunspell") échoue silencieusement et
    //    la correction retombe sur le correcteur système (dictionnaire vide).
    // 2) ≥ 5.17.0 : les correctifs 16 Ko d'Android 15/16 pour jnidispatch
    //    (issues #1618 et #1647) ne sont complets qu'à partir de 5.17.0.
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}