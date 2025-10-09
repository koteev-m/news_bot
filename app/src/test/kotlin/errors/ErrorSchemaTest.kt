package errors

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class ErrorSchemaTest {
    @Test
    fun error_response_has_required_fields() {
        val json = Json.encodeToString(
            ErrorResponse(
                code = "INTERNAL",
                message = "Unexpected error",
                details = listOf("x"),
                traceId = "t1"
            )
        )
        val parsed = Json.parseToJsonElement(json) as JsonObject
        assertTrue("code" in parsed.keys)
        assertTrue("message" in parsed.keys)
    }
}
