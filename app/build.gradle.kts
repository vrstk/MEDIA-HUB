plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}
android {
    namespace = "com.mediahub.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.mediahub.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 26
        versionName = "0.8.1"
        resValue("string", "app_name", "Media Hub")
    }
    buildTypes {
        debug {
            when {
                providers.gradleProperty("mediaHubCatalogPreview").orNull == "true" -> {
                    applicationIdSuffix = ".preview.catalog"
                    resValue("string", "app_name", "Media Hub Каталог")
                }
                providers.gradleProperty("mediaHubSideBySide").orNull == "true" -> {
                    applicationIdSuffix = ".preview"
                    resValue("string", "app_name", "Media Hub Preview")
                }
            }
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    sourceSets.getByName("test").resources.srcDir("schemas")
}
tasks.withType<Test>().configureEach {
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    providers.gradleProperty("mediaHubSeedFile").orNull?.let { path ->
        systemProperty("mediaHubSeedFile", path)
        inputs.file(path).withPathSensitivity(PathSensitivity.NONE)
    }
}
ksp {
    arg("room.schemaLocation", layout.projectDirectory.dir("schemas").asFile.path)
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250107")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
