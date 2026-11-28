package org.matrix.chromext.extension

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ExtensionContentCompilerTest {
  @Test fun matchesHostAndRejectsChromePages() {
    assertTrue(ExtensionMatchPattern.matches("*://*.example.com/*", "https://example.com/"))
    assertTrue(ExtensionMatchPattern.matches("*://*.example.com/*", "http://a.example.com/path"))
    assertFalse(ExtensionMatchPattern.matches("*://*.example.com/*", "https://evil-example.com/"))
    assertFalse(ExtensionMatchPattern.matches("*://*.example.com/*", "https://example.com.evil.test/"))
    assertFalse(ExtensionMatchPattern.matches("<all_urls>", "chrome://settings/"))
    assertFalse(ExtensionMatchPattern.matches("<all_urls>", "file:///tmp/index.html"))
    assertFalse(ExtensionMatchPattern.matches("<all_urls>", "https://name:pw@example.com/"))
  }

  @Test fun buildsExecutableContentBundle() {
    val dir = Files.createTempDirectory("mv3-test").toFile()
    try {
      dir.resolve("manifest.json").writeText(
          """{"manifest_version":3,"name":"Smoke","version":"1","content_scripts":[{"matches":["https://example.com/*"],"js":["script.js"],"css":["style.css"]}]}""")
      dir.resolve("script.js").writeText("document.documentElement.dataset.smoke='passed';")
      dir.resolve("style.css").writeText("html{outline:1px solid green}")
      val bundle = ExtensionContentCompiler.compile(dir, "test").single()
      assertTrue(bundle.appliesTo("https://example.com/page"))
      assertFalse(bundle.appliesTo("https://other.example/page"))
      assertTrue(bundle.code().contains("dataset.smoke='passed'"))
      assertTrue(bundle.code().contains("outline:1px solid green"))
    } finally { dir.deleteRecursively() }
  }

  @Test fun blocksTraversalAndUnsupportedBackgroundOnly() {
    val dir = Files.createTempDirectory("mv3-test").toFile()
    try {
      dir.resolve("manifest.json").writeText(
          """{"manifest_version":3,"name":"Traversal","version":"1","content_scripts":[{"matches":["<all_urls>"],"js":["../outside.js"]}]}""")
      assertThrows(IllegalArgumentException::class.java) {
        ExtensionContentCompiler.compile(dir, "invalid")
      }
      dir.resolve("manifest.json").writeText(
          """{"manifest_version":3,"name":"Background","version":"1","background":{"service_worker":"bg.js"}}""")
      assertThrows(IllegalArgumentException::class.java) {
        ExtensionContentCompiler.compile(dir, "background")
      }
    } finally { dir.deleteRecursively() }
  }

  @Test fun blocksUnsupportedPermissionsEvenWithContentScript() {
    val root = Files.createTempDirectory("mv3-permissions").toFile()
    try {
      root.resolve("manifest.json").writeText(
          """{"manifest_version":3,"name":"Privileged","version":"1","permissions":["tabs"],"content_scripts":[{"matches":["<all_urls>"],"js":["script.js"]}]}""")
      root.resolve("script.js").writeText("console.log('hello')")
      assertThrows(IllegalArgumentException::class.java) {
        ExtensionContentCompiler.compile(root, "privileged")
      }
    } finally {
      root.deleteRecursively()
    }
  }

  @Test fun blocksUnsupportedFrames() {
    val dir = Files.createTempDirectory("mv3-test").toFile()
    try {
      dir.resolve("manifest.json").writeText(
          """{"manifest_version":3,"name":"Frame","version":"1","content_scripts":[{"matches":["<all_urls>"],"all_frames":true,"js":["script.js"]}]}""")
      dir.resolve("script.js").writeText("console.log('test')")
      assertThrows(IllegalArgumentException::class.java) {
        ExtensionContentCompiler.compile(dir, "frame")
      }
    } finally { dir.deleteRecursively() }
  }
}
