package org.matrix.chromext.extension

import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.devtools.DevToolClient
import org.matrix.chromext.hook.UserScriptHook
import org.matrix.chromext.utils.Log

/**
 * Isolated-world MV3 content script execution via Chromium DevTools Protocol.
 *
 * Page.createIsolatedWorld provides a separate JS global while sharing the page DOM.
 * No unsafe page-main-world fallback is allowed when the CDP endpoint is unavailable.
 * This is a partial extension runtime, not native WebExtensions.
 */
object ExtensionIsolatedRuntime {
  private const val SESSION_TIMEOUT_MS = 8_000
  private val pending = mutableSetOf<String>()

  fun inject(url: String, tab: Any?, frameId: String?) {
    if (!UserScriptHook.isInit || tab == null || frameId != null) return
    val matches =
        runCatching { ExtensionContentRuntime.bundlesFor(url) }
            .onFailure { Log.ex(it, "Cannot resolve enabled extension scripts") }
            .getOrDefault(emptyList())
    if (matches.isEmpty()) return

    val tabId = runCatching { Chrome.getTabId(tab, url) }.getOrNull() ?: return
    val key = "$tabId:$url"
    synchronized(pending) {
      if (!pending.add(key)) return
    }

    Chrome.IO.execute {
      var client: DevToolClient? = null
      try {
        Chrome.wakeUpDevTools(2)
        val session = DevToolClient(tabId, "extensions")
        client = session
        if (session.isClosed()) throw IllegalStateException("No CDP session")
        session.soTimeout = SESSION_TIMEOUT_MS

        var current = 0
        var frame = ""
        session.command(1, "Page.enable", JSONObject())
        session.command(2, "Page.getFrameTree", JSONObject())

        session.listen { message ->
          if (message.has("id")) {
            when (message.optInt("id")) {
              2 -> {
                frame = ExtensionCdpProtocol.mainFrame(message)
                    ?: throw IllegalStateException("No main frame returned")
                session.command(
                    3, "Page.createIsolatedWorld",
                    ExtensionCdpProtocol.isolatedWorldParams(frame, matches[current].extensionId))
              }
              3 -> {
                val context = ExtensionCdpProtocol.contextId(message)
                    ?: throw IllegalStateException("Isolated world unavailable")
                session.command(
                    4, "Runtime.evaluate",
                    ExtensionCdpProtocol.evaluationParams(context, matches[current].code()))
              }
              4 -> {
                if (ExtensionCdpProtocol.hasError(message)) {
                  Log.e("Isolated-world execution error for ${matches[current].extensionId}")
                }
                current++
                if (current >= matches.size) {
                  session.close()
                } else {
                  session.command(
                      3, "Page.createIsolatedWorld",
                      ExtensionCdpProtocol.isolatedWorldParams(frame, matches[current].extensionId))
                }
              }
            }
          }
        }
      } catch (failure: Throwable) {
        Log.ex(failure, "Isolated extension execution failed; no main-world fallback")
      } finally {
        runCatching { client?.close() }
        synchronized(pending) { pending.remove(key) }
      }
    }
  }
}
