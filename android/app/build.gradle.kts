import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------
// Where the Pi lives.
//
// One source of truth for three consumers: BuildConfig (BriqApi's host list),
// the NFC intent filters in the manifest, and the cleartext allowlist in
// network_security_config.xml. Falls back to the committed .example so a
// fresh clone builds before it is configured.
// ---------------------------------------------------------------------------
val briqProps = Properties().apply {
    val configured = rootProject.file("briq.properties")
    val source = if (configured.exists()) configured
                 else rootProject.file("briq.properties.example")
    source.inputStream().use { load(it) }
}

val briqLanHost: String = briqProps.getProperty("briq.lanHost").orEmpty().trim()
val briqTailscaleHost: String = briqProps.getProperty("briq.tailscaleHost").orEmpty().trim()
val briqPort: String = briqProps.getProperty("briq.port", "8088").trim()

require(briqLanHost.isNotEmpty()) {
    "briq.lanHost must be set in android/briq.properties"
}

// network_security_config.xml cannot take a manifest placeholder, so it is
// generated instead of edited.
abstract class GenerateNetworkSecurityConfig : DefaultTask() {
    @get:Input abstract val lanHost: Property<String>
    @get:Input abstract val tailscaleHost: Property<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val domains = listOf(lanHost.get(), tailscaleHost.get())
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n") {
                "        <domain includeSubdomains=\"false\">$it</domain>"
            }
        // The domain block is substituted AFTER trimIndent, not interpolated
        // into the literal. trimIndent runs on the already-interpolated string,
        // so a multi-line value lowers the common indent and shifts the whole
        // file - which pushes the XML declaration off column 0 and makes the
        // file unparseable. That only shows up with two or more hosts.
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <!--
              GENERATED from android/briq.properties - do not edit by hand.

              The controller is plain HTTP on the LAN. Cleartext is permitted
              for exactly the addresses it can live at and nowhere else, so a
              hostile network cannot talk the app into an unencrypted request
              somewhere useful.
            -->
            <network-security-config>
                <domain-config cleartextTrafficPermitted="true">
            @DOMAINS@
                </domain-config>
                <base-config cleartextTrafficPermitted="false" />
            </network-security-config>
        """.trimIndent().replace("@DOMAINS@", domains) + "\n"

        val dir = outputDir.get().asFile.resolve("xml")
        dir.mkdirs()
        dir.resolve("network_security_config.xml").writeText(xml)
    }
}

val generateNetworkSecurityConfig =
    tasks.register<GenerateNetworkSecurityConfig>("generateNetworkSecurityConfig") {
        lanHost.set(briqLanHost)
        tailscaleHost.set(briqTailscaleHost)
    }

androidComponents {
    onVariants { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateNetworkSecurityConfig,
            GenerateNetworkSecurityConfig::outputDir,
        )
    }
}

android {
    namespace = "de.lukas.briq"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.lukas.briq"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "LAN_HOST", "\"$briqLanHost\"")
        buildConfigField("String", "TAILSCALE_HOST", "\"$briqTailscaleHost\"")
        buildConfigField("String", "BRIQ_PORT", "\"$briqPort\"")

        // The http:// NFC intent filters must match the tag byte for byte.
        manifestPlaceholders["briqLanHost"] = briqLanHost
        manifestPlaceholders["briqPort"] = briqPort
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded to one device; the debug key is the only key there is.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
}
