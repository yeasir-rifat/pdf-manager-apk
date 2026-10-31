package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveJsonTest {

    @Test fun quoteEscapesSpecialCharacters() {
        assertEquals("\"a\\\"b\\\\c\\nd\"", DriveJson.quote("a\"b\\c\nd"))
        assertEquals("\"\\u0001\"", DriveJson.quote("\u0001"))
        assertEquals("\"বাংলা\"", DriveJson.quote("বাংলা"))
    }

    @Test fun quotedTextReadsBackTheSame() {
        val s = "x\"y\\z\n\t\u0002 বাংলা"
        assertEquals(s, DriveJson.parse(DriveJson.quote(s)))
    }

    @Test fun parsesNestedValues() {
        val v = DriveJson.parseObject("""{"a":[1,2.5,{"b":null}],"c":true,"d":"\u0041\/x"}""")!!
        assertEquals(true, v["c"])
        assertEquals("A/x", v["d"])
        val a = v["a"] as List<*>
        assertEquals(1.0, a[0])
        assertEquals(2.5, a[1])
        assertTrue((a[2] as Map<*, *>).containsKey("b"))
    }

    @Test fun badJsonGivesNullNotACrash() {
        assertNull(DriveJson.parseObject(""))
        assertNull(DriveJson.parseObject("{"))
        assertNull(DriveJson.parseObject("[1]"))
        assertNull(DriveJson.parseObject("{\"a\":}"))
        assertNull(DriveJson.parseObject("<html>502</html>"))
        assertNull(DriveJson.stringField("not json", "id"))
    }

    @Test fun deepNestingIsRefused() {
        assertNull(DriveJson.parseObject("{\"a\":" + "[".repeat(100) + "]".repeat(100) + "}"))
    }

    @Test fun stringFieldReadsId() {
        assertEquals("abc123", DriveJson.stringField("""{"id":"abc123","x":1}""", "id"))
        assertNull(DriveJson.stringField("""{"id":5}""", "id"))
    }

    @Test fun errorReasonFromErrorsList() {
        val body = """{"error":{"code":403,"message":"x","errors":[{"domain":"global","reason":"exportSizeLimitExceeded"}]}}"""
        assertEquals("exportSizeLimitExceeded", DriveJson.errorReason(body))
    }

    @Test fun errorReasonFromDetails() {
        val body = """{"error":{"code":403,"status":"PERMISSION_DENIED","details":[{"@type":"x","reason":"SERVICE_DISABLED"}]}}"""
        assertEquals("SERVICE_DISABLED", DriveJson.errorReason(body))
    }

    @Test fun errorReasonMissingOrGarbage() {
        assertNull(DriveJson.errorReason("""{"error":{"code":500}}"""))
        assertNull(DriveJson.errorReason("oops"))
        assertNull(DriveJson.errorReason(""))
    }

    @Test fun fileIdsSkipEntriesWithoutId() {
        assertEquals(listOf("a1", "b2"), DriveJson.fileIds("""{"files":[{"id":"a1"},{"x":1},{"id":"b2"}]}"""))
        assertEquals(emptyList<String>(), DriveJson.fileIds("""{"files":[]}"""))
        assertEquals(emptyList<String>(), DriveJson.fileIds("nope"))
    }
}
