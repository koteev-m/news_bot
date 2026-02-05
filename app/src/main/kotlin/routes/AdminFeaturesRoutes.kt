package routes

import app.Services
import features.FeatureFlagsPatch
import features.FeatureFlagsService
import features.FeatureFlagsValidationException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import kotlinx.serialization.SerializationException
import security.userIdOrNull

fun Route.adminFeaturesRoutes() {
    route("/api/admin/features") {
        get {
            if (call.userIdOrNull == null) {
                call.respondUnauthorized()
                return@get
            }
            val services = call.application.attributes[Services.Key]
            val flags = services.featureFlags.effective()
            call.respond(HttpStatusCode.OK, flags)
        }

        patch {
            val subject =
                call.userIdOrNull?.toLongOrNull() ?: run {
                    call.respondUnauthorized()
                    return@patch
                }
            val services = call.application.attributes[Services.Key]
            if (!services.adminUserIds.contains(subject)) {
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "forbidden"))
                return@patch
            }

            val patch =
                try {
                    call.receive<FeatureFlagsPatch>()
                } catch (_: ContentTransformationException) {
                    call.respondBadRequest(listOf("invalid_json"))
                    return@patch
                } catch (_: SerializationException) {
                    call.respondBadRequest(listOf("invalid_json"))
                    return@patch
                } catch (_: Exception) {
                    call.respondBadRequest(listOf("invalid_json"))
                    return@patch
                }

            val service: FeatureFlagsService = services.featureFlags
            try {
                service.upsertGlobal(patch)
            } catch (error: FeatureFlagsValidationException) {
                call.respondBadRequest(error.errors)
                return@patch
            }

            call.respond(HttpStatusCode.NoContent)
        }
    }
}
