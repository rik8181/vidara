plugins {
    id("com.android.application")
}

android {
    lint { disable += "MissingTranslation"; abortOnError = false; checkReleaseBuilds = false }
    namespace = "com.rik.vidaraplus"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rik.vidaraglass"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.media3:media3-exoplayer:1.7.1")
    implementation("androidx.media3:media3-ui:1.7.1")
    implementation("androidx.media3:media3-session:1.7.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
