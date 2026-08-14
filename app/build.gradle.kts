plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "link.kmaba.apdf"
    compileSdk = 34

    defaultConfig {
        applicationId = "link.kmaba.apdf"
        minSdk = 21
        targetSdk = 34
        versionCode = 8
        versionName = "1.3.0"
    }

    signingConfigs {
        if (file("../keystore/release.jks").exists()) {
            create("release") {
                storeFile = file("../keystore/release.jks")
                storePassword = "converter123"
                keyAlias = "converter"
                keyPassword = "converter123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE", "META-INF/LICENSE.txt", "META-INF/NOTICE", "META-INF/NOTICE.txt")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("test.font.path", project.file("src/main/res/font/space_grotesk.ttf").absolutePath)
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}