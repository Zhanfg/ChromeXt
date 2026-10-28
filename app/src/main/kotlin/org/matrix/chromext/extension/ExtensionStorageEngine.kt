package org.matrix.chromext.extension

import org.json.JSONArray
import org.json.JSONObject

/** Pure JSON semantics for the supported chrome.storage.local subset. */
object ExtensionStorageEngine {
  private const val MAX_BYTES = 1024 * 1024

  fun execute(method: String, args: JSONArray, state: JSONObject): Pair<JSONObject, JSONObject> {
    val store = JSONObject(state.toString())
    val result = when (method) {
      "storage.local.get" -> {
        when (val keys = args.opt(0)) {
          null, JSONObject.NULL -> JSONObject(store.toString())
          is String -> JSONObject().apply { if (store.has(keys)) put(keys, store.get(keys)) }
          is JSONArray -> JSONObject().apply {
            for (i in 0 until keys.length()) {
              val k = keys.getString(i)
              if (store.has(k)) put(k, store.get(k))
            }
          }
          is JSONObject -> JSONObject().apply {
            val it = keys.keys()
            while (it.hasNext()) {
              val k = it.next()
              put(k, if (store.has(k)) store.get(k) else keys.get(k))
            }
          }
          else -> throw IllegalArgumentException("Invalid storage keys")
        }
      }
      "storage.local.set" -> {
        val values = args.optJSONObject(0)
            ?: throw IllegalArgumentException("storage.set requires an object")
        val iterator = values.keys()
        while (iterator.hasNext()) {
          val k = iterator.next()
          require(k.length <= 1024)
          store.put(k, values.get(k))
        }
        JSONObject()
      }
      "storage.local.remove" -> {
        when (val keys = args.opt(0)) {
          is String -> store.remove(keys)
          is JSONArray -> for (i in 0 until keys.length()) store.remove(keys.getString(i))
          else -> throw IllegalArgumentException("storage.remove requires keys")
        }
        JSONObject()
      }
      "storage.local.clear" -> {
        val keys = mutableListOf<String>()
        val it = store.keys()
        while (it.hasNext()) keys.add(it.next())
        keys.forEach { store.remove(it) }
        JSONObject()
      }
      else -> throw IllegalArgumentException("Unsupported extension API")
    }
    require(store.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
      "Extension storage exceeds 1 MiB"
    }
    return result to store
  }
}
