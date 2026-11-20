plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatedWebAssets = layout.buildDirectory.dir("generated/zhan-assets")
val parentWebAssets = rootProject.projectDir.parentFile
val bundledWebAssets = rootProject.projectDir.resolve("web")
val hasParentWebAssets = parentWebAssets.resolve("index.html").isFile &&
    parentWebAssets.resolve("css/style.css").isFile &&
    parentWebAssets.resolve("js/main.js").isFile
val webAssetsSource = if (hasParentWebAssets) parentWebAssets else bundledWebAssets
val syncWebAssets by tasks.registering(Sync::class) {
    from(webAssetsSource) {
        include("index.html")
        include("manifest.webmanifest")
        include("sw.js")
        include("zhan-icon.svg")
        include("css/**")
        include("js/**")
    }
    into(generatedWebAssets)
}

android {
    namespace = "com.zhan.personal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zhan.personal"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main").assets.srcDir(generatedWebAssets)
}

kotlin {
    jvmToolchain(17)
}

tasks.named("preBuild").configure {
    dependsOn(syncWebAssets)
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
