package news.pipeline

import ab.ExperimentsService
import java.security.MessageDigest
import java.util.Base64
import kotlin.text.Charsets
import news.config.NewsConfig
import news.cta.CtaBuilder
import news.model.Cluster
import news.publisher.ChannelPublisher
import news.render.PostTemplates
import common.runCatchingNonFatal

class PublishBreaking(
    private val cfg: NewsConfig,
    private val publisher: ChannelPublisher,
    private val experiments: ExperimentsService? = null
) {
    suspend fun publish(cluster: Cluster, primaryTicker: String?): Boolean {
        val deepLink = deepLink(cluster.clusterKey)
        val text = PostTemplates.renderBreaking(cluster, deepLink)
        val abVariant = resolveVariant()
        val markup = CtaBuilder.defaultMarkup(cfg.botDeepLinkBase, cfg.maxPayloadBytes, primaryTicker, abVariant)
        return publisher.publish(cluster.clusterKey, text, markup)
    }

    private fun deepLink(clusterKey: String): String {
        val payload = payload(clusterKey)
        return CtaBuilder.deepLink(cfg.botDeepLinkBase, payload, cfg.maxPayloadBytes)
    }

    private fun payload(clusterKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashed = digest.digest(clusterKey.toByteArray(Charsets.UTF_8)).copyOf(16)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed)
    }

    private suspend fun resolveVariant(): String? {
        val service = experiments ?: return null
        val subject = cfg.channelId.takeIf { it != 0L } ?: 0L
        return runCatchingNonFatal { service.assign(subject, "cta_copy").variant }.getOrNull()
    }
}
