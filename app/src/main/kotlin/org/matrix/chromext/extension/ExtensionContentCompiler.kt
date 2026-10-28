package org.matrix.chromext.extension

import java.io.File
import org.json.JSONObject

data class CompiledContentScript(
    val extensionId: String,
    val ruleIndex: Int,
    val matches: List<String>,
    val excludes: List<String>,
    val css: List<String>,
    val javascript: List<String>,
) {
  fun appliesTo(url: String): Boolean =
      matches.any { ExtensionMatchPattern.matches(it, url) } &&
          excludes.none { ExtensionMatchPattern.matches(it, url) }

  /** Main-world approximation, NOT native isolated-world WebExtensions execution. */
  fun code(): String {
    val key = JSONObject.quote("chromext-content-$extensionId-$ruleIndex")
    val style = if (css.isEmpty()) "" else
        "const style=document.createElement('style');style.textContent=" +
            JSONObject.quote(css.joinToString("\n")) +
            ";(document.head||document.documentElement).appendChild(style);"
    val source = javascript.joinToString("\n;\n")
    return """(()=>{const key=$key;
      const run=()=>{if(!document.documentElement)return;
        const marks=globalThis.__chromeXtContentScripts||(globalThis.__chromeXtContentScripts=Object.create(null));
        if(marks[key])return;marks[key]=true;
        try{$style
        (function(){${source}
        }).call(globalThis);}catch(e){console.error('ChromeXt content script',e);}
      };
      if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run,{once:true});
      else run();
    })();
    //# sourceURL=local://ChromeXt/content/$extensionId/$ruleIndex"""
  }
}

object ExtensionContentCompiler {
  private const val MAX_SCRIPT_BYTES = 512L * 1024
  private const val MAX_TOTAL_BYTES = 1024L * 1024
  private const val MAX_RULES = 32

  /** Opt-in subset of manifest v3 declarative content_scripts; no privileged APIs. */
  fun compile(folder: File, id: String): List<CompiledContentScript> {
    val root = folder.canonicalFile
    require(root.isDirectory) { "Extension directory missing" }
    val manifestFile = File(root, "manifest.json")
    require(manifestFile.isFile && manifestFile.length() <= MAX_SCRIPT_BYTES) {
      "Manifest missing or too large"
    }
    val manifest = JSONObject(manifestFile.readText(Charsets.UTF_8))
    require(manifest.optInt("manifest_version") == 3) { "Requires manifest V3" }
    val rules = manifest.optJSONArray("content_scripts")
        ?: throw IllegalArgumentException("No content_scripts in manifest")
    require(rules.length() in 1..MAX_RULES) { "Invalid content_scripts count" }
    var totalSize = 0L

    fun resources(rule: JSONObject, type: String): List<String> {
      val arr = rule.optJSONArray(type) ?: return emptyList()
      require(arr.length() <= 32) { "Too many resource files" }
      return (0 until arr.length()).map { i ->
        val path = arr.getString(i)
        require(path.isNotBlank() && !path.startsWith('/') && !path.contains('\\') &&
            path.split('/').all { it.isNotBlank() && it != ".." && it != "." }) {
          "Invalid extension resource path"
        }
        val file = File(root, path).canonicalFile
        require(file.path.startsWith(root.path + File.separator) &&
            file.isFile && file.length() <= MAX_SCRIPT_BYTES) {
          "Missing or unsafe resource"
        }
        totalSize += file.length()
        require(totalSize <= MAX_TOTAL_BYTES) { "Script resources exceed 1 MiB" }
        file.readText(Charsets.UTF_8)
      }
    }

    fun patterns(rule: JSONObject, field: String): List<String> {
      val array = rule.optJSONArray(field) ?: return emptyList()
      require(array.length() <= 128) { "Too many match patterns" }
      return (0 until array.length()).map { array.getString(it) }
    }

    val compiled = mutableListOf<CompiledContentScript>()
    for (index in 0 until rules.length()) {
      val rule = rules.getJSONObject(index)
      require(rule.optString("run_at", "document_idle") == "document_idle") {
        "Only document_idle supported"
      }
      require(!rule.optBoolean("all_frames")) { "all_frames unsupported" }
      val matches = patterns(rule, "matches")
      require(matches.isNotEmpty()) { "Missing matches" }
      val css = resources(rule, "css")
      val js = resources(rule, "js")
      require(css.isNotEmpty() || js.isNotEmpty()) { "No content script resources" }
      compiled.add(CompiledContentScript(id, index, matches,
          patterns(rule, "exclude_matches"), css, js))
    }
    return compiled
  }
}
