package demo

import common.runCatchingNonFatal
import db.DatabaseFactory
import db.tables.PortfoliosTable
import db.tables.TradesTable
import db.tables.UsersTable
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.log
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.application
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.select
import java.nio.file.Files
import java.nio.file.Paths

private const val DEMO_USER_TG_ID = 10001L
private const val DEMO_PORTFOLIO_NAME = "Demo RUB"

fun Route.demoRoutes() {
    val app = application
    val profile = currentProfile(app)
    if (profile.equals("prod", ignoreCase = true)) {
        app.log.info("demo routes disabled for profile={}", profile)
        return
    }

    route("/demo") {
        post("/seed") {
            val (status, payload) = runSeedScriptOrCheck(app)
            call.respond(status, payload)
        }

        post("/reset") {
            if (!isResetAllowed()) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    mapOf(
                        "ok" to false,
                        "hint" to "Set DEMO_RESET_ENABLE=true to allow demo reset",
                    ),
                )
                return@post
            }

            val result = runCatchingNonFatal { truncateDemoData() }
            if (result.isFailure) {
                app.log.error("demo reset failed", result.exceptionOrNull())
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf(
                        "ok" to false,
                        "hint" to "Reset failed: ${result.exceptionOrNull()?.message ?: "unexpected error"}",
                    ),
                )
                return@post
            }

            call.respond(HttpStatusCode.OK, mapOf("ok" to true, "hint" to "Demo tables truncated"))
        }
    }
}

private fun currentProfile(app: Application): String {
    val envValue = System.getenv("APP_PROFILE")
    if (!envValue.isNullOrBlank()) {
        return envValue
    }
    return app.environment.config
        .propertyOrNull("ktor.environment")
        ?.getString() ?: "dev"
}

private suspend fun runSeedScriptOrCheck(app: Application): Pair<HttpStatusCode, Map<String, Any>> {
    val projectRoot = Paths.get(System.getProperty("user.dir"))
    val scriptPath = projectRoot.resolve("tools/demo/seed.sh")

    if (!Files.isRegularFile(scriptPath)) {
        val seeded = hasDemoData()
        return HttpStatusCode.OK to
            mapOf(
                "ok" to seeded,
                "hint" to "Seed script missing. Run tools/demo/seed.sh manually.",
            )
    }

    if (!Files.isExecutable(scriptPath)) {
        val seeded = hasDemoData()
        return HttpStatusCode.OK to
            mapOf(
                "ok" to seeded,
                "hint" to "Make tools/demo/seed.sh executable and rerun",
            )
    }

    val execution =
        runCatchingNonFatal {
            withContext(Dispatchers.IO) {
                val process =
                    ProcessBuilder(scriptPath.toAbsolutePath().toString())
                        .directory(projectRoot.toFile())
                        .redirectErrorStream(true)
                        .start()
                val output = process.inputStream.bufferedReader().use { it.readText() }
                val exit = process.waitFor()
                exit to output.trim()
            }
        }

    if (execution.isFailure) {
        app.log.warn("demo seed script failed to start", execution.exceptionOrNull())
        val seeded = hasDemoData()
        val reason = execution.exceptionOrNull()?.message ?: "spawn error"
        return HttpStatusCode.OK to
            mapOf(
                "ok" to seeded,
                "hint" to "Could not execute seed.sh: $reason",
            )
    }

    val (exitCode, output) = execution.getOrThrow()
    if (exitCode == 0) {
        return HttpStatusCode.OK to
            mapOf(
                "ok" to true,
                "hint" to "seed.sh executed successfully",
            )
    }

    app.log.warn("demo seed script exited with code {}", exitCode)
    if (output.isNotEmpty()) {
        app.log.warn(output)
    }
    return HttpStatusCode.InternalServerError to
        mapOf(
            "ok" to false,
            "hint" to "seed.sh failed with exit $exitCode",
        )
}

private suspend fun hasDemoData(): Boolean =
    DatabaseFactory.dbQuery {
        TradesTable
            .join(PortfoliosTable, JoinType.INNER, additionalConstraint = {
                TradesTable.portfolioId eq PortfoliosTable.portfolioId
            })
            .join(UsersTable, JoinType.INNER, additionalConstraint = { PortfoliosTable.userId eq UsersTable.userId })
            .select {
                (UsersTable.tgUserId eq DEMO_USER_TG_ID) and (PortfoliosTable.name eq DEMO_PORTFOLIO_NAME)
            }.limit(1)
            .toList()
            .isNotEmpty()
    }

private fun isResetAllowed(): Boolean {
    val raw = System.getenv("DEMO_RESET_ENABLE") ?: return false
    return when (raw.lowercase()) {
        "true", "1", "yes", "y" -> true
        else -> false
    }
}

private suspend fun truncateDemoData() {
    DatabaseFactory.dbQuery {
        exec(
            """
            TRUNCATE TABLE
                trades,
                positions,
                instrument_aliases,
                instruments,
                valuations_daily,
                prices,
                fx_rates,
                portfolios,
                users
            RESTART IDENTITY CASCADE
            """.trimIndent(),
        )
    }
}
