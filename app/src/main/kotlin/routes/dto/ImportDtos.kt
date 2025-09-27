package routes.dto

import kotlinx.serialization.Serializable

@Serializable
data class ImportByUrlRequest(
    val url: String,
)

@Serializable
data class ImportFailedItem(
    val line: Int,
    val error: String,
)

@Serializable
data class ImportReportResponse(
    val inserted: Int,
    val skippedDuplicates: Int,
    val failed: List<ImportFailedItem>,
)
