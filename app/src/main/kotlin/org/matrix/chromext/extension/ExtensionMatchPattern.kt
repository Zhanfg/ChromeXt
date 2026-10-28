package org.matrix.chromext.extension

import java.net.URI

/** HTTP(S) subset of Chrome MV3 match patterns; never matches browser-internal URLs. */
object ExtensionMatchPattern {
  fun matches(pattern: String, address: String): Boolean {
    val url = runCatching { URI(address) }.getOrNull() ?: return false
    val scheme = url.scheme?.lowercase() ?: return false
    val host = url.host?.lowercase() ?: return false
    if (scheme !in listOf("http", "https") || url.rawUserInfo != null) return false
    if (pattern == "<all_urls>") return true
    val parts = Regex("""^(\*|http|https)://([^/]+)/(.*)$""").matchEntire(pattern) ?: return false
    val ruleScheme = parts.groupValues[1]
    val ruleHost = parts.groupValues[2].lowercase()
    if (ruleScheme != "*" && ruleScheme != scheme) return false
    if (ruleHost.contains(':')) return false
    val validHost = when {
      ruleHost == "*" -> true
      ruleHost.startsWith("*.") -> {
        val domain = ruleHost.removePrefix("*.")
        domain.isNotEmpty() && !domain.contains('*') &&
            (host == domain || host.endsWith(".$domain"))
      }
      ruleHost.contains('*') -> false
      else -> host == ruleHost
    }
    if (!validHost) return false
    val path = (url.rawPath?.ifEmpty { "/" } ?: "/") +
        (url.rawQuery?.let { "?$it" } ?: "")
    val rulePath = "/" + parts.groupValues[3]
    val regex = "^" + rulePath.split("*").joinToString(".*") { Regex.escape(it) } + "$"
    return runCatching { Regex(regex).matches(path) }.getOrDefault(false)
  }
}
