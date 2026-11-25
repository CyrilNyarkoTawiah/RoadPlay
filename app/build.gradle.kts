plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing is read from environment variables so that no secret ever
// enters source control. If they are absent, the release APK is left unsigned.
val keystorePath: String? = System.getenv("ROADPLAY_KEYSTORE_PATH")

android {
    // To rename the package: change `namespace` and `applicationId`, then move
    // the folder app/src/*/java/com/roadplay/player and update the `package`
    // lines. See README.md "Changing the app name or package".
    namespace = "com.roadplay.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.roadplay.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("ROADPLAY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ROADPLAY_KEY_ALIAS")
                keyPassword = System.getenv("ROADPLAY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false // enable + test thoroughly before shipping
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    // The (old, partner-only) Android Auto SDK that lets a media app show its own
    // Activity — and video — on the head unit while the car is parked.
    implementation(files("libs/aauto.aar"))
    // Runtime deps of the vendored legacy AA SDK: files() has no POM, so transitive
    // androidx libs are never pulled and the host's CarActivity base class dies with
    // NoClassDefFoundError (androidx/fragment/app/FragmentManager) on launch.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.media:media:1.7.1")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.kotlinx.coroutines.guava)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.car.app) // CarConnection only (connection detection)
    implementation(libs.coil.compose)
    implementation(libs.coil.video)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
