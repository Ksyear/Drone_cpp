plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "kr.ac.rb022"
    compileSdk = 35
    defaultConfig {
        applicationId = "kr.ac.rb022.diagnostic"
        minSdk = 31
        targetSdk = 35
        versionCode = 8
        versionName = "0.8-control"
        testInstrumentationRunner = "kr.ac.rb022.CameraSmokeInstrumentation"
    }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.15" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
}
