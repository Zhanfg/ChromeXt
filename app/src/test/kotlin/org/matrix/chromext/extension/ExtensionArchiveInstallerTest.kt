package org.matrix.chromext.extension

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtensionArchiveInstallerTest {
  private fun zip(vararg entries: Pair<String, String>): ByteArray {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { archive ->
      entries.forEach { (path, contents) ->
        archive.putNextEntry(ZipEntry(path))
        archive.write(contents.toByteArray(Charsets.UTF_8))
        archive.closeEntry()
      }
    }
    return bytes.toByteArray()
  }

  private val manifest =
      """{"manifest_version":3,"name":"Example extension","version":"1.0.0"}"""

  @Test
  fun importsValidManifestAndAssets() {
    val root = Files.createTempDirectory("chromext-test").toFile()
    try {
      val archive = zip("manifest.json" to manifest, "popup/index.html" to "<h1>Hi</h1>")
      val installed =
          ExtensionArchiveInstaller.installZip(ByteArrayInputStream(archive), root)
      assertEquals("Example extension", installed.name)
      assertEquals(3, installed.manifestVersion)
      assertEquals("1.0.0", installed.version)
      assertTrue(installed.directory.resolve("popup/index.html").isFile)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun refusesParentTraversalAndCleansUp() {
    val root = Files.createTempDirectory("chromext-test").toFile()
    try {
      val archive = zip("../escape.txt" to "bad", "manifest.json" to manifest)
      var rejected = false
      try {
        ExtensionArchiveInstaller.installZip(ByteArrayInputStream(archive), root)
      } catch (_: IllegalArgumentException) {
        rejected = true
      }
      assertTrue(rejected)
      assertFalse(root.parentFile.resolve("escape.txt").exists())
      assertTrue(root.listFiles().isNullOrEmpty())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun refusesMissingManifest() {
    val root = Files.createTempDirectory("chromext-test").toFile()
    try {
      var rejected = false
      try {
        ExtensionArchiveInstaller.installZip(
            ByteArrayInputStream(zip("popup.html" to "hello")), root)
      } catch (_: IllegalArgumentException) {
        rejected = true
      }
      assertTrue(rejected)
      assertTrue(root.listFiles().isNullOrEmpty())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun refusesOversizedUncompressedFiles() {
    val root = Files.createTempDirectory("chromext-test").toFile()
    try {
      val oversized = "x".repeat(16 * 1024 * 1024 + 1)
      var rejected = false
      try {
        ExtensionArchiveInstaller.installZip(
            ByteArrayInputStream(zip("manifest.json" to manifest, "large.dat" to oversized)),
            root)
      } catch (_: IllegalArgumentException) {
        rejected = true
      }
      assertTrue(rejected)
      assertTrue(root.listFiles().isNullOrEmpty())
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun refusesInvalidManifestVersion() {
    val root = Files.createTempDirectory("chromext-test").toFile()
    try {
      var rejected = false
      try {
        ExtensionArchiveInstaller.installZip(
            ByteArrayInputStream(
                zip("manifest.json" to """{"manifest_version":999,"name":"X","version":"1"}""")),
            root)
      } catch (_: IllegalArgumentException) {
        rejected = true
      }
      assertTrue(rejected)
      assertTrue(root.listFiles().isNullOrEmpty())
    } finally {
      root.deleteRecursively()
    }
  }
}
