plugins {
    id("com.android.library") version "9.4.1"
    `maven-publish`
}

val coreSources = providers.gradleProperty("attrikit.coreSources").get()

// The version the SDK reports in X-AttriKit-SDK is the one literal in the core's Models.kt; the
// publication reads it from there so a release tag and the reported version cannot drift apart.
val sdkVersion: String = Regex("""const val ATTRIKIT_ANDROID_SDK_VERSION = "([^"]+)"""")
    .find(file("$coreSources/dev/attrkit/core/Models.kt").readText())
    ?.groupValues?.get(1)
    ?: error("ATTRIKIT_ANDROID_SDK_VERSION not found in $coreSources/dev/attrkit/core/Models.kt")

group = providers.gradleProperty("attrikit.group").get()
version = sdkVersion

android {
    namespace = "dev.attrkit.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // The core is written against java.time and java.util.Base64, which Android has only from
        // API 26. Desugaring keeps the declared minSdk 21 true; the host app must enable it too.
        isCoreLibraryDesugaringEnabled = true
    }

    sourceSets {
        // The core's sources are compiled INTO this artifact rather than copied: the pure-JVM
        // package stays the one place the attribution logic lives and its offline gate keeps
        // guarding it.
        named("main") {
            kotlin.directories.add(coreSources)
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// ads-identifier 18.3.0 and lifecycle-process 2.10.0 declare minSdk 23 (read from their AAR
// manifests), so a host at the minSdk 21 this library declares would fail its manifest merge. The
// two pins below are the versions checked to resolve to minSdk 21 or lower throughout; Lint's
// "newer version" warnings for them are expected. Re-check every dependency's minSdk before
// raising either.
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation("com.android.installreferrer:installreferrer:2.2")
    implementation("com.google.android.gms:play-services-ads-identifier:18.2.0")
    implementation("com.google.android.gms:play-services-appset:16.1.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")

    testImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = project.group.toString()
                artifactId = "attrikit-android"
                version = sdkVersion
                from(components["release"])
                pom {
                    name.set("AttriKit Android SDK")
                    description.set("Mobile attribution for Android: Google Play install referrer matching, consent-aware events and Google EU consent.")
                    url.set("https://attrikit.io")
                    // No <licenses>: the public iOS package carries no license file either, and
                    // this mirrors it rather than choosing one for the owner.
                }
            }
        }
    }
}
