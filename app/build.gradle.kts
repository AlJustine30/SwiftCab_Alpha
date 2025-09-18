import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services) // Added this line
}

android {
    namespace = "com.btsi.swiftcabalpha"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.btsi.swiftcabalpha"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.navigation:navigation-compose:2.9.3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation(platform("com.google.firebase:firebase-bom:32.0.0")) // Kept one BOM
    implementation("com.google.firebase:firebase-auth") // Correct
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("com.google.firebase:firebase-database") // Changed this line
    implementation("com.github.ybq:android-spinkit:1.4.0")
    implementation(libs.glide) // Kept this, assuming it's from version catalog
    implementation("com.google.android.gms:play-services-maps:18.2.0") // Added Google Maps SDK
    implementation("com.google.android.gms:play-services-location:21.3.0") // Added Location SDK
    implementation("com.google.android.libraries.places:places:3.5.0") // ADDED THIS LINE
    implementation("com.facebook.shimmer:shimmer:0.5.0") // Added Shimmer dependency
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    annotationProcessor(libs.compiler)
}