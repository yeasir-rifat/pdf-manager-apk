package com.pdfmanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeminiRequestTest {

    private fun parse(json: String) = DriveJson.parseObject(json)!!

    private fun firstText(obj: Map<String, Any?>, key: String): String {
        val parts = when (val v = obj[key]) {
            is Map<*, *> -> v["parts"] as List<*>
            else -> error("missing $key")
        }
        return (parts[0] as Map<*, *>)["text"] as String
    }

    @Test fun bodyIsValidJsonWithTheExpectedShape() {
        val o = parse(GeminiRequest.body("Say OK", AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL))
        assertTrue(firstText(o, "system_instruction").isNotBlank())
        val contents = o["contents"] as List<*>
        assertEquals(1, contents.size)
        val turn = contents[0] as Map<*, *>
        assertEquals("user", turn["role"])
        assertEquals("Say OK", ((turn["parts"] as List<*>)[0] as Map<*, *>)["text"])
        assertEquals(0.4, (o["generationConfig"] as Map<*, *>)["temperature"])
    }

    @Test fun quotesNewlinesAndBackslashesSurvive() {
        val prompt = "He said \"hi\"\nline two\\end\ttab"
        val o = parse(GeminiRequest.body(prompt, AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL))
        val turn = (o["contents"] as List<*>)[0] as Map<*, *>
        assertEquals(prompt, ((turn["parts"] as List<*>)[0] as Map<*, *>)["text"])
    }

    @Test fun banglaTextSurvives() {
        val prompt = "এই পাতাটি ব্যাখ্যা করো"
        val body = GeminiRequest.body(prompt, AiPrefs.LANG_BN, AiPrefs.LEN_NORMAL)
        val turn = (parse(body)["contents"] as List<*>)[0] as Map<*, *>
        assertEquals(prompt, ((turn["parts"] as List<*>)[0] as Map<*, *>)["text"])
    }

    @Test fun controlCharactersAreEscaped() {
        val body = GeminiRequest.body("a\u0001b", AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL)
        assertTrue(body.contains("\\u0001"))
        val turn = (parse(body)["contents"] as List<*>)[0] as Map<*, *>
        assertEquals("a\u0001b", ((turn["parts"] as List<*>)[0] as Map<*, *>)["text"])
    }

    @Test fun languageRule() {
        assertTrue(GeminiRequest.systemText(AiPrefs.LANG_EN, AiPrefs.LEN_NORMAL).contains("English"))
        assertTrue(GeminiRequest.systemText(AiPrefs.LANG_BN, AiPrefs.LEN_NORMAL).contains("Bangla"))
        val auto = GeminiRequest.systemText(AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL)
        assertTrue(auto.contains("language of the question"))
        assertFalse(auto.contains("Always answer"))
        assertEquals(auto, GeminiRequest.systemText("unknown", AiPrefs.LEN_NORMAL))
    }

    @Test fun lengthRule() {
        val short = GeminiRequest.systemText(AiPrefs.LANG_AUTO, AiPrefs.LEN_SHORT)
        val normal = GeminiRequest.systemText(AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL)
        val long = GeminiRequest.systemText(AiPrefs.LANG_AUTO, AiPrefs.LEN_LONG)
        assertTrue(short.contains("short"))
        assertTrue(long.contains("detailed"))
        assertEquals(3, setOf(short, normal, long).size)
    }

    @Test fun conversationKeepsOrderAndRoles() {
        val turns = listOf(
            GeminiRequest.Turn(GeminiRequest.USER, "one"),
            GeminiRequest.Turn(GeminiRequest.MODEL, "two"),
            GeminiRequest.Turn("other", "three"),
        )
        val contents = parse(GeminiRequest.body(turns, AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL))["contents"] as List<*>
        assertEquals(listOf("user", "model", "user"), contents.map { (it as Map<*, *>)["role"] })
        assertEquals(
            listOf("one", "two", "three"),
            contents.map { (((it as Map<*, *>)["parts"] as List<*>)[0] as Map<*, *>)["text"] },
        )
    }

    @Test fun emptyConversationIsRefused() {
        try {
            GeminiRequest.body(emptyList(), AiPrefs.LANG_AUTO, AiPrefs.LEN_NORMAL)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun noEmDashInBuiltText() {
        for (lang in AiPrefs.languages) for (len in AiPrefs.lengths) {
            assertFalse(GeminiRequest.systemText(lang, len).contains('\u2014'))
        }
    }
}
