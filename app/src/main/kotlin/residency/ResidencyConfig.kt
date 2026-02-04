package residency

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respond
import repo.ResidencyRepository
import tenancy.TenantContextKey
import common.runCatchingNonFatal

/**
 * Плагин, который валидирует «гео-политику» перед записью PII/FIN.
 * Ожидает заголовок X-Region-Served (регион текущего PoP) или env конфиг.
 */
class ResidencyConfig {
    var repo: ResidencyRepository? = null
    var servedRegionProvider: (ApplicationCall) -> String = { System.getenv("APP_REGION") ?: "EU" }
    var dataClassProvider: (ApplicationCall) -> DataClass? = { null } // определить по маршруту
}

val ResidencyPlugin = createApplicationPlugin(name = "ResidencyPlugin", createConfiguration = ::ResidencyConfig) {
    val repo = requireNotNull(pluginConfig.repo) { "Residency repository must be configured" }
    val regionOf = pluginConfig.servedRegionProvider
    val dataClassOf = pluginConfig.dataClassProvider

    onCall { call ->
        val ctx = runCatchingNonFatal { call.attributes[TenantContextKey] }.getOrNull() ?: return@onCall
        val policy = repo.getPolicy(ctx.tenant.tenantId) ?: return@onCall
        val served = regionOf(call)
        val dc = dataClassOf(call) ?: return@onCall
        if (dc == DataClass.PII || dc == DataClass.FIN) {
            if (policy.region != served) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf("code" to "RESIDENCY_VIOLATION", "message" to "write denied in region=$served, home=${policy.region}")
                )
                return@onCall
            }
        }
    }
}
