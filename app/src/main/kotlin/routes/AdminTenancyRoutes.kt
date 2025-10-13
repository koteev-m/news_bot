package routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import repo.TenancyRepository

@Serializable
data class CreateOrgReq(val name: String)

@Serializable
data class CreateTenantReq(val orgId: Long, val slug: String, val displayName: String)

@Serializable
data class AddMemberReq(val tenantId: Long, val userId: Long, val role: String)

fun Route.adminTenancyRoutes(repo: TenancyRepository) {
    route("/api/admin/tenancy") {
        post("/org") {
            call.receive<CreateOrgReq>()
            call.respond(HttpStatusCode.Created, mapOf("status" to "ok"))
        }
        post("/tenant") {
            call.receive<CreateTenantReq>()
            call.respond(HttpStatusCode.Created, mapOf("status" to "ok"))
        }
        post("/member") {
            val req = call.receive<AddMemberReq>()
            require(req.role in setOf("OWNER", "ADMIN", "DEVELOPER", "VIEWER"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
