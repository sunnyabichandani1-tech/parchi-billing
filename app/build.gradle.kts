plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.parchi.billing"
    compileSdk = 35

    val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
    defaultConfig {
        applicationId = "com.parchi.billing"
        minSdk = 26
        targetSdk = 35
        versionCode = run
        versionName = "1.0.$run"
    }

    // One fixed key, so every new test build installs over the old one and keeps the data.
    signingConfigs {
        create("parchi") {
            storeFile = file("parchi-debug.jks")
            storePassword = "android"
            keyAlias = "parchi"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("parchi")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("parchi")
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
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val room = "2.6.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
