package tenancy

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.response.respondText
import repo.TenancyRepository

class TenantPluginConfig {
    lateinit var repository: TenancyRepository
    var userIdProvider: (ApplicationCall) -> Long? = { null }
    var scopesProvider: (ApplicationCall) -> Set<String> = { emptySet() }
}

val TenantPlugin = createApplicationPlugin(name = "TenantPlugin", createConfiguration = ::TenantPluginConfig) {
    val repo = pluginConfig.repository
    val resolver = TenantResolver(repo)
    onCall { call ->
        runCatching {
            val userId = pluginConfig.userIdProvider(call)
            val scopes = pluginConfig.scopesProvider(call)
            val ctx = resolver.resolve(call, userId, scopes)
            call.attributes.put(TenantContextKey, ctx)
        }.onFailure {
            call.respondText(
                text = """{"code":"UNAUTHORIZED","message":"Tenant resolution failed"}""",
                contentType = ContentType.Application.Json,
                status = HttpStatusCode.Unauthorized
            )
            throw it
        }
    }
}
