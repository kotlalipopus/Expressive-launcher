plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.expressivebreathe"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.example.expressivebreathe"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}
