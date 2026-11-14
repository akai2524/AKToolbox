plugins { id("com.android.application") }

android {
    namespace = "dev.ak.toolbox"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "dev.ak.toolbox"
        minSdk = 35
        targetSdk = 37
        versionCode = 37
        versionName = "0.1.36"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release { isMinifyEnabled = false }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.google.zxing:core:3.5.3")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

