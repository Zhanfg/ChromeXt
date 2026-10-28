package org.matrix.chromext.extension

import org.json.JSONObject
import org.matrix.chromext.Chrome
import org.matrix.chromext.devtools.DevToolClient
import org.matrix.chromext.hook.UserScriptHook
import org.matrix.chromext.utils.Log

/**
 * CDP isolated-world extension execution with a restricted, context-authenticated RPC bridge.
 * The site cannot see the JS globals of this world. The page DOM remains shared.
 * This is not Chromium's complete WebExtensions runtime.
 */
object ExtensionIsolatedRuntime {
  private const val SETUP_TIMEOUT_MS = 10_000
  private val pending = mutableSetOf<String>()
  private val active = mutableMapOf<String, DevToolClient>()

  fun inject(url: String, tab: Any?, frameId: String?) {
    if (!UserScriptHook.isInit || tab == null || frameId != null) return
    val bundles = runCatching { ExtensionContentRuntime.bundlesFor(url) }
        .onFailure { Log.ex(it, "Cannot resolve enabled extensions") }
        .getOrDefault(emptyList())
    if (bundles.isEmpty()) return

    val tabId = runCatching { Chrome.getTabId(tab, url) }.getOrNull() ?: return
    val pendingKey = "$tabId:$url"
    synchronized(pending) { if (!pending.add(pendingKey)) return }

    Chrome.IO.execute {
      var client: DevToolClient? = null
      try {
        Chrome.wakeUpDevTools(2)
        val session = DevToolClient(tabId, "extensions")
        client = session
        if (session.isClosed()) throw IllegalStateException("Cannot open Chrome CDP endpoint")
        session.soTimeout = SETUP_TIMEOUT_MS
        synchronized(active) {
          active.remove(tabId)?.close()
          active[tabId] = session
        }

        val folders = LocalFiles.listImported().associateBy { it.id }
        val contexts = mutableMapOf<Int, String>()
        var current = 0
        var frame = ""
        var executionContext = 0
        var installed = false
        var replyCommandId = 100

        session.command(1, "Page.enable", JSONObject())
        session.command(7, "Runtime.enable", JSONObject())
        session.command(2, "Page.getFrameTree", JSONObject())

        session.listen { message ->
          if (message.has("id")) {
            when (message.optInt("id")) {
              2 -> {
                frame = ExtensionCdpProtocol.mainFrame(message)
                    ?: throw IllegalStateException("CDP main frame missing")
                session.command(
                    3, "Page.createIsolatedWorld",
                    ExtensionCdpProtocol.isolatedWorldParams(frame, bundles[current].extensionId))
              }
              3 -> {
                executionContext = ExtensionCdpProtocol.contextId(message)
                    ?: throw IllegalStateException("CDP isolated world creation failed")
                contexts[executionContext] = bundles[current].extensionId
                session.command(
                    5, "Runtime.addBinding",
                    JSONObject()
                        .put("name", ExtensionApiBootstrap.BINDING)
                        .put("executionContextId", executionContext))
              }
              5 -> {
                if (ExtensionCdpProtocol.hasError(message))
                    throw IllegalStateException("Cannot register isolated extension API binding")
                val bundle = bundles[current]
                val folder = folders[bundle.extensionId]?.directory
                    ?: throw IllegalStateException("Enabled extension missing on disk")
                val bootstrap = ExtensionApiBootstrap.source(bundle.extensionId, folder)
                session.command(
                    4, "Runtime.evaluate",
                    ExtensionCdpProtocol.evaluationParams(
                        executionContext, bootstrap + "\n" + bundle.code()))
              }
              4 -> {
                if (ExtensionCdpProtocol.hasError(message))
                    Log.e("CDP execution failed for ${bundles[current].extensionId}")
                current++
                if (current < bundles.size) {
                  session.command(
                      3, "Page.createIsolatedWorld",
                      ExtensionCdpProtocol.isolatedWorldParams(
                          frame, bundles[current].extensionId))
                } else {
                  installed = true
                  // Continue listening for chrome.storage RPC calls for the life of the page.
                  // Navigation closes this binding and a new document gets a fresh world.
                  session.soTimeout = 0
                  synchronized(pending) { pending.remove(pendingKey) }
                }
              }
            }
          } else {
            when (message.optString("method")) {
              "Runtime.bindingCalled" -> {
                val detail = message.optJSONObject("params") ?: return@listen
                if (detail.optString("name") != ExtensionApiBootstrap.BINDING) return@listen
                val contextId = detail.optInt("executionContextId")
                val authorizedId = contexts[contextId] ?: return@listen
                val result = ExtensionStorageBridge.dispatch(
                    authorizedId, detail.optString("payload"))
                session.command(
                    replyCommandId++, "Runtime.evaluate",
                    ExtensionCdpProtocol.evaluationParams(
                        contextId, "globalThis.__chromeXtReply(" + result.toString() + ")"))
              }
              "Runtime.executionContextsCleared" -> if (installed) session.close()
              "Page.frameNavigated" -> {
                val navigated = message.optJSONObject("params")
                    ?.optJSONObject("frame")?.optString("id")
                if (installed && navigated == frame) session.close()
              }
              "Inspector.detached" -> session.close()
            }
          }
        }
      } catch (error: Throwable) {
        Log.ex(error, "Extension CDP runtime disconnected (no unsafe fallback)")
      } finally {
        runCatching { client?.close() }
        synchronized(pending) { pending.remove(pendingKey) }
        synchronized(active) {
          if (active[tabId] === client) active.remove(tabId)
        }
      }
    }
  }
}
