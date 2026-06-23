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

tasks.test {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    forkEvery = 1
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

@Suppress("HasPlatformType")
val currentOs = OperatingSystem.current()

val rustProjectDir = projectDir.resolve("ffi")
val jextractOutputDir = layout.buildDirectory.dir("generated/sources/jextract/main").get().asFile
val prodNativesDir = layout.buildDirectory.dir("generated/natives/main")
val testNativesDir = layout.buildDirectory.dir("generated/natives/test")
val isCi = project.hasProperty("ci")

fun findTool(toolName: String): String {
    val os = currentOs
    val execName = if (os.isWindows) "$toolName.exe" else toolName

    val pathEnv = System.getenv("PATH") ?: ""
    pathEnv.split(File.pathSeparator).forEach { dir ->
        val file = File(dir, execName)
        if (file.isFile && file.canExecute()) {
            return file.absolutePath
        }
    }

    if (os.isMacOsX || os.isLinux) {
        val fallbacks = listOf(
            File(System.getProperty("user.home"), ".cargo/bin/$execName"),
            File("/opt/homebrew/bin/$execName"),
            File("/usr/local/bin/$execName")
        )
        fallbacks.forEach { file ->
            if (file.isFile && file.canExecute()) {
                return file.absolutePath
            }
        }
    }

    return execName
}

val cargoPath = findTool("cargo")
val jextractPath = findTool("jextract")

tasks.register<Exec>("cargoBuildProd") {
    onlyIf { !isCi }
    workingDir = rustProjectDir
    commandLine(cargoPath, "build", "--release")
    inputs.dir(rustProjectDir.resolve("src"))
    inputs.file(rustProjectDir.resolve("Cargo.toml"))
    outputs.dir(rustProjectDir.resolve("target/release"))

    doFirst {
        if (cargoPath == "cargo") {
            logger.warn("Warning: Could not find 'cargo' in PATH or standard directories.")
        }
    }
}

tasks.register<Exec>("cargoBuildMock") {
    onlyIf { !isCi }
    workingDir = rustProjectDir
    commandLine(cargoPath, "build", "--release", "--features", "mock-hw", "--target-dir", "target-test")
    inputs.dir(rustProjectDir.resolve("src"))
    inputs.file(rustProjectDir.resolve("Cargo.toml"))
    outputs.dir(rustProjectDir.resolve("target-test/release"))

    doFirst {
        if (cargoPath == "cargo") {
            logger.warn("Warning: Could not find 'cargo' in PATH or standard directories.")
        }
    }
}

tasks.register<Exec>("generateBindings") {
    onlyIf { !isCi }
    dependsOn("cargoBuildProd")
    val headerFile = rustProjectDir.resolve("bindings.h")

    doFirst {
        jextractOutputDir.mkdirs()

        if (jextractPath == "jextract") {
            logger.warn("Warning: Could not find 'jextract' in PATH or standard directories.")
        }

        if (!headerFile.exists()) {
            throw GradleException("Cannot find bindings.h at ${headerFile.absolutePath}. Did Cargo build fail?")
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
        java {
            srcDir(jextractOutputDir)
        }
        resources {
            if (!isCi) {
                srcDir(prodNativesDir)
            }
        }
    }
    test {
        resources {
            if (!isCi) {
                srcDir(testNativesDir)
            }
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

val ext = when {
    currentOs.isMacOsX -> "dylib"
    currentOs.isWindows -> "dll"
    else -> "so"
}
val prefix = if (currentOs.isWindows) "" else "lib"
val libName = "${prefix}btleplug4j_ffi.$ext"

tasks.register<Copy>("copyProdNatives") {
    onlyIf { !isCi }
    dependsOn("cargoBuildProd")
    from(rustProjectDir.resolve("target/release/$libName"))
    into(prodNativesDir.get().dir("natives/$currentPlatform"))
}

tasks.register<Copy>("copyMockNatives") {
    onlyIf { !isCi }
    dependsOn("cargoBuildMock")
    from(rustProjectDir.resolve("target-test/release/$libName"))
    into(testNativesDir.get().dir("natives/$currentPlatform"))
}

tasks.named("processResources") {
    dependsOn("copyProdNatives")
}

tasks.named("processTestResources") {
    dependsOn("copyMockNatives")
}
