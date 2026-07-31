plugins {
    application
    java
    jacoco
    checkstyle
    id("org.cyclonedx.bom") version "3.3.0"
}

group = "dev.justnels.castcli"
version = (findProperty("castcliVersion") as String?)
    ?: System.getenv("CASTCLI_VERSION")
    ?: "0.1.2-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencyLocking {
    lockAllConfigurations()
}

checkstyle {
    toolVersion = "10.14.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    checkstyle("com.puppycrawl.tools:checkstyle:10.14.0") {
        exclude(group = "com.google.collections", module = "google-collections")
    }
    implementation(platform("dev.langchain4j:langchain4j-bom:1.18.0"))
    implementation("dev.langchain4j:langchain4j")
    implementation("dev.langchain4j:langchain4j-open-ai")
    implementation("dev.langchain4j:langchain4j-mcp:1.18.0-beta28")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("info.picocli:picocli:4.7.7")
    implementation("ch.qos.logback:logback-classic:1.6.0")
    implementation("org.xerial:sqlite-jdbc:3.51.2.0")
    implementation(platform("io.opentelemetry:opentelemetry-bom:1.64.0"))
    implementation("io.opentelemetry:opentelemetry-api")
    implementation("io.opentelemetry:opentelemetry-sdk")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    testImplementation(platform("org.junit:junit-bom:6.1.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.assertj:assertj-core:3.27.7")
}

application {
    mainClass = "dev.justnels.castcli.CastCli"
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "cast-cli",
            "Implementation-Version" to project.version
        )
    }
}

// Bundle the hardware presets onto the classpath (under /presets/) so InitService's default,
// classpath-based lookup works from any working directory -- including the standalone release
// zip / jpackage image, which doesn't carry a config/ directory. config/ remains the single
// source of truth in git; this just mirrors those specific files into the jar at build time.
tasks.named<ProcessResources>("processResources") {
    from("config") {
        include("harness.vram-*.json", "harness.apple-silicon.json")
        into("presets")
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("performance", "chaos")
    }
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("performanceTest") {
    group = "verification"
    description = "Runs opt-in deterministic performance and load regression probes."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("performance") }
    maxParallelForks = 1
    shouldRunAfter(tasks.test)
}

tasks.register<Test>("chaosTest") {
    group = "verification"
    description = "Runs opt-in deterministic fault and resilience scenarios."
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("chaos") }
    maxParallelForks = 1
    shouldRunAfter(tasks.test)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
    finalizedBy(tasks.jacocoTestCoverageVerification)
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    violationRules {
        rule {
            limit {
                minimum = "0.70".toBigDecimal()
            }
        }
        // Higher bar for packages where a coverage gap is a security bug, not just a UX bug --
        // the aggregate 70% minimum above can hide near-zero coverage in any one of these.
        // "tools" is currently at ~78%; capped at 75% here rather than 80% so this doesn't
        // block on untested-in-a-rush coverage -- raise it once real tests close the gap.
        mapOf("security" to "0.80", "reliability" to "0.80", "tools" to "0.75").forEach { (pkg, min) ->
            rule {
                element = "PACKAGE"
                includes = listOf("dev.justnels.castcli.$pkg")
                limit {
                    counter = "LINE"
                    minimum = min.toBigDecimal()
                }
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-parameters"))
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.register<Exec>("jpackageImage") {
    group = "distribution"
    description = "Builds a self-contained app image (bundled JRE, no host Java required) via jpackage."
    dependsOn(tasks.named("installDist"))

    val installLibDir = layout.buildDirectory.dir("install/cast-cli/lib")
    val jpackageOutputDir = layout.buildDirectory.dir("jpackage")
    val mainJarName = tasks.jar.get().archiveFileName

    inputs.dir(installLibDir)
    outputs.dir(jpackageOutputDir)

    doFirst {
        val outDir = jpackageOutputDir.get().asFile
        outDir.deleteRecursively()
        outDir.mkdirs()
    }

    // jpackage's --app-version only sets the macOS .app bundle's Info.plist metadata
    // (CFBundleShortVersionString) -- it is unrelated to the jar's Implementation-Version that
    // `cast-cli --version` actually reports. Apple's bundler rejects a version whose first
    // component is 0 ("The first number in an app-version cannot be zero or negative"), which
    // pre-1.0 releases like 0.1.0 always have, so bump a zero leading component to 1 here only.
    val rawVersion = project.version.toString().substringBefore("-SNAPSHOT")
    val appVersionParts = rawVersion.split(".").toMutableList()
    if (appVersionParts.isNotEmpty() && (appVersionParts[0].toIntOrNull() ?: 0) < 1) {
        appVersionParts[0] = "1"
    }
    val jpackageAppVersion = appVersionParts.joinToString(".")

    commandLine(
        "jpackage",
        "--type", "app-image",
        "--name", "cast-cli",
        "--app-version", jpackageAppVersion,
        "--input", installLibDir.get().asFile.absolutePath,
        "--main-jar", mainJarName.get(),
        "--main-class", "dev.justnels.castcli.CastCli",
        "--dest", jpackageOutputDir.get().asFile.absolutePath
    )
}

tasks.register("setupMcp") {
    dependsOn("installDist")
    val binPathFile = layout.buildDirectory.file("install/cast-cli/bin/cast-cli.bat").get().asFile
    val configPathFile = layout.projectDirectory.file("config/harness.local.json").asFile
    val mcpClientsDirFile = layout.projectDirectory.dir("config/mcp-clients").asFile

    doLast {
        val binPath = binPathFile.absolutePath
        val configPath = configPathFile.absolutePath

        mcpClientsDirFile.mkdirs()

        val claudeCmd = "claude mcp add cast-cli -- \"$binPath\" --config \"$configPath\" mcp-serve"
        File(mcpClientsDirFile, "claude_code.sh").writeText("#!/usr/bin/env bash\n$claudeCmd\n")

        val cursorJson = """
        {
          "mcpServers": {
            "cast-cli": {
              "command": "${binPath.replace("\\", "\\\\")}",
              "args": ["--config", "${configPath.replace("\\", "\\\\")}", "mcp-serve"]
            }
          }
        }
        """.trimIndent()
        File(mcpClientsDirFile, "cursor.json").writeText(cursorJson)

        logger.lifecycle("=========================================================================")
        logger.lifecycle("CastCLI MCP Server Setup Complete.")
        logger.lifecycle("To attach CastCLI to Claude Code, execute:")
        logger.lifecycle("  $claudeCmd")
        logger.lifecycle("Client config snippets generated in config/mcp-clients/")
        logger.lifecycle("=========================================================================")
    }
}

tasks.register<JavaExec>("generateAppCds") {
    group = "distribution"
    description = "Generates an AppCDS (Application Class Data Sharing) archive for instant JVM cold start."
    dependsOn(tasks.named("installDist"))
    classpath = files(tasks.named<CreateStartScripts>("startScripts").get().classpath)
    mainClass = "dev.justnels.castcli.CastCli"
    args = listOf("--help")
    jvmArgs = listOf(
        "-XX:ArchiveClassesAtExit=${layout.buildDirectory.get().asFile}/cast-cli.jsa"
    )
}
