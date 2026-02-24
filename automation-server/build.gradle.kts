plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.automationserver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.automationserver"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.1"

        testInstrumentationRunner = "com.example.automationserver.AutomationInstrumentationRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    buildFeatures {
        viewBinding = true
    }

    dependenciesInfo {
        includeInApk = true
        includeInBundle = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // ConstraintLayout
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // UIAutomator for automation
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")

    // JSON-RPC server dependencies
    implementation("com.google.code.gson:gson:2.10.1")

    // Ktor server for HTTP/JSON-RPC
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-gson:2.3.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk-android:1.13.10")
    testImplementation("org.robolectric:robolectric:4.12.1")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")

    // Instrumentation dependencies (for running JSON-RPC server in androidTest)
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("io.ktor:ktor-server-core:2.3.7")
    androidTestImplementation("io.ktor:ktor-server-netty:2.3.7")
    androidTestImplementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    androidTestImplementation("io.ktor:ktor-serialization-gson:2.3.7")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    androidTestImplementation("com.google.code.gson:gson:2.10.1")
}
