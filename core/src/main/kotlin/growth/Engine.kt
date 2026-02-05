package growth

import common.runCatchingNonFatal
import kotlinx.serialization.json.JsonObject
import tenancy.TenantContext

interface SegmentProvider {
    suspend fun fetchUserIds(
        segment: Segment,
        tenantId: Long,
    ): List<Long>
}

interface DeliveryRepo {
    suspend fun frequencyCountToday(
        userId: Long,
        tenantId: Long,
        channel: String,
    ): Int

    suspend fun isSuppressed(
        userId: Long,
        tenantId: Long,
        channel: String,
    ): Boolean

    suspend fun enqueue(
        campaignId: Long?,
        journeyId: Long?,
        userId: Long,
        tenantId: Long,
        channel: String,
        payload: JsonObject?,
    ): Long

    suspend fun markSent(deliveryId: Long)

    suspend fun markFailed(
        deliveryId: Long,
        reason: String,
    )
}

interface Messenger {
    suspend fun sendTelegram(
        userId: Long,
        text: String,
        locale: String,
    ): Boolean
    // email/webhook можно добавить при необходимости
}

class Caps(
    val perDay: Int,
    val quietFromHour: Int,
    val quietToHour: Int,
)

class GrowthEngine(
    private val deliveryRepo: DeliveryRepo,
    private val segmentProvider: SegmentProvider,
    private val messenger: Messenger,
) {
    suspend fun runCampaign(
        ctx: TenantContext,
        campaign: Campaign,
        template: Template,
        segment: Segment,
    ) {
        val users = segmentProvider.fetchUserIds(segment, ctx.tenant.tenantId)
        val cap =
            Caps(
                campaign.capDay,
                campaign.quietFrom.substringBefore(":").toInt(),
                campaign.quietTo.substringBefore(":").toInt(),
            )
        val nowHour =
            java.time.OffsetDateTime
                .now(java.time.ZoneOffset.UTC)
                .hour
        if (isQuiet(nowHour, cap)) return

        for (u in users) {
            if (deliveryRepo.isSuppressed(u, ctx.tenant.tenantId, campaign.channel)) continue
            val sentToday = deliveryRepo.frequencyCountToday(u, ctx.tenant.tenantId, campaign.channel)
            if (sentToday >= cap.perDay) continue
            val text = render(template.bodyMd, mapOf("userId" to u.toString(), "tenant" to ctx.tenant.slug))
            val id = deliveryRepo.enqueue(campaign.id, null, u, ctx.tenant.tenantId, campaign.channel, null)
            runCatchingNonFatal {
                val ok = messenger.sendTelegram(u, text, campaign.locale)
                if (ok) deliveryRepo.markSent(id) else deliveryRepo.markFailed(id, "send_failed")
            }.onFailure { deliveryRepo.markFailed(id, "exception:${it::class.simpleName}") }
        }
    }

    private fun isQuiet(
        hour: Int,
        cap: Caps,
    ): Boolean {
        val from = cap.quietFromHour
        val to = cap.quietToHour
        return if (from < to) (hour >= from && hour < to) else (hour >= from || hour < to)
    }

    private fun render(
        tpl: String,
        ctx: Map<String, String>,
    ) = ctx.entries.fold(tpl) { acc, (k, v) -> acc.replace("{{" + k + "}}", v) }
}
