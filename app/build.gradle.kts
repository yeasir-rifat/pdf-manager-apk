plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.pdfmanager.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pdfmanager.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
    }
    // One permanent key signs every release, so a new APK installs over the old one and keeps the user's data.
    // The key comes from environment variables (GitHub Actions) or gradle properties (-PkeystoreFile=...).
    // Without a keystore (a quick local build) the release APK falls back to the debug key and a warning is printed.
    fun secret(env: String, prop: String): String? =
        (System.getenv(env) ?: project.findProperty(prop)?.toString())?.takeIf { it.isNotBlank() }

    val keystorePath = secret("KEYSTORE_FILE", "keystoreFile")
    val keystorePassword = secret("KEYSTORE_PASSWORD", "keystorePassword")
    val keyAliasName = secret("KEY_ALIAS", "keyAlias")
    val keyPasswordValue = secret("KEY_PASSWORD", "keyPassword") ?: keystorePassword
    val hasReleaseKey = keystorePath != null && file(keystorePath).isFile &&
        keystorePassword != null && keyAliasName != null

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            // R8 shrinks the code and resources (smaller and faster). If a feature ever breaks in the
            // release APK, build with  -PnoR8  to switch it off without touching any code.
            val r8 = !project.hasProperty("noR8")
            isMinifyEnabled = r8
            isShrinkResources = r8
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("WARNING: no release keystore given, signing with the debug key. This APK cannot update an APK signed with another key.")
                signingConfigs.getByName("debug")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.core:core-ktx:1.13.1")

    val room = "2.6.1"
    implementation("androidx.room:room-runtime:$room")
    implementation("androidx.room:room-ktx:$room")
    ksp("androidx.room:room-compiler:$room")

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // Office to PDF: AuthorizationClient (Google sign-in consent for the drive.file scope). Check for the newest stable version before building.
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    testImplementation("junit:junit:4.13.2")
}
