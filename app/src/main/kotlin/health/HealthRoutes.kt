package health

import common.runCatchingNonFatal
import db.DatabaseFactory
import di.portfolioModule
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.sql.selectAll
import repo.mapper.toFxRate
import repo.tables.FxRatesTable
import java.time.Instant
import java.time.format.DateTimeFormatter

fun Route.healthRoutes() {
    get("/health/db") {
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val module = call.application.portfolioModule()

        try {
            DatabaseFactory.dbQuery { exec("select 1") { } }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (err: Error) {
            throw err
        } catch (ex: Throwable) {
            call.application.environment.log
                .error("Database ping failed", ex)
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                mapOf(
                    "status" to "error",
                    "db" to "down",
                    "fx" to "skip",
                    "instrument" to "skip",
                    "timestamp" to timestamp,
                ),
            )
            return@get
        }

        val fxStatus =
            try {
                val fxRepo = module.repositories.fxRateRepository
                val fxRate =
                    runCatchingNonFatal { fxRepo.findLatest("USD") }.getOrNull()
                        ?: DatabaseFactory.dbQuery {
                            FxRatesTable
                                .selectAll()
                                .limit(1)
                                .singleOrNull()
                                ?.toFxRate()
                        }
                if (fxRate != null) "ok" else "skip"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (err: Error) {
                throw err
            } catch (ex: Throwable) {
                call.application.environment.log
                    .error("FX warm-up failed", ex)
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf(
                        "status" to "error",
                        "db" to "up",
                        "fx" to "error",
                        "instrument" to "skip",
                        "timestamp" to timestamp,
                    ),
                )
                return@get
            }

        val instrumentStatus =
            try {
                val instrument =
                    module.repositories.instrumentRepository
                        .listAll(limit = 1)
                        .firstOrNull()
                if (instrument != null) "ok" else "skip"
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (err: Error) {
                throw err
            } catch (ex: Throwable) {
                call.application.environment.log
                    .error("Instrument warm-up failed", ex)
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    mapOf(
                        "status" to "error",
                        "db" to "up",
                        "fx" to fxStatus,
                        "instrument" to "error",
                        "timestamp" to timestamp,
                    ),
                )
                return@get
            }

        call.respond(
            mapOf(
                "status" to "ok",
                "db" to "up",
                "fx" to fxStatus,
                "instrument" to instrumentStatus,
                "timestamp" to timestamp,
            ),
        )
    }
}
