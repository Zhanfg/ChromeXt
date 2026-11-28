package org.matrix.chromext.extension

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONObject

data class ImportedExtension(
    val id: String,
    val name: String,
    val version: String,
    val manifestVersion: Int,
    val directory: File,
)

/**
 * Validates and stages a locally supplied, unpacked ZIP extension in the browser's extension
 * directory. Importing a package does not grant permissions, register Chrome APIs, or execute it.
 *
 * The caller must supply the target browser's own Context and open the SAF stream in that process.
 */
object ExtensionArchiveInstaller {
  private const val MAX_ENTRIES = 1024
  private const val MAX_FILE_BYTES = 16L * 1024 * 1024
  private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
  private const val MAX_MANIFEST_BYTES = 512L * 1024

  fun installZip(source: InputStream, extensionsRoot: File): ImportedExtension {
    val root = extensionsRoot.canonicalFile
    require(root.isDirectory || root.mkdirs()) { "Cannot create extension directory" }
    val staging = File(root, ".staging-${UUID.randomUUID()}")
    check(staging.mkdir()) { "Cannot create staging directory" }

    var committed = false
    try {
      var count = 0
      var totalBytes = 0L
      val seenPaths = mutableSetOf<String>()
      ZipInputStream(BufferedInputStream(source)).use { archive ->
        val buffer = ByteArray(8192)
        while (true) {
          val entry = archive.nextEntry ?: break
          val entryName = entry.name
          val normalized = entryName.trimEnd('/')
          require(
              normalized.isNotBlank() &&
                  !entryName.startsWith('/') &&
                  !entryName.contains('\\') &&
                  !entryName.contains('\u0000') &&
                  normalized.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
          ) { "Unsafe archive entry" }

          require(seenPaths.add(normalized.lowercase(Locale.ROOT))) {
            "Duplicate archive entry"
          }
          count++
          require(count <= MAX_ENTRIES) { "Too many archive entries" }

          val destination = File(staging, normalized).canonicalFile
          require(destination.path.startsWith(staging.canonicalPath + File.separator)) {
            "Archive entry escapes destination"
          }
          if (entry.isDirectory) {
            require(destination.isDirectory || destination.mkdirs()) {
              "Cannot create archive directory"
            }
          } else {
            require(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs()) {
              "Cannot create parent directory"
            }
            require(!destination.exists()) { "Archive entry already exists" }
            var fileBytes = 0L
            destination.outputStream().use { output ->
              while (true) {
                val bytesRead = archive.read(buffer)
                if (bytesRead < 0) break
                fileBytes += bytesRead
                totalBytes += bytesRead
                require(fileBytes <= MAX_FILE_BYTES && totalBytes <= MAX_TOTAL_BYTES) {
                  "Archive exceeds the extraction limit"
                }
                output.write(buffer, 0, bytesRead)
              }
            }
          }
          archive.closeEntry()
        }
      }

      require(count > 0) { "Empty extension archive" }
      val manifestFile = File(staging, "manifest.json")
      require(manifestFile.isFile && manifestFile.length() <= MAX_MANIFEST_BYTES) {
        "A root-level manifest.json is required"
      }
      val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
      val manifestVersion = manifest.optInt("manifest_version")
      require(manifestVersion == 2 || manifestVersion == 3) {
        "Unsupported manifest_version"
      }
      val name = manifest.optString("name").trim()
      val version = manifest.optString("version").trim()
      require(name.isNotEmpty() && version.isNotEmpty()) {
        "Extension name and version are required"
      }

      // A local, randomly assigned directory ID is NOT a Chrome Web Store extension ID.
      val id = UUID.randomUUID().toString()
      val installedDir = File(root, id)
      check(!installedDir.exists() && staging.renameTo(installedDir)) {
        "Cannot commit imported extension"
      }
      committed = true
      return ImportedExtension(id, name, version, manifestVersion, installedDir)
    } finally {
      if (!committed) staging.deleteRecursively()
    }
  }
}
