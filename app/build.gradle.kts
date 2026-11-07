import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing: ~/.local/android/release-keys/commander.properties (or the
// file named by COMMANDER_ANDROID_SIGNING_PROPERTIES), or the same keys as
// environment variables. Without them, release builds use the debug key.
val releaseSigningProperties = Properties()
val releaseSigningPropertiesFile = file(
    System.getenv("COMMANDER_ANDROID_SIGNING_PROPERTIES")
        ?: "${System.getProperty("user.home")}/.local/android/release-keys/commander.properties"
)
if (releaseSigningPropertiesFile.isFile) {
    releaseSigningPropertiesFile.inputStream().use(releaseSigningProperties::load)
}

fun signingValue(name: String): String? = System.getenv(name) ?: releaseSigningProperties.getProperty(name)

android {
    namespace = "io.github.carnager.commander"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "36.1.0"

    defaultConfig {
        applicationId = "io.github.carnager.commander"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.3.2"
    }

    signingConfigs {
        create("release") {
            signingValue("COMMANDER_ANDROID_STORE_FILE")?.let { storeFile = file(it) }
            storePassword = signingValue("COMMANDER_ANDROID_STORE_PASSWORD")
            keyAlias = signingValue("COMMANDER_ANDROID_KEY_ALIAS")
            keyPassword = signingValue("COMMANDER_ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingValue("COMMANDER_ANDROID_STORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // BouncyCastle ships multi-release and OSGi metadata that collides and is useless on Android.
            excludes += setOf("META-INF/versions/**", "META-INF/*.md", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core:1.18.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-video:3.6.3")
    // Pinch zoom with tiled loading, so 50-megapixel photos stay sharp without eating memory.
    implementation("me.saket.telephoto:zoomable-image-coil3:0.19.0")

    implementation("com.hierynomus:sshj:0.41.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    // LZMA for 7z and .xz, optional in commons-compress.
    implementation("org.tukaani:xz:1.12")
    // Password-protected ZIP (AES and ZipCrypto), reading and writing.
    implementation("net.lingala.zip4j:zip4j:2.11.6")
    // RAR up to version 4; RAR5 has no free Java implementation.
    implementation("com.github.junrar:junrar:8.1.2")
    // sshj and smbj pull different BouncyCastle versions; all modules must match or classes collide.
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
    implementation("org.bouncycastle:bcutil-jdk18on:1.86")
    implementation("com.hierynomus:smbj:0.15.0")
    // Share enumeration (NetShareEnum) over DCE/RPC, which smbj itself does not do.
    implementation("com.rapid7.client:dcerpc:0.12.13")

    testImplementation("junit:junit:4.13.2")
}
