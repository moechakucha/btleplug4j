import java.io.File
import org.gradle.internal.os.OperatingSystem

plugins {
    id("java")
}

group = "moe.prwk"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains:annotations:25.0.0")

    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

@Suppress("HasPlatformType")
val currentOs = OperatingSystem.current()

val rustProjectDir = projectDir.resolve("ffi")
val jextractOutputDir = layout.buildDirectory.dir("generated/sources/jextract/main").get().asFile
val generatedNativesDir = layout.buildDirectory.dir("generated/natives")
val isCi = project.hasProperty("ci")

val runTest = project.hasProperty("runTest")

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    forkEvery = 1
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }

    onlyIf { runTest }
    if (runTest) {
        dependsOn("copyNativeLibs")
    }

    doFirst {
        if (runTest) {
            val setupScript = projectDir.resolve("src/test/resources/setup_vhci.sh")
            if (setupScript.exists()) {
                logger.lifecycle("Initializing vHCI adapter")
                setupScript.setExecutable(true)
                val pb = ProcessBuilder("bash", setupScript.absolutePath)
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
                pb.redirectError(ProcessBuilder.Redirect.INHERIT)
                val process = pb.start()
                if (process.waitFor() != 0) {
                    throw GradleException("Failed to initialize vHCI")
                }
            }
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

fun findTool(toolName: String): String {
    val execName = if (currentOs.isWindows) "$toolName.exe" else toolName
    val pathEnv = System.getenv("PATH") ?: ""

    pathEnv.split(File.pathSeparator).forEach { dir ->
        val file = File(dir, execName)
        if (file.isFile && file.canExecute()) return file.absolutePath
    }

    if (currentOs.isMacOsX || currentOs.isLinux) {
        listOf(
            File(System.getProperty("user.home"), ".cargo/bin/$execName"),
            File("/opt/homebrew/bin/$execName"),
            File("/usr/local/bin/$execName")
        ).forEach { file ->
            if (file.isFile && file.canExecute()) return file.absolutePath
        }
    }
    return execName
}

val cargoPath = findTool("cargo")
val jextractPath = findTool("jextract")

tasks.register<Exec>("cargoBuild") {
    workingDir = rustProjectDir
    commandLine(cargoPath, "build", "--release")
    inputs.dir(rustProjectDir.resolve("src"))
    inputs.file(rustProjectDir.resolve("Cargo.toml"))
    outputs.dir(rustProjectDir.resolve("target/release"))
}

tasks.register<Exec>("generateBindings") {
    dependsOn("cargoBuild")
    val headerFile = rustProjectDir.resolve("bindings.h")

    doFirst {
        jextractOutputDir.mkdirs()
        if (!headerFile.exists()) {
            throw GradleException("Cannot find bindings.h. Did Cargo build fail?")
        }
    }

    commandLine(
        jextractPath,
        "--output", jextractOutputDir.absolutePath,
        "--header-class-name", "BtleplugFfi",
        "-t", "moe.prwk.btleplug4j.ffi",
        headerFile.absolutePath
    )
    outputs.dir(jextractOutputDir)
}

sourceSets {
    main {
        java { srcDir(jextractOutputDir) }
        resources {
            if (!isCi) srcDir(generatedNativesDir)
        }
    }
}

tasks.named("compileJava") {
    dependsOn("generateBindings")
}

val osNameStr = when {
    currentOs.isMacOsX -> "macos"
    currentOs.isWindows -> "windows"
    currentOs.isLinux -> "linux"
    else -> "unknown"
}
val archStr = System.getProperty("os.arch").let {
    if (it == "arm64" || it == "aarch64") "aarch64" else "x86_64"
}
val currentPlatform = "$osNameStr-$archStr"

tasks.jar {
    archiveClassifier.set(currentPlatform)
}

val ext = when {
    currentOs.isMacOsX -> "dylib"
    currentOs.isWindows -> "dll"
    else -> "so"
}
val prefix = if (currentOs.isWindows) "" else "lib"
val libName = "${prefix}btleplug4j_ffi.$ext"

tasks.register<Copy>("copyNativeLibs") {
    onlyIf { !isCi }
    dependsOn("cargoBuild")
    from(rustProjectDir.resolve("target/release/$libName"))
    into(generatedNativesDir.get().dir("natives/$currentPlatform"))
}

tasks.named("processResources") {
    dependsOn("copyNativeLibs")
}
