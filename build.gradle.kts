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

val targetPlatforms = listOf(
    PlatformInfo("macos", "aarch64", "aarch64-apple-darwin", "dylib", "lib"),
    PlatformInfo("macos", "x86_64", "x86_64-apple-darwin", "dylib", "lib"),
    PlatformInfo("windows", "x86_64", "x86_64-pc-windows-gnu", "dll", ""),
    PlatformInfo("linux", "x86_64", "x86_64-unknown-linux-gnu", "so", "lib")
)

data class PlatformInfo(
    val os: String,
    val arch: String,
    val triple: String,
    val ext: String,
    val prefix: String
) {
    val name = "$os-$arch"
    val libName = "${prefix}btleplug4j_ffi.$ext"
}

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
        dependsOn("copyNative_linux_x86_64")
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

fun findTool(toolName: String, extraPrefix: String = "exe"): String {
    val execName = if (currentOs.isWindows) "$toolName.$extraPrefix" else toolName
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
val jextractPath = findTool("jextract", "bat")

tasks.register<Exec>("cargoBuild") {
    group = "build"
    workingDir = rustProjectDir
    commandLine(cargoPath, "build", "--release")
    inputs.dir(rustProjectDir.resolve("src"))
    inputs.file(rustProjectDir.resolve("Cargo.toml"))
    outputs.dir(rustProjectDir.resolve("target/release"))
}

val dumpSymbolsFile = layout.buildDirectory.file("tmp/jextract/all_symbols.txt")
val filteredSymbolsFile = layout.buildDirectory.file("tmp/jextract/filtered_symbols.txt")

tasks.register<Exec>("dumpAllSymbols") {
    dependsOn("cargoBuild")
    val headerFile = rustProjectDir.resolve("bindings.h")

    doFirst {
        dumpSymbolsFile.get().asFile.parentFile.mkdirs()
    }

    commandLine(
        jextractPath,
        "--dump-includes", dumpSymbolsFile.get().asFile.absolutePath,
        headerFile.absolutePath
    )

    outputs.file(dumpSymbolsFile)
}

tasks.register<Exec>("generateBindings") {
    dependsOn("cargoBuild", "dumpAllSymbols")
    val headerFile = rustProjectDir.resolve("bindings.h")

    doFirst {
        jextractOutputDir.mkdirs()

        if (!headerFile.exists()) {
            throw GradleException("Cannot find bindings.h. Did Cargo build fail?")
        }

        val rawSymbols = dumpSymbolsFile.get().asFile.readLines()
        val filteredSymbols = mutableListOf<String>()

        rawSymbols.forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("--include-function ble_") ||
                trimmed.startsWith("--include-struct Ble") ||
                trimmed.startsWith("--include-typedef NotifyCallback")) {

                val parts = trimmed.split(" ", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim().split(" ", limit = 2)[0]
                    filteredSymbols.add("$key $value")
                }
            }
        }

        filteredSymbolsFile.get().asFile.writeText(filteredSymbols.joinToString("\n"))
    }

    val cmd = mutableListOf(
        jextractPath,
        "--output", jextractOutputDir.absolutePath,
        "--header-class-name", "BtleplugFfi",
        "-t", "moe.prwk.btleplug4j.ffi"
    )
    cmd.add("@${filteredSymbolsFile.get().asFile.absolutePath}")
    cmd.add(headerFile.absolutePath)

    commandLine(cmd)
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

targetPlatforms.forEach { platform ->
    val compileTaskName = "cargoCrossBuild_${platform.os}_${platform.arch}"
    val copyTaskName = "copyNative_${platform.os}_${platform.arch}"

    val compileTask = tasks.register<Exec>(compileTaskName) {
        group = "build"
        workingDir = rustProjectDir
        val cmd = mutableListOf(cargoPath)
        if (platform.os == "windows") {
            cmd.add("xwin")
            cmd.add("build")
        } else {
            cmd.add("zigbuild")
        }
        cmd.addAll(arrayOf("--release", "--target", platform.triple))
        commandLine(cmd)
        inputs.dir(rustProjectDir.resolve("src"))
        inputs.file(rustProjectDir.resolve("Cargo.toml"))
        outputs.dir(rustProjectDir.resolve("target/${platform.triple}/release"))
    }

    tasks.register<Copy>(copyTaskName) {
        dependsOn(compileTask)
        from(rustProjectDir.resolve("target/${platform.triple}/release/${platform.libName}"))
        into(generatedNativesDir.get().dir("natives/${platform.name}"))
    }
}

tasks.register("buildAllPlatformsNatives") {
    group = "build"
    dependsOn("compileJava")

    targetPlatforms.forEach { platform ->
        val copyTaskName = "copyNative_${platform.os}_${platform.arch}"
        dependsOn(copyTaskName)
    }
}

tasks.named("jar") {
    dependsOn("buildAllPlatformsNatives")
}
