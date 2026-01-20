package integrations

import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.config.configOrNull
import io.ktor.server.config.propertyOrNull
import org.slf4j.LoggerFactory

internal data class MtprotoViewsConfig(
    val enabled: Boolean,
    val baseUrl: String?,
    val apiKey: String?
)

internal fun ApplicationEnvironment.mtprotoViewsConfig(): MtprotoViewsConfig {
    val root = config.configOrNull("integrations")?.configOrNull("mtproto")
    val enabled = root?.propertyOrNull("enabled")?.getString()?.toBoolean() ?: false
    val envBaseUrl = System.getenv("MTPROTO_GATEWAY_BASE")?.trim()?.ifBlank { null }
    val baseUrl = envBaseUrl ?: root?.propertyOrNull("baseUrl")?.getString()?.trim()?.ifBlank { null }
    val apiKey = root?.propertyOrNull("apiKey")?.getString()?.trim()?.ifBlank { null }
    if (enabled && baseUrl == null) {
        LoggerFactory.getLogger("mtproto-config")
            .warn("MTProto views enabled but integrations.mtproto.baseUrl is not configured")
    }
    return MtprotoViewsConfig(
        enabled = enabled,
        baseUrl = baseUrl,
        apiKey = apiKey
    )
}
