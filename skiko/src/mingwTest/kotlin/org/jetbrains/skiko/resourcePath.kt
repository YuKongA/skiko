package org.jetbrains.skiko

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.windows.GetModuleFileNameW
import platform.windows.MAX_PATH
import platform.windows.WCHARVar

private const val RESOURCES_PATH = "src/commonTest/resources"

@OptIn(ExperimentalForeignApi::class)
private val KEXE_DIR: String by lazy {
    memScoped {
        val buffer = allocArray<WCHARVar>(MAX_PATH)
        GetModuleFileNameW(null, buffer, MAX_PATH.toUInt())
        val exePath = buffer.toKString().replace("\\", "/")
        exePath.substringBeforeLast("/")
    }
}

actual fun resourcePath(resourceId: String): String {
    val filePath = "$KEXE_DIR/../../../../$RESOURCES_PATH/$resourceId"
    // Normalize: resolve ".." segments
    val parts = filePath.split("/").toMutableList()
    val normalized = mutableListOf<String>()
    for (part in parts) {
        if (part == "..") {
            if (normalized.isNotEmpty()) normalized.removeLast()
        } else if (part != ".") {
            normalized.add(part)
        }
    }
    return normalized.joinToString("/")
}
