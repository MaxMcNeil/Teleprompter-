plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.tele.prompter"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.tele.prompter"
        minSdk = 29
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }
    // Clé fixe : chaque nouvel APK s'installe par-dessus l'ancien (sinon « paquet non valide »)
    signingConfigs {
        create("fixe") {
            storeFile = file("prompteur.jks")
            storePassword = "prompteur"
            keyAlias = "prompteur"
            keyPassword = "prompteur"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixe") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47")
}
