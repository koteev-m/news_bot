package routes

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.acceptItems
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal val AcceptInvalidAttribute = AttributeKey<Boolean>("AcceptInvalid")
private val GuestImportStorageAttribute = AttributeKey<GuestImportStorage>("GuestImportStorage")
private val json = Json { encodeDefaults = true }

fun Application.configureGuestListRoutes(storage: GuestImportStorage = GuestImportStorage()) {
    attributes.put(GuestImportStorageAttribute, storage)

    routing {
        post("/guest-list/import") {
            val body = call.receiveText()
            val report = body.toGuestImportReport()

            val wantsCsv = call.wantsCsv()
            val acceptInvalid = call.attributes.getOrNull(AcceptInvalidAttribute) ?: false

            val guestStorage = call.application.attributes[GuestImportStorageAttribute]
            guestStorage.saveAccepted(report.acceptedNames)

            if (wantsCsv) {
                call.respondText(report.toCsv(), ContentType.Text.CSV)
            } else if (acceptInvalid) {
                call.respondText(json.encodeToString(report.toResponse()), ContentType.Application.Json)
            } else {
                call.respond(report.toResponse())
            }
        }
    }
}

fun prepareActiveListWithManager(): GuestImportStorage = GuestImportStorage()

internal fun ApplicationCall.wantsCsv(): Boolean {
    val formatOverride = request.queryParameters["format"]?.equals("csv", ignoreCase = true) == true
    if (formatOverride) {
        return true
    }

    var invalidAccept = false
    val acceptItems = runCatching { request.acceptItems() }
        .onFailure { invalidAccept = true }
        .getOrElse { emptyList() }

    var csvQ = 0.0
    var jsonQ = 0.0

    for (item in acceptItems) {
        val quality = item.quality
        if (quality <= 0.0) continue

        val baseType = runCatching { ContentType.parse(item.value).withoutParameters() }
            .onFailure { invalidAccept = true }
            .getOrNull() ?: continue

        when (baseType) {
            ContentType.Text.CSV -> csvQ = maxOf(csvQ, quality)
            ContentType.Application.Json -> jsonQ = maxOf(jsonQ, quality)
        }
    }

    if (invalidAccept) {
        attributes.put(AcceptInvalidAttribute, true)
    }

    return csvQ > 0.0 && csvQ > jsonQ
}

private fun String.toGuestImportReport(): GuestImportReport {
    val lines = lineSequence().toList()
    if (lines.isEmpty()) {
        return GuestImportReport(emptyList(), 0)
    }

    val dataLines = if (lines.first().contains("name", ignoreCase = true)) lines.drop(1) else lines
    val trimmedData = dataLines.dropLastWhile { it.isBlank() }
    val acceptedNames = trimmedData.filter { it.isNotBlank() }
    val rejectedCount = trimmedData.size - acceptedNames.size
    return GuestImportReport(acceptedNames, rejectedCount)
}

data class GuestImportReport(val acceptedNames: List<String>, val rejectedCount: Int) {
    val acceptedCount: Int = acceptedNames.size
}

@Serializable
internal data class GuestImportResponse(
    @SerialName("accepted_count") val acceptedCount: Int,
    @SerialName("rejected_count") val rejectedCount: Int,
)

internal fun GuestImportReport.toResponse(): GuestImportResponse = GuestImportResponse(
    acceptedCount = acceptedCount,
    rejectedCount = rejectedCount,
)

internal fun GuestImportReport.toCsv(): String = buildString {
    append("accepted_count,rejected_count\n")
    append(acceptedCount)
    append(',')
    append(rejectedCount)
}

class GuestImportStorage {
    private val acceptedNames = mutableListOf<String>()

    fun saveAccepted(names: List<String>) {
        acceptedNames.addAll(names)
    }

    fun accepted(): List<String> = acceptedNames.toList()
}
