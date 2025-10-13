package growth

import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import repo.GrowthRepository
import tenancy.TenantContextKey

@Serializable data class CampaignRunReq(val campaignId: Long, val templateId: Long, val segmentId: Long)

fun Route.growthRoutes(repo: GrowthRepository, engine: GrowthEngine) {
    route("/api/growth") {
        post("/optout") {
            // упрощенно: запись в growth_suppressions (опустим код вставки для краткости)
            call.respond(HttpStatusCode.NoContent)
        }
        post("/run-campaign") {
            val req = call.receive<CampaignRunReq>()
            val ctx = call.attributes[TenantContextKey]
            val campaign = Campaign(req.campaignId, "tmp", "ACTIVE", "telegram", "en", req.templateId, 1, "22:00", "08:00")
            val template = Template(req.templateId, "tmp", "telegram", "en", null, "👋 {{userId}} — welcome to {{tenant}}")
            val segment = Segment(req.segmentId, "tmp", "SELECT 1")
            engine.runCampaign(ctx, campaign, template, segment)
            call.respond(HttpStatusCode.Accepted)
        }
    }
}
