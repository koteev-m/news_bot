package errors

import kotlinx.serialization.Serializable

@Serializable
data class ErrorResponse(
    val code: String,
    val message: String,
    val details: List<String> = emptyList(),
    val traceId: String
)
