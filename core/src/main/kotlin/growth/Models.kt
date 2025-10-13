package growth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable data class Campaign(
    val id: Long, val name: String, val status: String, val channel: String,
    val locale: String, val templateId: Long?, val capDay: Int,
    val quietFrom: String, val quietTo: String
)

@Serializable data class Template(
    val id: Long, val name: String, val channel: String, val locale: String,
    val subject: String?, val bodyMd: String
)

@Serializable data class Segment(
    val id: Long, val name: String, val definitionSql: String
)

@Serializable data class Delivery(
    val id: Long, val campaignId: Long?, val journeyId: Long?, val userId: Long,
    val tenantId: Long, val channel: String, val status: String,
    val reason: String?, val payload: JsonObject?
)

@Serializable data class JourneyNode(
    val id: Long, val journeyId: Long, val kind: String, val config: JsonObject, val position: Int
)

@Serializable data class JourneyEdge(
    val id: Long, val journeyId: Long, val fromNodeId: Long, val toNodeId: Long, val condition: JsonObject?
)
