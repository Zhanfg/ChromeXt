package org.matrix.chromext.extension

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.matrix.chromext.Chrome

/**
 * Called only from an authenticated, mapped CDP executionContextId, never from page JS.
 * Extension identity is derived from the server-side context map, not untrusted JSON.
 */
object ExtensionStorageBridge {
  private val ids =
      Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

  @Synchronized
  fun erase(extensionId: String) {
    if (!ids.matches(extensionId)) return
    Chrome.getContext().getSharedPreferences(
        "ChromeXtExtensionStore_$extensionId", Context.MODE_PRIVATE)
        .edit().clear().commit()
  }

  @Synchronized
  fun dispatch(extensionId: String, payload: String): JSONObject {
    try {
      require(ids.matches(extensionId) && ExtensionContentRuntime.isEnabled(extensionId)) {
        "Extension not enabled"
      }
      require(payload.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Request too large" }
      val folder = LocalFiles.listImported().firstOrNull { it.id == extensionId }?.directory
          ?: throw IllegalArgumentException("Installed extension not found")
      val manifest = JSONObject(java.io.File(folder, "manifest.json").readText(Charsets.UTF_8))
      val permissions = manifest.optJSONArray("permissions")
      require(permissions != null &&
          (0 until permissions.length()).any { permissions.getString(it) == "storage" }) {
        "Extension lacks storage permission"
      }
      val request = JSONObject(payload)
      val requestId = request.getInt("id")
      val action = request.getString("method")
      val args = request.optJSONArray("args") ?: JSONArray()
      val prefs = Chrome.getContext().getSharedPreferences(
          "ChromeXtExtensionStore_$extensionId", Context.MODE_PRIVATE)
      val saved = JSONObject(prefs.getString("state", "{}") ?: "{}")
      val (value, nextState) = ExtensionStorageEngine.execute(action, args, saved)
      if (nextState.toString() != saved.toString()) {
        check(prefs.edit().putString("state", nextState.toString()).commit()) {
          "Unable to persist storage"
        }
      }
      return JSONObject().put("id", requestId).put("ok", true).put("value", value)
    } catch (error: Exception) {
      val requestId = runCatching { JSONObject(payload).optInt("id", -1) }.getOrDefault(-1)
      return JSONObject().put("id", requestId).put("ok", false)
          .put("error", error.message ?: "Extension API rejected")
    }
  }
}
