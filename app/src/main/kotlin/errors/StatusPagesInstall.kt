package errors

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond

fun Application.installStatusPages() {
    val mapper = ErrorMapper(environment.log)
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val mapped = mapper.map(call, cause)
            mapped.headers.forEach { (name, value) ->
                call.response.headers.append(name, value, safeOnly = false)
            }
            call.respond(mapped.status, mapped.response)
        }
    }
}
