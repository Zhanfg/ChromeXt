package org.matrix.chromext.extension

import android.content.Context
import org.matrix.chromext.Chrome
import org.matrix.chromext.utils.Log

/**
 * Explicitly enabled, content-scripts-only runtime.
 * It does not expose MV3 service workers or privileged chrome.* APIs.
 */
object ExtensionContentRuntime {
  private const val PREFS = "ChromeXtContentRuntime"
  @Volatile private var snapshot: List<CompiledContentScript>? = null

  fun isEnabled(id: String): Boolean =
      Chrome.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
          .getBoolean("enabled:$id", false)

  @Synchronized
  fun setEnabled(item: ImportedExtension, enabled: Boolean) {
    if (enabled) require(ExtensionContentCompiler.compile(item.directory, item.id).isNotEmpty())
    Chrome.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().putBoolean("enabled:${item.id}", enabled).apply()
    snapshot = null
  }

  @Synchronized
  fun forget(id: String) {
    Chrome.getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .edit().remove("enabled:$id").apply()
    snapshot = null
  }

  @Synchronized
  fun invalidate() {
    snapshot = null
  }

  private fun loaded(): List<CompiledContentScript> {
    snapshot?.let { return it }
    return synchronized(this) {
      snapshot ?: run {
        val found = mutableListOf<CompiledContentScript>()
        LocalFiles.listImported().filter { isEnabled(it.id) }.forEach { item ->
          runCatching { found.addAll(ExtensionContentCompiler.compile(item.directory, item.id)) }
              .onFailure { Log.ex(it, "Disabled invalid extension content: ${item.id}") }
        }
        found.toList().also { snapshot = it }
      }
    }
  }

  fun bundlesFor(url: String): List<CompiledContentScript> {
    if (!url.startsWith("https://") && !url.startsWith("http://")) return emptyList()
    return loaded().filter { it.appliesTo(url) }
  }
}
