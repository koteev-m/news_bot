package scim

import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable data class ScimUser(val id: String, val userName: String, val active: Boolean = true, val externalId: String? = null, val emails: List<Email>? = null) {
    @Serializable data class Email(val value: String, val primary: Boolean = true)
}

fun Route.scimRoutes() {
    route("/scim/v2") {
        get("/ServiceProviderConfig") {
            call.respond(
                mapOf(
                    "patch" to true,
                    "bulk" to false,
                    "filter" to true,
                    "changePassword" to false,
                    "sort" to false,
                    "etag" to false
                )
            )
        }
        get("/Schemas") { call.respond(listOf(mapOf("id" to "urn:ietf:params:scim:schemas:core:2.0:User"))) }
        // CRUD Users (минимально)
        post("/Users") {
            val u = call.receive<ScimUser>()
            call.respond(HttpStatusCode.Created, u.copy(id = UUID.randomUUID().toString()))
        }
        get("/Users/{id}") {
            val id = call.parameters["id"]!!
            call.respond(ScimUser(id = id, userName = "example"))
        }
        patch("/Users/{id}") { call.respond(HttpStatusCode.NoContent) }
        delete("/Users/{id}") { call.respond(HttpStatusCode.NoContent) }
    }
}
