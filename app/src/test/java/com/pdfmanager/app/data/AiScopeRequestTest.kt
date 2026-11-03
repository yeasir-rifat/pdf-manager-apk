package com.pdfmanager.app.data

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiScopeRequestTest {

    private val line = AiScope.contextLine(AiScope.Page(12), 40, 12)!!

    @Test fun contextLineIsAddedToTheSystemTextInEnglish() {
        val t = GeminiRequest.systemText(AiPrefs.LANG_EN, AiPrefs.LEN_NORMAL, line)
        assertTrue(t.contains("Always answer in English."))
        assertTrue(t.endsWith(line))
    }

    @Test fun contextLineIsAddedToTheSystemTextInBangla() {
        val t = GeminiRequest.systemText(AiPrefs.LANG_BN, AiPrefs.LEN_NORMAL, line)
        assertTrue(t.contains("Always answer in Bangla."))
        assertTrue(t.endsWith(line))
    }

    @Test fun noContextLeavesTheSystemTextAsBefore() {
        val plain = GeminiRequest.systemText(AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL)
        assertEquals(plain, GeminiRequest.systemText(AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, null))
        assertEquals(plain, GeminiRequest.systemText(AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL, "  "))
    }

    @Test fun contextReachesTheRequestBodyWithBanglaQuestion() {
        val out = ByteArrayOutputStream()
        val q = "\u098F\u0987 \u09AA\u09C3\u09B7\u09CD\u09A0\u09BE\u09B0 \u09AC\u09CD\u09AF\u09BE\u0996\u09CD\u09AF\u09BE \u09A6\u09BE\u0993"
        GeminiRequest.write(out, listOf(GeminiRequest.Turn(GeminiRequest.USER, q)), AiPrefs.LANG_BN, AiPrefs.LEN_SHORT, line + " " + AiScope.PAGE_REF_RULE)
        val root = DriveJson.parseObject(out.toString("UTF-8"))!!
        @Suppress("UNCHECKED_CAST")
        val sys = ((root["system_instruction"] as Map<String, Any?>)["parts"] as List<Map<String, Any?>>)[0]["text"] as String
        assertTrue(sys.contains("page 12 of a 40 page document"))
        assertTrue(sys.contains("[p. N]"))
        assertFalse(sys.contains('\u2014'))
        @Suppress("UNCHECKED_CAST")
        val text = ((root["contents"] as List<Map<String, Any?>>)[0]["parts"] as List<Map<String, Any?>>)[0]["text"]
        assertEquals(q, text)
    }

    @Test fun writeWithoutContextIsTheSameBodyAsBefore() {
        val turns = listOf(GeminiRequest.Turn(GeminiRequest.USER, "Say OK"))
        val a = ByteArrayOutputStream().also { GeminiRequest.write(it, turns, AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL) }
        assertEquals(GeminiRequest.body("Say OK", AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL), a.toString("UTF-8"))
    }
}
