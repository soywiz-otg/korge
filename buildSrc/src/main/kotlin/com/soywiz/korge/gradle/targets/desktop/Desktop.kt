package com.soywiz.korge.gradle.targets.desktop

import com.soywiz.korge.gradle.*
import com.soywiz.korge.gradle.targets.*
import com.soywiz.korge.gradle.targets.apple.*
import com.soywiz.korge.gradle.targets.native.*
import com.soywiz.korge.gradle.targets.windows.*
import com.soywiz.korge.gradle.util.*
import com.soywiz.korge.gradle.util.get
import org.gradle.api.*
import org.gradle.api.file.*
import org.gradle.api.internal.plugins.DslObject
import org.gradle.api.reporting.ReportingExtension
import org.gradle.api.tasks.*
import org.gradle.kotlin.dsl.extra
import org.gradle.testing.base.plugins.TestingBasePlugin
import org.jetbrains.kotlin.gradle.plugin.mpp.*
import org.jetbrains.kotlin.gradle.targets.native.tasks.*
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink
import java.io.*

fun Project.configureNativeDesktop() {
	val project = this

    /*
    val common = gkotlin.sourceSets.createPairSourceSet("common") { test ->
        dependencies {
            if (test) {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
            } else {
                implementation(kotlin("stdlib-common"))
            }
        }
    }
    val concurrent = gkotlin.sourceSets.createPairSourceSet("concurrent", common)
    val nonNativeCommon = gkotlin.sourceSets.createPairSourceSet("nonNativeCommon", common)
    val nonJs = gkotlin.sourceSets.createPairSourceSet("nonJs", common)
    val nonJvm = gkotlin.sourceSets.createPairSourceSet("nonJvm", common)
    val jvmAndroid = gkotlin.sourceSets.createPairSourceSet("jvmAndroid", common)
    val nativeCommon by lazy { gkotlin.sourceSets.createPairSourceSet("nativeCommon", concurrent) }
    */

	for (preset in DESKTOP_NATIVE_TARGETS) {
        //val target = gkotlin.presets.getAt(preset) as KotlinNativeTargetPreset
		gkotlin.targets.add((gkotlin.presets.getAt(preset) as AbstractKotlinNativeTargetPreset<*>).createTarget(preset).apply {
            configureKotlinNativeTarget(project)
            //val target = this
            //val native = gkotlin.sourceSets.createPairSourceSet(target.name, common, nativeCommon, nonJvm, nonJs)
            //native.dependsOn(nativeCommon)

            //(compilations["main"] as KotlinNativeCompilation).outputKinds("EXECUTABLE")
			binaries {
                executable {
                    //this.entryPoint = "korge.bootstrap.main"
                    //this.entryPoint = "korge.bootstrap"
                }
            }
		})
	}

	//project.afterEvaluate {}


	afterEvaluate {
		for (target in convertNamesToNativeTargets(DESKTOP_NATIVE_TARGETS)) {
			val mainCompilation = target.compilations["main"]
			for (type in NativeBuildTypes.TYPES) {
				mainCompilation.getCompileTask(NativeOutputKind.EXECUTABLE, type, project).dependsOn(prepareKotlinNativeBootstrap)
			}
			mainCompilation.defaultSourceSet.kotlin.srcDir(project.file("build/platforms/native-desktop/"))
		}
	}

	project.afterEvaluate {
		for (target in DESKTOP_NATIVE_TARGETS) {
			if (isLinux && target.endsWith("Arm32Hfp")) {
				// don't create an Arm32Hfp test task
				continue
			}
			val taskName = "copyResourcesToExecutableTest_${target.capitalize()}"
			val targetTestTask = project.tasks.getByName("${target}Test") as KotlinNativeTest
			val task = project.addTask<Copy>(taskName) { task ->
				for (sourceSet in project.gkotlin.sourceSets) {
					task.from(sourceSet.resources)
				}
				task.into(targetTestTask.executableFolder)
			}
			targetTestTask.dependsOn(task)
		}

		// @TODO: This doesn't work after migrating code to Kotlin.
		//for (target in listOf(project.gkotlin.targets["js"], project.gkotlin.targets["metadata"])) {
		//    for (it in (target["compilations"]["main"]["kotlinSourceSets"]["resources"] as Iterable<SourceDirectorySet>).flatMap { it.srcDirs }) {
		//        (tasks["jsJar"] as Copy).from(it)
		//    }
		//}
	}

	addNativeRun()
}

fun Project.addNativeRun() {
    val project = this

    fun KotlinNativeCompilation.getBinary(kind: NativeOutputKind, type: NativeBuildType): File {
        return this.getLinkTask(kind, type, project).binary.outputFile.absoluteFile
    }

    afterEvaluate {
        //run {
        for (target in DESKTOP_NATIVE_TARGETS) {
            val ctarget = target.capitalize()
            val isMingwX64 = target == "mingwX64"
            for (kind in NativeBuildTypes.TYPES) {
                val ckind = kind.name.toLowerCase().capitalize()
                val ctargetKind = "$ctarget$ckind"
                val buildType = if (kind == NativeBuildType.DEBUG) NativeBuildType.DEBUG else NativeBuildType.RELEASE

                val ktarget = gkotlin.targets[target]
                //(ktarget as KotlinNativeTarget).attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.native)

                val compilation = ktarget["compilations"]["main"] as KotlinNativeCompilation

                if (isMingwX64) {
                    //compilation.getLinkTask(NativeOutputKind.EXECUTABLE, buildType, project).dependsOn(mingwX64SelectPatchedMemoryManager)
                }

                val executableFile = compilation.getBinary(NativeOutputKind.EXECUTABLE, buildType)

                val appResFile = buildDir["app.res"]
                val appRcFile = buildDir["app.rc"]
                val appRcObjFile = buildDir["app.obj"]
                val appIcoFile = buildDir["icon.ico"]

                val copyTask = project.addTask<Copy>("copyResourcesToExecutable$ctargetKind") { task ->
                    task.dependsOn(compilation.compileKotlinTask)
                    task.duplicatesStrategy = DuplicatesStrategy.INCLUDE
                    for (sourceSet in project.gkotlin.sourceSets) {
                        task.from(sourceSet.resources)
                    }
                    //println("executableFile.parentFile: ${executableFile.parentFile}")
                    task.into(executableFile.parentFile)
                    if (isMingwX64) {
                        //if (false) {
                        doLast {
                            val bmp32 = project.korge.getIconBytes(32).decodeImage()
                            val bmp256 = project.korge.getIconBytes(256).decodeImage()

                            appIcoFile.writeBytes(ICO2.encode(listOf(bmp32, bmp256)))

                            val linkTask = compilation.getLinkTask(NativeOutputKind.EXECUTABLE, buildType, project)

                            val isConsole = korge.enableConsole ?: (kind == NativeBuildType.DEBUG)
                            val subsystem = if (isConsole) "console" else "windows"

                            run {
                                // @TODO: https://releases.llvm.org/9.0.0/tools/lld/docs/ReleaseNotes.html#id6
                                // @TODO: lld-link now rejects more than one resource object input files, matching link.exe. Previously, lld-link would silently ignore all but one. If you hit this: Don’t pass resource object files to the linker, instead pass res files to the linker directly. Don’t put resource files in static libraries, pass them on the command line. (r359749)
                                //appRcFile.writeText(WindowsRC.generate(korge))
                                //project.compileWindowsRC(appRcFile, appRcObjFile)
                                //linkTask.binary.linkerOpts(appRcObjFile.absolutePath, "-Wl,--subsystem,$subsystem")
                            }

                            run {
                                // @TODO: Not working either!
                                //appRcFile.writeText(WindowsRC.generate(korge))
                                //val appResFile = project.compileWindowsRES(appRcFile)
                                //linkTask.binary.linkerOpts(appResFile.absolutePath, "-Wl,--subsystem,$subsystem")
                            }

                            run {
                                appRcFile.writeText(WindowsRC.generate(korge))
                                project.compileWindowsRES(appRcFile, appResFile)
                                linkTask.binary.linkerOpts("-Wl,--subsystem,$subsystem")
                            }
                        }
                        //println("compilation:$compilation")
                        //compilation.linkerOpts(appRcObjFile.absolutePath, "-Wl,--subsystem,console")
                        afterEvaluate {
                        }
                    }
                }

                afterEvaluate {
                    try {
                        val linkTask = compilation.getLinkTask(NativeOutputKind.EXECUTABLE, buildType, project)
                        linkTask.dependsOn(copyTask)
                        if (isMingwX64) {
                            linkTask.doLast {
                                replaceExeWithRes(linkTask.outputFile.get(), appResFile)
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }

                //addTask<Exec>("runNative$ctargetKind", dependsOn = listOf("linkMain${ckind}Executable$ctarget", copyTask), group = GROUP_KORGE) { task ->
                addTask<Exec>("runNative$ctargetKind", dependsOn = listOf("link${ckind}Executable$ctarget", copyTask), group = GROUP_KORGE) { task: Exec ->
                    task.group = GROUP_KORGE_RUN
                    task.executable = executableFile.absolutePath
                    task.args()
                }
            }
            addTask<Task>("runNative$ctarget", dependsOn = listOf("runNative${ctarget}Release"), group = GROUP_KORGE) { task ->
                task.group = GROUP_KORGE_RUN
            }
        }
    }

    addTask<Task>("runNative", dependsOn = listOf("runNative$cnativeTarget"), group = GROUP_KORGE_RUN)
    addTask<Task>("runNativeDebug", dependsOn = listOf("runNative${cnativeTarget}Debug"), group = GROUP_KORGE_RUN)
    addTask<Task>("runNativeRelease", dependsOn = listOf("runNative${cnativeTarget}Release"), group = GROUP_KORGE_RUN)

    afterEvaluate {
        if (isMacos) {
            for (buildType in NativeBuildTypes.TYPES) {
                val buildTypeLC = buildType.name.toLowerCase()
                for (nativeTargetName in listOf("macosArm64", "macosX64")) {
                    //val nativeTargetName = if (isArm) "macosArm64" else "macosX64"

                    val ktarget = gkotlin.targets[nativeTargetName]
                    //(ktarget as KotlinNativeTarget).attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.native)
                    val compilation = ktarget["compilations"]["main"] as KotlinNativeCompilation

                    addTask<Task>(
                        "package${nativeTargetName.capitalize()}App${buildTypeLC.capitalize()}", // @TODO: What happens on macosArm64?
                        group = GROUP_KORGE_PACKAGE,
                        dependsOn = listOf(compilation.getLinkTask(NativeOutputKind.EXECUTABLE, buildType, project))
                        //dependsOn = listOf("link${buildType.name.capitalize()}ExecutableMacosX64")
                    ) {

                        group = GROUP_KORGE_PACKAGE
                        doLast {
                            val compilation = gkotlin.targets[nativeTargetName]["compilations"]["main"] as KotlinNativeCompilation
                            val executableFile = compilation.getBinary(NativeOutputKind.EXECUTABLE, buildType)
                            val appFolder = buildDir["${korge.name}-${nativeTargetName}-${buildTypeLC}.app"].apply { mkdirs() }
                            val appFolderContents = appFolder["Contents"].apply { mkdirs() }
                            val appMacOSFolder = appFolderContents["MacOS"].apply { mkdirs() }
                            val resourcesFolder = appFolderContents["Resources"].apply { mkdirs() }
                            appFolderContents["Info.plist"].writeText(InfoPlistBuilder.build(korge))
                            resourcesFolder["${korge.exeBaseName}.icns"].writeBytes(IcnsBuilder.build(korge.getIconBytes()))
                            copy {
                                val copy = this
                                copy.duplicatesStrategy = DuplicatesStrategy.INCLUDE
                                for (sourceSet in project.gkotlin.sourceSets) {
                                    copy.from(sourceSet.resources)
                                }
                                copy.into(resourcesFolder)
                            }
                            executableFile.copyTo(appMacOSFolder[korge.exeBaseName], overwrite = true)
                            appMacOSFolder[korge.exeBaseName].setExecutable(true)
                        }
                    }
                }
            }
        }
        if (isWindows) {
            val target = "mingwX64"
            val compilation = gkotlin.targets[target]["compilations"]["main"] as KotlinNativeCompilation
            for (kind in NativeBuildTypes.TYPES) {
                val buildType = if (kind == NativeBuildType.DEBUG) NativeBuildType.DEBUG else NativeBuildType.RELEASE
                val linkTask = compilation.getLinkTask(NativeOutputKind.EXECUTABLE, buildType, project)

                addTask<Task>(
                    "packageMingwX64App${kind.name.capitalize()}",
                    group = GROUP_KORGE_PACKAGE,
                    dependsOn = listOf(linkTask)
                ) {
                    val inputFile = linkTask.binary.outputFile
                    val strippedFile = inputFile.parentFile[inputFile.nameWithoutExtension + "-stripped.exe"]
                    doLast {
                        val bytes = inputFile.readBytes()
                        bytes[0xDC] = 2 // Convert exe into WINDOW_GUI(2) subsystem (prevents opening a console window)
                        strippedFile.writeBytes(bytes)
                        stripWindowsExe(strippedFile)
                    }
                }
            }
        }
    }
}
