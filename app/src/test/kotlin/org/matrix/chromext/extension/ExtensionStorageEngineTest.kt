package org.matrix.chromext.extension

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionStorageEngineTest {
  @Test fun storesAndGetsValues() {
    val (_, updated) = ExtensionStorageEngine.execute(
        "storage.local.set", JSONArray().put(JSONObject().put("count", 3)), JSONObject())
    val (fetched, unchanged) = ExtensionStorageEngine.execute(
        "storage.local.get", JSONArray().put("count"), updated)
    assertEquals(3, fetched.getInt("count"))
    assertEquals(updated.toString(), unchanged.toString())
    val (_, removed) = ExtensionStorageEngine.execute(
        "storage.local.remove", JSONArray().put("count"), updated)
    assertEquals(0, removed.length())
  }

  @Test fun getsDefaultsAndClearsStorage() {
    val state = JSONObject().put("a", 1)
    val (value, _) = ExtensionStorageEngine.execute(
        "storage.local.get", JSONArray().put(JSONObject().put("a", 2).put("b", 7)), state)
    assertEquals(1, value.getInt("a"))
    assertEquals(7, value.getInt("b"))
    val (_, clear) = ExtensionStorageEngine.execute("storage.local.clear", JSONArray(), state)
    assertEquals(0, clear.length())
  }

  @Test fun rejectsOversizedWritesAndUnknownMethods() {
    assertThrows(IllegalArgumentException::class.java) {
      ExtensionStorageEngine.execute(
          "storage.local.set",
          JSONArray().put(JSONObject().put("large", "a".repeat(1024*1024+1))),
          JSONObject())
    }
    assertThrows(IllegalArgumentException::class.java) {
      ExtensionStorageEngine.execute("tabs.create", JSONArray(), JSONObject())
    }
  }
}
