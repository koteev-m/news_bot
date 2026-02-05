package tenancy

import common.runCatchingNonFatal
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respondText
import repo.TenancyRepository

class TenantPluginConfig {
    var repository: TenancyRepository? = null
    var userIdProvider: (ApplicationCall) -> Long? = { null }
    var scopesProvider: (ApplicationCall) -> Set<String> = { emptySet() }
}

val TenantPlugin =
    createApplicationPlugin(
        name = "TenantPlugin",
        createConfiguration = ::TenantPluginConfig,
    ) {
        val repo = requireNotNull(pluginConfig.repository) { "Tenancy repository must be configured" }
        val resolver = TenantResolver(repo)

        onCall { call ->
            runCatchingNonFatal {
                val userId = pluginConfig.userIdProvider(call)
                val scopes = pluginConfig.scopesProvider(call)
                val ctx = resolver.resolve(call, userId, scopes)
                call.attributes.put(TenantContextKey, ctx)
            }.onFailure {
                call.respondText(
                    text = """{"code":"UNAUTHORIZED","message":"Tenant resolution failed"}""",
                    contentType = ContentType.Application.Json,
                    status = HttpStatusCode.Unauthorized,
                )
                throw it
            }
        }
    }
