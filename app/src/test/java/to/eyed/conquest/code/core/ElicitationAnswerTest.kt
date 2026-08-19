package to.eyed.conquest.code.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning a filled-in form question back into the protocol's answer.
 *
 * The types are the whole point. The engine maps each value's JSON type
 * straight onto the protocol's `ElicitationContentValue` variant, so an
 * integer field answered with the string `"7"` reaches the agent as a
 * string — indistinguishable in a transcript, and wrong.
 */
class ElicitationAnswerTest {

    private fun field(
        key: String,
        type: String,
        required: Boolean = false,
        options: List<ElicitationOption> = emptyList(),
        defaultString: String? = null,
        defaultNumber: Double? = null,
        defaultBoolean: Boolean? = null,
        defaultList: List<String> = emptyList(),
    ) = ElicitationField(
        key = key,
        type = type,
        title = null,
        description = null,
        required = required,
        options = options,
        format = null,
        defaultString = defaultString,
        defaultNumber = defaultNumber,
        defaultBoolean = defaultBoolean,
        defaultList = defaultList,
        minimum = null,
        maximum = null,
        minLength = null,
        maxLength = null,
        minItems = null,
        maxItems = null,
    )

    @Test
    fun everyFieldGoesBackAsItsOwnJsonType() {
        val fields = listOf(
            field("note", "string"),
            field("depth", "integer"),
            field("ratio", "number"),
            field("dry", "boolean"),
            field("tags", "array"),
        )
        val json = ElicitationAnswer.accept(
            fields,
            mapOf(
                "note" to "hello",
                "depth" to "7",
                "ratio" to "1.5",
                "dry" to true,
                "tags" to listOf("a", "c"),
            ),
        )
        val content = JSONObject(json).getJSONObject("content")
        assertEquals("accept", JSONObject(json).getString("action"))
        assertEquals("hello", content.get("note"))
        assertEquals(2, content.getJSONArray("tags").length())
        // The *text* is the contract: the engine reads this JSON and maps
        // each value's JSON type onto the protocol's own variant, so an
        // unquoted 7 and a quoted "7" are two different answers. Asserting on
        // the parsed object would let a string through — `getLong` parses one
        // happily, which is exactly the bug this guards.
        val emitted = content.toString()
        assertTrue("an integer, not text: $emitted", emitted.contains("\"depth\":7"))
        assertTrue("a real number: $emitted", emitted.contains("\"ratio\":1.5"))
        assertTrue("a boolean, not text: $emitted", emitted.contains("\"dry\":true"))
        assertFalse("nothing quoted that should not be: $emitted", emitted.contains("\"7\""))
    }

    /** A number field with junk in it must not be sent as junk-the-string. */
    @Test
    fun aNumberFieldThatIsNotANumberIsLeftOutRatherThanSentAsText() {
        val fields = listOf(field("depth", "integer"))
        val content = JSONObject(ElicitationAnswer.accept(fields, mapOf("depth" to "later")))
            .getJSONObject("content")
        assertFalse(content.has("depth"))
    }

    @Test
    fun requiredFieldsGateTheAnswer() {
        val fields = listOf(
            field("note", "string", required = true),
            field("depth", "integer", required = true),
            field("tags", "array", required = true),
            field("dry", "boolean", required = true),
        )
        val empty = ElicitationAnswer.missing(
            fields,
            mapOf("note" to "", "depth" to "", "tags" to emptyList<String>(), "dry" to false),
        )
        // A switch always has an answer; false is one.
        assertEquals(listOf("note", "depth", "tags"), empty)

        val filled = ElicitationAnswer.missing(
            fields,
            mapOf("note" to "x", "depth" to "2", "tags" to listOf("a"), "dry" to false),
        )
        assertTrue(filled.isEmpty())
    }

    /** An optional multi-select with nothing ticked says so, rather than going silent. */
    @Test
    fun anEmptyOptionalMultiSelectIsSentAsAnEmptyList() {
        val fields = listOf(field("tags", "array"))
        val content = JSONObject(ElicitationAnswer.accept(fields, mapOf("tags" to emptyList<String>())))
            .getJSONObject("content")
        assertEquals(0, content.getJSONArray("tags").length())
    }

    @Test
    fun defaultsSeedTheFieldsInTheirOwnShape() {
        assertEquals("", ElicitationAnswer.initialValue(field("a", "string")))
        assertEquals("x", ElicitationAnswer.initialValue(field("a", "string", defaultString = "x")))
        assertEquals(true, ElicitationAnswer.initialValue(field("a", "boolean", defaultBoolean = true)))
        // A JSON number arrives as a double; `3.0` in a form box is wrong.
        assertEquals("3", ElicitationAnswer.initialValue(field("a", "integer", defaultNumber = 3.0)))
        assertEquals("1.5", ElicitationAnswer.initialValue(field("a", "number", defaultNumber = 1.5)))
        assertEquals(listOf("a"), ElicitationAnswer.initialValue(field("a", "array", defaultList = listOf("a"))))
    }

    /** A field kind this build cannot draw is never invented an answer for. */
    @Test
    fun anUnsupportedFieldIsNeitherSentNorDemanded() {
        val fields = listOf(field("mystery", "unsupported", required = true))
        assertTrue(ElicitationAnswer.missing(fields, emptyMap()).isEmpty())
        val content = JSONObject(ElicitationAnswer.accept(fields, mapOf("mystery" to "x")))
            .getJSONObject("content")
        assertEquals(0, content.length())
    }
}
