package tasks.configuration

import Arch
import CompileSkikoCppTask
import OS
import SkiaBuildType
import SkikoProjectContext
import WriteCInteropDefFile
import compilerForTarget
import hostArch
import isCompatibleWithHost
import joinToTitleCamelCase
import listOfFrameworks
import mutableListOfLinkerOptions
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.getByName
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.CInteropProcess
import projectDirs
import registerOrGetSkiaDirProvider
import registerSkikoTask
import java.io.File

fun String.withSuffix(isUikitSim: Boolean = false) =
    this + if (isUikitSim) "Sim" else ""

fun KotlinTarget.isUikitSimulator() =
    name.contains("Simulator", ignoreCase = true) || name == "tvosX64" // x64 tvOS is implicitly a simulator

fun Project.findXcodeSdkRoot(): String {
    val defaultPath = "/Applications/Xcode.app/Contents/Developer/Platforms"
    if (File(defaultPath).exists()) {
        return defaultPath.also {
            println("findXcodeSdkRoot = $it")
        }
    }

    return (project.property("skiko.ci.xcodehome") as? String)?.let {
        val sdkPath = it + "/Platforms"
        println("findXcodeSdkRoot = $sdkPath")
        sdkPath
    } ?: error("gradle property `skiko.ci.xcodehome` is not set")
}

fun SkikoProjectContext.compileNativeBridgesTask(
    os: OS, arch: Arch, isUikitSim: Boolean
): TaskProvider<CompileSkikoCppTask> = with (this.project) {
    val skiaNativeDir = registerOrGetSkiaDirProvider(os, arch, isUikitSim = isUikitSim)

    val actionName = "compileNativeBridges".withSuffix(isUikitSim = isUikitSim)

    return project.registerSkikoTask<CompileSkikoCppTask>(actionName, os, arch) {
        dependsOn(skiaNativeDir)
        val unpackedSkia = skiaNativeDir.get()

        compiler.set(compilerForTarget(os, arch))
        buildTargetOS.set(os)
        if (isUikitSim) {
            buildSuffix.set("sim")
        }
        buildTargetArch.set(arch)
        buildVariant.set(buildType)

        when (os) {
            OS.IOS -> {
                val sdkRoot = findXcodeSdkRoot()
                val iphoneOsSdk = "$sdkRoot/iPhoneOS.platform/Developer/SDKs/iPhoneOS.sdk"
                val iphoneSimSdk = "$sdkRoot/iPhoneSimulator.platform/Developer/SDKs/iPhoneSimulator.sdk"
                val iosArchFlags = when (arch) {
                    Arch.Arm64 -> arrayOf(
                        "-target", if (isUikitSim) "arm64-apple-ios-simulator" else "arm64-apple-ios",
                        "-isysroot", if (isUikitSim) iphoneSimSdk else iphoneOsSdk,
                        if (isUikitSim) "-mios-simulator-version-min=12.0" else "-mios-version-min=12.0"
                    )
                    Arch.X64 -> arrayOf(
                        "-target", "x86_64-apple-ios-simulator",
                        "-mios-version-min=12.0",
                        "-isysroot", iphoneSimSdk
                    )
                    else -> throw GradleException("Unsupported arch: $arch")
                }
                flags.set(listOf(
                    *iosArchFlags,
                    *buildType.clangFlags,
                    "-stdlib=libc++",
                    *skiaPreprocessorFlags(OS.IOS, buildType),
                ))
            }
            OS.TVOS -> {
                val sdkRoot = findXcodeSdkRoot()
                val tvOsSdk = "$sdkRoot/AppleTVOS.platform/Developer/SDKs/AppleTVOS.sdk"
                val tvSimSdk = "$sdkRoot/AppleTVSimulator.platform/Developer/SDKs/AppleTVSimulator.sdk"
                val tvosArchFlags = when (arch) {
                    Arch.Arm64 -> arrayOf(
                        "-target", if (isUikitSim) "arm64-apple-tvos-simulator" else "arm64-apple-tvos",
                        if (isUikitSim) "-mappletvsimulator-version-min=12.0" else "-mappletvos-version-min=12.0" ,
                        "-isysroot", if (isUikitSim) tvSimSdk else tvOsSdk,
                    )
                    Arch.X64 -> arrayOf(
                        "-target", "x86_64-apple-tvos-simulator",
                        "-mappletvsimulator-version-min=12.0",
                        "-isysroot", tvSimSdk
                    )
                    else -> throw GradleException("Unsupported arch: $arch")
                }
                flags.set(listOf(
                    *tvosArchFlags,
                    *buildType.clangFlags,
                    "-stdlib=libc++",
                    *skiaPreprocessorFlags(OS.TVOS, buildType),
                ))
            }
            OS.MacOS -> {
                compiler.set(project.appleToolchainExecutableOrDefault("clang++", compiler.get()))
                flags.set(listOf(
                    *project.appleMacOsSdkFlags().toTypedArray(),
                    *buildType.clangFlags,
                    *skiaPreprocessorFlags(OS.MacOS, buildType),
                    when(arch) {
                        Arch.Arm64 -> "-arch arm64"
                        Arch.X64 -> "-arch x86_64"
                        else -> error("Unexpected arch: $arch for $os")
                    }
                ))
            }
            OS.Linux -> {
                val archFlags = if (arch == Arch.Arm64) arrayOf(
                    // Always inline atomics for ARM64 to prevent linking incompatibility issues after updating GCC to 10
                    "-mno-outline-atomics",
                ) else arrayOf()
                val linuxFlags = mutableListOf(
                    *buildType.clangFlags,
                    "-fPIC",
                    "-fno-rtti",
                    "-fno-exceptions",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    *archFlags,
                    *skiaPreprocessorFlags(OS.Linux, buildType)
                )
                // Add sysroot for ARM64 cross-compilation
                if (arch == Arch.Arm64 && hostArch != Arch.Arm64) {
                    linuxFlags.add(0, "--sysroot=/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc")
                }
                flags.set(linuxFlags)
            }
            OS.Windows -> {
                compiler.set("g++.exe")
                // Filter out clang-specific SK_TRIVIAL_ABI attribute, define it as empty for g++
                val gccCompatFlags = buildType.clangFlags
                    .filter { !it.contains("SK_TRIVIAL_ABI") }
                    .toTypedArray()
                flags.set(listOf(
                    *gccCompatFlags,
                    "-DSK_TRIVIAL_ABI=",
                    "-fno-rtti",
                    "-fno-exceptions",
                    "-fvisibility=hidden",
                    "-fvisibility-inlines-hidden",
                    *skiaPreprocessorFlags(OS.Windows, buildType, isNative = true),
                ))
            }
            else -> throw GradleException("$os not yet supported")
        }

        var srcDirs = projectDirs("src/commonMain/cpp/common", "src/nativeNativeJs/cpp", "src/nativeJsMain/cpp") +
                if (skiko.includeTestHelpers) projectDirs("src/nativeJsTest/cpp") else emptyList()
        if (os == OS.Windows) {
            srcDirs = srcDirs + projectDirs("src/mingwMain/cpp")
        }
        sourceRoots.set(srcDirs)

        includeHeadersNonRecursive(projectDir.resolve("src/nativeJsMain/cpp"))
        includeHeadersNonRecursive(projectDir.resolve("src/commonMain/cpp/common/include"))
        includeHeadersNonRecursive(skiaHeadersDirs(unpackedSkia))
        if (os == OS.Windows) {
            // ANGLE EGL headers from Skia's bundled third_party
            includeHeadersNonRecursive(unpackedSkia.resolve("third_party/externals/angle2/include"))
        }
    }
}


fun configureCinterop(
    cinteropName: String,
    os: OS,
    arch: Arch,
    target: KotlinNativeTarget,
    targetString: String,
    linkerOpts: List<String>,
    staticLibraries: List<String> = emptyList(),
    libraryPaths: List<String> = emptyList(),
) {
    val tasks = target.project.tasks
    val taskNameSuffix = joinToTitleCamelCase(os.idWithSuffix(isUikitSim = target.isUikitSimulator()), arch.id)
    val writeCInteropDef = tasks.register("writeCInteropDef$taskNameSuffix", WriteCInteropDefFile::class.java) {
        this.linkerOpts.set(linkerOpts)
        this.staticLibraries.set(staticLibraries)
        this.libraryPaths.set(libraryPaths)
        outputFile.set(project.layout.buildDirectory.file("cinterop/$targetString/skiko.def"))
    }
    tasks.withType(CInteropProcess::class.java).configureEach {
        if (konanTarget == target.konanTarget) {
            dependsOn(writeCInteropDef)
        }
    }
    target.compilations.getByName("main") {
        cinterops.create(cinteropName).apply {
            definitionFile.set(writeCInteropDef.flatMap { it.outputFile })
        }
    }
}

fun skiaStaticLibraries(skiaDir: String, targetString: String, buildType: SkiaBuildType, os: OS = OS.Linux): List<String> {
    val skiaBinSubdir = "$skiaDir/out/${buildType.id}-$targetString"
    val commonLibs = listOf(
        "libskresources.a",
        "libskparagraph.a",
        "libskia.a",
        "libicu.a",
        "libjsonreader.a",
        "libskottie.a",
        "libsvg.a",
        "libpng.a",
        "libwebp_sse41.a",
        "libsksg.a",
        "libskunicode_core.a",
        "libskunicode_icu.a",
        "libwebp.a",
        "libharfbuzz.a",
        "libexpat.a",
        "libzlib.a",
        "libjpeg.a",
        "libskshaper.a",
    )
    val platformLibs = if (!os.isWindows) {
        // piex/dng_sdk are not used on Windows
        listOf("libdng_sdk.a", "libpiex.a")
    } else {
        emptyList()
    }
    return (commonLibs + platformLibs).map { "$skiaBinSubdir/$it" }
}

fun SkikoProjectContext.configureNativeTarget(os: OS, arch: Arch, target: KotlinNativeTarget) = with(this.project) {
    if (!os.isCompatibleWithHost) return

    target.generateVersion(os, arch, skiko)
    val isUikitSim = target.isUikitSimulator()

    val targetString = "${os.idWithSuffix(isUikitSim = isUikitSim)}-${arch.id}"
    // Skia's mingw build uses target_os="mingw", so the output dir is "mingw-x64" not "windows-x64"
    val skiaTargetString = if (os == OS.Windows) "mingw-${arch.id}" else targetString

    val unzipper = registerOrGetSkiaDirProvider(os, arch, isUikitSim)
    val unpackedSkia = unzipper.get()
    val skiaDir = unpackedSkia.absolutePath

    val bridgesLibrary = layout.buildDirectory.file("nativeBridges/static/$targetString/skiko-native-bridges-$targetString.a")
    val bridgesLibraryPath = bridgesLibrary.get().asFile.absolutePath
    val compatLibraries = if (os == OS.Windows) listOf(
        project.file("src/mingwMain/cpp/libstdc++_subset.a").absolutePath,
        project.file("src/mingwMain/cpp/libmingw_compat.a").absolutePath,
    ) else emptyList()
    val allLibraries = skiaStaticLibraries(skiaDir, skiaTargetString, buildType, os) + bridgesLibraryPath + compatLibraries

    val skiaBinDir = "$skiaDir/out/${buildType.id}-$skiaTargetString"
    val linkerFlags = when (os) {
        OS.MacOS -> {
            val macFrameworks = listOfFrameworks("Metal", "CoreGraphics", "CoreText", "CoreServices")
            configureCinterop("skiko", os, arch, target, targetString, macFrameworks)
            mutableListOfLinkerOptions(macFrameworks)
        }
        OS.IOS -> {
            val iosFrameworks = listOfFrameworks("Metal", "CoreGraphics", "CoreText", "UIKit")
            // list of linker options to be included into klib, which are needed for skiko consumers
            // https://github.com/JetBrains/compose-multiplatform/issues/3178
            // Important! Removing or renaming cinterop-uikit publication might cause compile error
            // for projects depending on older Compose/Skiko transitively https://youtrack.jetbrains.com/issue/KT-60399
            configureCinterop("uikit", os, arch, target, targetString, iosFrameworks)
            mutableListOfLinkerOptions(iosFrameworks)
        }
        OS.TVOS -> {
            val tvosFrameworks = listOfFrameworks("Metal", "CoreGraphics", "CoreText", "UIKit")
            configureCinterop("uikit", os, arch, target, targetString, tvosFrameworks)
            mutableListOfLinkerOptions(tvosFrameworks)
        }
        OS.Linux -> {
            val options = mutableListOf(
                "-L/usr/lib64",
                "-L/usr/lib/${if (arch == Arch.Arm64) "aarch64" else "x86_64"}-linux-gnu",
                "-lfontconfig",
                "-lGL",
                // TODO: an ugly hack, Linux linker searches only unresolved symbols.
                "$skiaBinDir/libskottie.a",
                "$skiaBinDir/libjsonreader.a",
                "$skiaBinDir/libsksg.a",
                "$skiaBinDir/libskshaper.a",
                "$skiaBinDir/libskunicode_core.a",
                "$skiaBinDir/libskunicode_icu.a",
                "$skiaBinDir/libskia.a"
            )
            if (arch == Arch.Arm64) {
                options.add("-lEGL")
            }
            // When cross-compiling for ARM64 from x64, use the ARM toolchain sysroot
            if (arch == Arch.Arm64 && hostArch != Arch.Arm64) {
                // ARM GNU toolchain sysroot paths
                options.add(0, "-L/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc/lib64")
                options.add(1, "-L/opt/arm-gnu-toolchain/aarch64-none-linux-gnu/libc/usr/lib64")
            }
            mutableListOfLinkerOptions(options)
        }
        OS.Windows -> {
            // System libraries needed by Skia on Windows — embedded into klib via cinterop
            // so consumers inherit them automatically.
            val windowsLinkerOpts = listOf(
                "-lopengl32", "-lgdi32", "-luser32",
                "-lole32", "-loleaut32", "-ldwrite",
                "-ld2d1", "-lwindowscodecs", "-lusp10", "-luuid",
                "-Wl,--allow-multiple-definition",
            )
            configureCinterop("skiko", os, arch, target, targetString, windowsLinkerOpts)

            val options = mutableListOf<String>()
            options.addAll(windowsLinkerOpts.flatMap { listOf("-linker-option", it) })
            // Skia static libraries — embedded via -include-binary through allLibraries.
            // GCC compat libs (libstdc++_subset.a, libmingw_compat.a) are also in
            // allLibraries and embedded the same way.
            options.addAll(mutableListOfLinkerOptions(mutableListOf(
                "$skiaBinDir/libskottie.a",
                "$skiaBinDir/libjsonreader.a",
                "$skiaBinDir/libsksg.a",
                "$skiaBinDir/libskshaper.a",
                "$skiaBinDir/libskunicode_core.a",
                "$skiaBinDir/libskunicode_icu.a",
                "$skiaBinDir/libskia.a",
            )))
            options
        }
        else -> mutableListOf()
    }
    if (skiko.includeTestHelpers) {
        linkerFlags.addAll(when (os) {
            OS.Linux -> listOf(
                "-linker-option", "-lX11",
                "-linker-option", "-lGLX",
            )
            else -> emptyList()
        })
    }

    // For some reason since 1.8.0 we need to set freeCompilerArgs for binaries AND for compilations
    target.binaries.all {
        freeCompilerArgs += allLibraries.map { listOf("-include-binary", it) }.flatten() + linkerFlags
    }


    target.compilations.all {
        compilerOptions.configure {
            freeCompilerArgs.addAll(
                allLibraries.flatMap { listOf("-include-binary", it) } + linkerFlags
            )
        }
    }

    val crossCompileTask = compileNativeBridgesTask(os, arch, isUikitSim = isUikitSim)

    // TODO: move to LinkSkikoTask.
    val actionName = "linkNativeBridges".withSuffix(isUikitSim = isUikitSim)
    val linkTask = project.registerSkikoTask<Exec>(actionName, os, arch) {
        dependsOn(crossCompileTask)
        val objectFilesDir = crossCompileTask.map { it.outDir.get() }
        val objectFiles = project.fileTree(objectFilesDir) {
            include("**/*.o")
        }
        inputs.files(objectFiles)
        val outDir = layout.buildDirectory.dir("nativeBridges/static/$targetString").get().asFile
        val staticLib = "skiko-native-bridges-$targetString.a"
        workingDir = outDir
        when (os) {
            OS.Linux -> {
                executable = if (arch == Arch.Arm64 && hostArch != Arch.Arm64) "aarch64-linux-gnu-ar" else "ar"
                argumentProviders.add { listOf("-crs", staticLib) }
            }
            OS.MacOS, OS.IOS, OS.TVOS -> {
                executable = "libtool"
                argumentProviders.add { listOf("-static", "-o", staticLib) }
            }
            OS.Windows -> {
                executable = "ar"
                argumentProviders.add { listOf("-crs", staticLib) }
            }
            else -> error("Unexpected OS for native bridges linking: $os")
        }
        argumentProviders.add { objectFiles.files.map { it.absolutePath } }
        file(outDir).mkdirs()
        outputs.dir(outDir)
    }
    target.compilations.all {
        compileTaskProvider.configure {
            dependsOn(linkTask)
        }
    }
}


fun KotlinMultiplatformExtension.configureIOSTestsWithMetal(project: Project) {
    val metalTestTargets = listOf("iosX64", "iosSimulatorArm64")
    metalTestTargets.forEach { target: String ->
        if (targets.names.contains(target)) {
            val testBinary = targets.getByName<KotlinNativeTarget>(target).binaries.getTest("DEBUG")
            project.tasks.register(target + "TestWithMetal") {
                dependsOn(testBinary.linkTaskProvider)
                doLast {
                    val simulatorIdPropertyKey = "skiko.iosSimulatorUUID"
                    val simulatorId = project.findProperty(simulatorIdPropertyKey)?.toString()
                        ?: error("Property '$simulatorIdPropertyKey' not found. Pass it with -P$simulatorIdPropertyKey=...")

                    project.providers.exec { commandLine("xcrun", "simctl", "boot", simulatorId) }
                    try {
                        project.providers.exec { commandLine("xcrun", "simctl", "spawn", simulatorId, testBinary.outputFile) }
                    } finally {
                        project.providers.exec { commandLine("xcrun", "simctl", "shutdown", simulatorId) }
                    }
                }
            }
        }
    }
}
