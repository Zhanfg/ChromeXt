package org.matrix.chromext.extension

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ExtensionCdpProtocolTest {
  @Test fun extractsMainFrameAndContext() {
    assertEquals(
        "frame42",
        ExtensionCdpProtocol.mainFrame(
            JSONObject("""{"id":2,"result":{"frameTree":{"frame":{"id":"frame42"}}}}""")))
    assertEquals(
        123,
        ExtensionCdpProtocol.contextId(
            JSONObject("""{"id":3,"result":{"executionContextId":123}}""")))
  }

  @Test fun detectsFailures() {
    assertNull(ExtensionCdpProtocol.mainFrame(JSONObject("""{"id":2,"result":{}}""")))
    assertNull(ExtensionCdpProtocol.contextId(JSONObject("""{"id":3,"error":{"message":"no world"}}""")))
    assertTrue(ExtensionCdpProtocol.hasError(JSONObject("""{"id":4,"error":{"message":"bad"}}""")))
    assertTrue(ExtensionCdpProtocol.hasError(
        JSONObject("""{"id":4,"result":{"exceptionDetails":{"text":"SyntaxError"}}}""")))
  }

  @Test fun passesWorldAndContextIdentifiers() {
    val world = ExtensionCdpProtocol.isolatedWorldParams("frame", "extension-1")
    assertEquals("frame", world.getString("frameId"))
    assertTrue(world.getString("worldName").contains("extension-1"))
    assertFalse(world.getBoolean("grantUniveralAccess"))
    val eval = ExtensionCdpProtocol.evaluationParams(7, "document.body.dataset.test=1")
    assertEquals(7, eval.getInt("contextId"))
    assertEquals("document.body.dataset.test=1", eval.getString("expression"))
  }
}
