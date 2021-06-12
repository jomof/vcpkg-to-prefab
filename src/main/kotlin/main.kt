import java.io.File
import java.io.FileNotFoundException

data class Control(
    val pkg : String,
    val version : String,
    val depends : List<String>?,
    val architecture : String,
    val abiHash : String,
    val description : String?,
    val type : String,
    val packageFolder : File
)

fun main(args: Array<String>) {
    println("Hello World!")
    val packagesFolder = File(args[0])
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
            it.readLines().map { line ->
                val index = line.indexOf(':')
                if (index == -1) error("$it")
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
                packageFolder = it.parentFile
            )
        }
        .filter { it.architecture.endsWith("-android") }

    for(control in controls) {
        val name = "${control.pkg}-${control.version}.aar"
        val aarBuildFolder = aarBuildFolder.resolve(name)
        val moduleFolder = aarBuildFolder.resolve("prefab/modules/${control.pkg}")
        val platformId = when(control.architecture) {
            "x64-android" -> "android.x86_64"
            "arm-android" -> "android.armeabi-v7a"
            "arm64-android" -> "android.arm64-v8a"
            "x86-android" -> "android.x86"
            else -> error(control.architecture)
        }
        val platformFolder = moduleFolder.resolve("libs/$platformId")

        // Includes
        val sourceIncludes = control.packageFolder.resolve("include")
        if (sourceIncludes.isDirectory) {
            val destIncludes = platformFolder.resolve("include")
            destIncludes.mkdirs()
            sourceIncludes.copyRecursively(destIncludes, overwrite = false) { _, e->
                if (e !is FileAlreadyExistsException) throw e
                OnErrorAction.SKIP
            }
        }

        // Libs
        val sourceLibs = control.packageFolder.resolve("lib")
        if (sourceLibs.isDirectory) {
            val destLibs = platformFolder
            destLibs.mkdirs()
            sourceLibs.copyRecursively(destLibs, overwrite = false) { _, e->
                if (e !is FileAlreadyExistsException) throw e
                OnErrorAction.SKIP
            }
        }

        // prefab.json
        val prefabJson = aarBuildFolder.resolve("prefab/prefab.json")
        val dependencies = control.depends
            ?.filter { !it.contains(":") }
            ?.joinToString { "\"${it}\"" }?.let {
            "\"dependencies\": [ $it ]"
        }
        prefabJson.writeText("""
            {
                "schema_version": 1,
                "name": "${control.pkg}",
                "version": "${control.version}",
                $dependencies
            }
        """.trimIndent())

        platformFolder.mkdirs()

        // module.json
        val moduleJson = aarBuildFolder.resolve("prefab/modules/module.json")
        moduleJson.writeText("""
            {
                "library_name": "${control.pkg}"
            }
        """.trimIndent())
    }

    println(controls)
    println(types)
}