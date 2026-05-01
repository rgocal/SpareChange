plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.gocalsd.sparechange"
    compileSdk = 35

    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                from(components["release"])
                
                groupId = "com.github.rgocal"
                artifactId = "SpareChange"
                version = "1.0.2"
            }
        }
    }
}

dependencies {
    api(libs.billing)
}