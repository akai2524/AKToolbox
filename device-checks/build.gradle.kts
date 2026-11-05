plugins { id("com.android.application") }

android {
    namespace = "dev.akai.toolbox.probe"
    compileSdk { version = release(37) { minorApiLevel = 0 } }
    defaultConfig {
        applicationId = "dev.akai.toolbox.probe"
        minSdk = 37
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
