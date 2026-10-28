package org.matrix.chromext.extension

import org.json.JSONObject

/** Separates CDP command/response parsing from Android socket IO for unit testing. */
object ExtensionCdpProtocol {
  fun mainFrame(response: JSONObject): String? =
      response.optJSONObject("result")
          ?.optJSONObject("frameTree")
          ?.optJSONObject("frame")
          ?.optString("id")
          ?.takeIf { it.isNotBlank() }

  fun contextId(response: JSONObject): Int? =
      response.optJSONObject("result")?.optInt("executionContextId", 0)?.takeIf { it > 0 }

  fun hasError(response: JSONObject): Boolean =
      response.has("error") || response.optJSONObject("result")?.has("exceptionDetails") == true

  fun isolatedWorldParams(frameId: String, extensionId: String): JSONObject =
      JSONObject()
          .put("frameId", frameId)
          .put("worldName", "ChromeXt.Extension." + extensionId)
          .put("grantUniveralAccess", false)

  fun evaluationParams(contextId: Int, source: String): JSONObject =
      JSONObject()
          .put("contextId", contextId)
          .put("expression", source)
          .put("returnByValue", true)
          .put("awaitPromise", false)
}
