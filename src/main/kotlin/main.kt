import java.io.File
import org.redundent.kotlin.xml.xml
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class Control(
    val pkg : String,
    val version : String,
    val depends : List<String>?,
    val architecture : String,
    val abiHash : String,
    val description : String?,
    val type : String,
    val packageFolder : File,
    val name : String
)

fun main(args: Array<String>) {
    val packagesFolder = File(args[0])
    val namespace = args[1]
    val api32 = args[2]
    val api64 = args[3]
    val ndk = args[4]
    val stl = args[5]
    val aarFolder = packagesFolder.resolveSibling("aar")
    val aarBuildFolder = packagesFolder.resolveSibling("aar-build")
    aarFolder.mkdirs()
    aarBuildFolder.mkdirs()
    val types = mutableSetOf<String>()
    val controls = packagesFolder.list().toList()
        .map { packagesFolder.resolve(it) }
        .filter { it.isDirectory }
        .map { it.resolve("CONTROL") }
        .filter { it.isFile }
        .map {
            val map = mutableMapOf<String, String>()
            for (line in it.readLines()) {
                val index = line.indexOf(':')
                if (index == -1) break
                map[line.substringBefore(':').trim()] = line.substringAfter(':').trim()
            }
            types.add(map["Type"]!!)
            Control(
                pkg = map.getValue("Package"),
                version = map.getValue("Version"),
                depends = map["Depends"]?.split(","),
                architecture = map.getValue("Architecture"),
                abiHash = map.getValue("Abi"),
                description = map["Description"],
                type = map.getValue("Type"),
                packageFolder = it.parentFile,
                name = "${map.getValue("Package")}-${map.getValue("Version")}.aar"
            )
        }
        .filter { it.architecture.endsWith("-android") }
        //.filter { it.pkg.contains("boost-uninstall") }

    for(control in controls) {
        val aarBuildFolder = aarBuildFolder.resolve(control.name)

        val (abi, api) = when(control.architecture) {
            "x64-android" -> "x86_64" to api64
            "arm-android" -> "armeabi-v7a" to api32
            "arm64-android" -> "arm64-v8a" to api64
            "x86-android" -> "x86" to api32
            else -> error(control.architecture)
        }

        // Libs
        val sourceLibFolder = control.packageFolder.resolve("lib")
        val sourceLibs = (sourceLibFolder.list() ?: arrayOf())
            .toList()
            .filter { it.endsWith(".so") or it.endsWith(".a") }
            .map { sourceLibFolder.resolve(it) }

        var moduleFolderName = control.pkg
        for(sourceLib in sourceLibs) {
            moduleFolderName = sourceLib.nameWithoutExtension.substring(3)
        }
        val moduleFolder = aarBuildFolder.resolve("prefab/modules/$moduleFolderName")
        val destinationIncludes =
            if (sourceLibs.isNotEmpty()) {
                val platformFolder = moduleFolder.resolve("libs/android.$abi")
                platformFolder.mkdirs()
                for(sourceLib in sourceLibs) {
                    val destinationLib = platformFolder.resolve(sourceLib.name)
                    if (!destinationLib.exists()) {
                        sourceLib.copyTo(destinationLib)
                    }
                }

                // abi.json
                val abiJson = platformFolder.resolve("abi.json")
                platformFolder.mkdirs()
                abiJson.writeText("""
                    {"abi":"$abi","api":$api,"ndk":$ndk,"stl":"$stl"}
                """.trimIndent())

                // Includes at platform folder level
                platformFolder.resolve("include")
            } else {
                // Includes at module folder level
                moduleFolder.resolve("include")
            }
        val sourceIncludes = control.packageFolder.resolve("include")
        if (sourceIncludes.isDirectory) {
            destinationIncludes.mkdirs()
            sourceIncludes.copyRecursively(destinationIncludes, overwrite = false) { _, e->
                if (e !is FileAlreadyExistsException) throw e
                OnErrorAction.SKIP
            }
        }

        // module.json
        val moduleJson = aarBuildFolder.resolve("prefab/modules/module.json")
        for(sourceLib in sourceLibs) {
            val libraryName = sourceLib.nameWithoutExtension.substring(3)
            moduleJson.writeText(
                """
                {
                    "library_name": "$libraryName"
                }""".trimIndent())
        }

        // Skip empty packages
        if (!aarBuildFolder.exists()) {
            continue
        }
    }

    // Calculate the names of packages created
    val packageNames = mutableSetOf<String>()
    for(control in controls) {
        val aarBuildFolder = aarBuildFolder.resolve(control.name)
        if (!aarBuildFolder.exists()) continue
        packageNames.add(control.pkg)
    }

    // Add the per-package files if the folder exists
    for(control in controls) {
        val aarBuildFolder = aarBuildFolder.resolve(control.name)
        if (!aarBuildFolder.exists()) continue

        // prefab.json
        val prefabJson = aarBuildFolder.resolve("prefab/prefab.json")
        prefabJson.parentFile.mkdirs()
        val dependencies = control.depends
            ?.filter { packageNames.contains(it) }
            ?.joinToString { "\"${it}\"" }?.let {
                "\"dependencies\": [ $it ]"
            } ?: "\"dependencies\": [ ]"
        val version = if (isValidVersionForCMake(control.version)) control.version else "0.0.0"
        prefabJson.writeText("""
            {
                "schema_version": 1,
                "name": "${control.pkg}",
                "version": "$version",
                $dependencies
            }
        """.trimIndent())

        // AndroidManifest.xml
        val packageName = sanitize(
            namespace,
            control.pkg)
        aarBuildFolder.resolve("AndroidManifest.xml")
            .writeText(xml("manifest") {
                attributes(
                    "xmlns:android" to "http://schemas.android.com/apk/res/android",
                    "package" to packageName,
                    "android:versionCode" to 1,
                    "android:versionName" to "1.0"
                )

                "uses-sdk" {
                    attributes(
                        "android:minSdkVersion" to 16,
                        "android:targetSdkVersion" to 29
                    )
                }
            }.toString())
    }

    // Delete old aars
    for(control in controls) {
        val outputAar = aarFolder.resolve(control.name)
        if (outputAar.exists()) {
            outputAar.delete()
        }
    }

    // Create the aars
    val skipped = mutableSetOf<String>()
    for(control in controls) {
        val outputAar = aarFolder.resolve(control.name)
        if (outputAar.exists()) continue
        val aarBuildFolder = aarBuildFolder.resolve(control.name)
        if (!aarBuildFolder.exists()) {
            if (!skipped.contains(control.name)) {
                skipped.add(control.name)
                println("Skipping empty ${aarBuildFolder.name}")
            }
            continue
        }

        println(control.name)
        ZipOutputStream(BufferedOutputStream(FileOutputStream(outputAar))).use { zos ->
            aarBuildFolder.walkTopDown().forEach { file ->
                val zipFileName = file.absolutePath.removePrefix(aarBuildFolder.absolutePath).removePrefix("/")
                val entry = ZipEntry( "$zipFileName${(if (file.isDirectory) "/" else "" )}")
                zos.putNextEntry(entry)
                if (file.isFile) {
                    file.inputStream().use { it.copyTo(zos) }
                }
            }
        }
    }
}

private val javaKeywords = listOf("static", "assert")
fun sanitize(ns : String, pkg: String): String {
    val result = if (pkg.contains("-")) {
        val left = pkg.substringBefore("-")
        val right = pkg.substringAfter("-").replace("-","")
        "$ns.$left.$right"
    } else "$ns.$pkg"
    return result.split(".").map {
        if (javaKeywords.contains(it)) "_$it" else it
    }.joinToString(".")
}
internal fun isValidVersionForCMake(version: String): Boolean =
    Regex("""^\d+(\.\d+(\.\d+(\.\d+)?)?)?$""").matches(version)
