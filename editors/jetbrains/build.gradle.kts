import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.blackbox"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        // The Kotlin runtime bundled with 2024.3.
        apiVersion = KotlinVersion.KOTLIN_2_0
        languageVersion = KotlinVersion.KOTLIN_2_0
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity(providers.gradleProperty("platformVersion"))
        bundledPlugins("com.intellij.java", "org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

// The MCP server ships inside the plugin (see McpInstall), bundled by
// editors/vscode/scripts/bundle-mcp.js. Needs `npm ci` in editors/vscode.
val vscodeDir = layout.projectDirectory.dir("../vscode")
val bundleMcpServer by tasks.registering(Exec::class) {
    description = "Bundles the Blackbox MCP server from editors/vscode."
    workingDir = vscodeDir.asFile
    commandLine(if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node", "scripts/bundle-mcp.js")
    inputs.dir(vscodeDir.dir("src"))
    inputs.file(vscodeDir.file("package.json"))
    inputs.file(layout.projectDirectory.file("../../schema/tools.json"))
    outputs.dir(vscodeDir.dir("dist-mcp"))
    val esbuild = vscodeDir.dir("node_modules/esbuild").asFile
    doFirst {
        check(esbuild.exists()) { "Run `npm ci` in editors/vscode first: the plugin bundles its MCP server." }
    }
}

// The plugin's own version, read at run time (ProjectBridge.PLUGIN_VERSION).
val pluginVersionFile by tasks.registering {
    val version = providers.gradleProperty("pluginVersion")
    val out = layout.buildDirectory.file("generated/version/blackbox-plugin-version.txt")
    inputs.property("version", version)
    outputs.file(out)
    doLast { out.get().asFile.writeText(version.get()) }
}

tasks.processResources {
    from(bundleMcpServer) { into("blackbox-mcp") }
    from(pluginVersionFile)
}

intellijPlatform {
    pluginConfiguration {
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("sinceBuild")
            untilBuild = provider { null }
        }
    }
    // The oldest supported IDE, and the latest PhpStorm (no Java plugin there).
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, providers.gradleProperty("platformVersion"))
            create(IntelliJPlatformType.PhpStorm, providers.gradleProperty("verifyPhpStormVersion"))
        }
    }
    // Release only (.github/workflows/jetbrains.yml), from repository secrets.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // Pre-releases go to the "eap" channel.
        channels = providers.gradleProperty("publishChannel").map { listOf(it) }.orElse(listOf("default"))
    }
}

// An IDE for the conformance suite (editors/conformance): opens the project
// given with -PblackboxProject=<dir>, without first-run dialogs.
val runIdeForConformance by intellijPlatformTesting.runIde.registering {
    task {
        jvmArgumentProviders += CommandLineArgumentProvider {
            listOf(
                "-Didea.trust.all.projects=true",
                "-Djb.privacy.policy.text=<!--999.999-->",
                "-Djb.consents.confirmation.enabled=false",
                "-Dide.show.tips.on.startup.default.value=false",
                "-Didea.initially.ask.config=never",
                "-Dide.mac.message.dialogs.as.sheets=false",
                "-Didea.suppress.statistics.report=true",
                "-Dnosplash=true",
            )
        }
        argumentProviders += CommandLineArgumentProvider {
            listOfNotNull(providers.gradleProperty("blackboxProject").orNull)
        }
    }
}
