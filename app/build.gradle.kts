plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "io.github.slightneko.notificationguard"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.slightneko.notificationguard"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        create("ci") {
            storeFile = file(System.getenv("SIGNING_STORE") ?: "../../signing/ci.jks")
            storePassword = System.getenv("SIGNING_PASSWORD")
            keyAlias = "notificationguard"
            keyPassword = System.getenv("SIGNING_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("ci")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("top.yukonga.miuix.kmp:miuix-android:0.8.8")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
