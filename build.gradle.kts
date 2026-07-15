plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.20"
    id("org.jetbrains.intellij.platform") version "2.10.4"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

group = "com.purringlabs.gitworktree"
version = "1.1.16"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        intellijIdea(version="2025.2.4")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        composeUI()

        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.25557"
        }

        changeNotes = """
            <b>What's new</b>
            <ul>
              <li>remove embedded telemetry; no outbound New Relic traffic</li>
              <li>require Compose so the tool window always installs</li>
              <li>await ignored-file copy before showing create success</li>
              <li>optional Claude Code context copy when creating a worktree</li>
              <li>split tool-window UI into screen/controller modules</li>
              <li>move primary UI and error strings into message bundle</li>
              <li>Plugin Verifier in CI against recommended IDE builds</li>
              <li>Simplified Chinese message bundle</li>
              <li>unit tests for ignored-file parsing and file copy security</li>
            </ul>
""".trimIndent()
    }

    pluginVerification {
        ides {
            recommended()
        }
    }

    signing {
        certificateChainFile = file("chain.crt")
        privateKeyFile = file("private.pem")
        password = providers.environmentVariable("PLUGIN_SIGNING_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("INTELLIJ_PLATFORM_PUBLISHING_TOKEN")
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
