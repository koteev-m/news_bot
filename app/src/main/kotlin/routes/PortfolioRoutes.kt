package routes

import db.DatabaseFactory
import di.portfolioModule
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import repo.model.NewPortfolio
import repo.tables.UsersTable
import routes.dto.CreatePortfolioRequest
import routes.dto.PortfolioItemResponse
import routes.dto.ValidatedCreate
import routes.dto.ValidationError
import routes.dto.validate
import security.userIdOrNull
import portfolio.errors.PortfolioError
import portfolio.errors.PortfolioException
import common.runCatchingNonFatal

@Serializable
data class ApiErrorResponse(
    val error: String,
    val message: String,
    val details: List<ValidationError> = emptyList()
)

fun Route.portfolioRoutes() {
    route("/api/portfolio") {
        get {
            val deps = call.portfolioDependencies()
            val result = processPortfolioList(
                subject = call.userIdOrNull,
                deps = deps,
                onError = { throwable -> call.application.environment.log.error("Unhandled error", throwable) },
            )
            call.respondResult(result)
        }

        post {
            val request = try {
                call.receive<CreatePortfolioRequest>()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (err: Error) {
                throw err
            } catch (_: Throwable) {
                call.respondBadRequest(listOf("Invalid JSON payload"))
                return@post
            }

            val deps = call.portfolioDependencies()
            val result = processPortfolioCreate(
                subject = call.userIdOrNull,
                request = request,
                deps = deps,
                onError = { throwable -> call.application.environment.log.error("Unhandled error", throwable) },
            )
            call.respondResult(result)
        }
    }
}

internal val PortfolioRouteDepsKey = AttributeKey<PortfolioRouteDeps>("PortfolioRouteDeps")

internal data class PortfolioRouteDeps(
    val defaultValuationMethod: String,
    val resolveUser: suspend (Long) -> Long,
    val listPortfolios: suspend (Long) -> List<PortfolioRecord>,
    val createPortfolio: suspend (Long, ValidatedCreate.Valid) -> PortfolioRecord,
)

internal data class PortfolioRecord(
    val id: String,
    val name: String,
    val baseCurrency: String,
    val valuationMethod: String,
    val isActive: Boolean,
    val createdAt: Instant,
)

internal data class PortfolioRouteResult(
    val status: HttpStatusCode,
    val payload: Any? = null,
)

private fun PortfolioRecord.toResponse(): PortfolioItemResponse = PortfolioItemResponse(
    id = id,
    name = name,
    baseCurrency = baseCurrency,
    valuationMethod = valuationMethod,
    isActive = isActive,
    createdAt = createdAt.toString(),
)

private fun ApplicationCall.portfolioDependencies(): PortfolioRouteDeps {
    val attributes = application.attributes
    if (attributes.contains(PortfolioRouteDepsKey)) {
        return attributes[PortfolioRouteDepsKey]
    }
    return buildDefaultDependencies()
}

private fun ApplicationCall.buildDefaultDependencies(): PortfolioRouteDeps {
    val module = application.portfolioModule()
    val repository = module.repositories.portfolioRepository
    val defaultMethod = module.settings.portfolio.defaultValuationMethod.name
    val cache = application.valuationMethodCache()
    return PortfolioRouteDeps(
        defaultValuationMethod = defaultMethod,
        resolveUser = { tgUserId -> ensureUser(tgUserId) },
        listPortfolios = { userId ->
            repository.findByUser(userId, Int.MAX_VALUE).map { entity ->
                val portfolioId = entity.portfolioId.toString()
                PortfolioRecord(
                    id = portfolioId,
                    name = entity.name,
                    baseCurrency = entity.baseCurrency,
                    valuationMethod = cache[portfolioId] ?: defaultMethod,
                    isActive = entity.isActive,
                    createdAt = entity.createdAt,
                )
            }
        },
        createPortfolio = { userId, payload ->
            val created = repository.create(
                NewPortfolio(
                    userId = userId,
                    name = payload.name,
                    baseCurrency = payload.baseCurrency,
                    createdAt = Instant.now(),
                ),
            )
            val portfolioId = created.portfolioId.toString()
            cache[portfolioId] = payload.valuationMethod
            PortfolioRecord(
                id = portfolioId,
                name = created.name,
                baseCurrency = created.baseCurrency,
                valuationMethod = payload.valuationMethod,
                isActive = created.isActive,
                createdAt = created.createdAt,
            )
        },
    )
}

private fun Application.valuationMethodCache(): ConcurrentHashMap<String, String> {
    if (!attributes.contains(valuationCacheKey)) {
        attributes.put(valuationCacheKey, ConcurrentHashMap())
    }
    return attributes[valuationCacheKey]
}

internal suspend fun processPortfolioList(
    subject: String?,
    deps: PortfolioRouteDeps,
    onError: (Throwable) -> Unit = {},
): PortfolioRouteResult {
    val tgUserId = subject?.toLongOrNull() ?: return unauthorizedResult()
    val userId = runCatchingNonFatal { deps.resolveUser(tgUserId) }
        .getOrElse { return mapException(it, onError) }
    val records = runCatchingNonFatal { deps.listPortfolios(userId) }
        .getOrElse { return mapException(it, onError) }
    return PortfolioRouteResult(HttpStatusCode.OK, records.map { it.toResponse() })
}

internal suspend fun processPortfolioCreate(
    subject: String?,
    request: CreatePortfolioRequest,
    deps: PortfolioRouteDeps,
    onError: (Throwable) -> Unit = {},
): PortfolioRouteResult {
    val tgUserId = subject?.toLongOrNull() ?: return unauthorizedResult()
    val validation = request.validate(deps.defaultValuationMethod)
    val validated = when (validation) {
        is ValidatedCreate.Valid -> validation
        is ValidatedCreate.Invalid -> {
            return PortfolioRouteResult(
                HttpStatusCode.BadRequest,
                ApiErrorResponse(
                    error = "bad_request",
                    message = "Invalid request payload",
                    details = validation.errors,
                ),
            )
        }
    }

    val userId = runCatchingNonFatal { deps.resolveUser(tgUserId) }
        .getOrElse { return mapException(it, onError) }
    val record = runCatchingNonFatal { deps.createPortfolio(userId, validated) }
        .getOrElse { throwable ->
            if (isUniqueViolation(throwable)) {
                return PortfolioRouteResult(
                    HttpStatusCode.Conflict,
                    ApiErrorResponse(
                        error = "conflict",
                        message = "Portfolio with the same name already exists",
                    ),
                )
            }
            return mapException(throwable, onError)
        }

    return PortfolioRouteResult(HttpStatusCode.Created, record.toResponse())
}

private fun unauthorizedResult(): PortfolioRouteResult =
    PortfolioRouteResult(
        HttpStatusCode.Unauthorized,
        ApiErrorResponse(error = "unauthorized", message = "Authentication required"),
    )

private fun mapException(throwable: Throwable, onError: (Throwable) -> Unit): PortfolioRouteResult {
    val domain = (throwable as? PortfolioException)?.error
    return when (domain) {
        is PortfolioError.Validation -> PortfolioRouteResult(
            HttpStatusCode.BadRequest,
            ApiErrorResponse(
                error = "bad_request",
                message = domain.message,
                details = listOf(ValidationError(field = "general", message = domain.message)),
            ),
        )
        is PortfolioError.NotFound -> PortfolioRouteResult(
            HttpStatusCode.NotFound,
            ApiErrorResponse(error = "not_found", message = domain.message),
        )
        is PortfolioError.External -> {
            onError(throwable)
            PortfolioRouteResult(
                HttpStatusCode.InternalServerError,
                ApiErrorResponse(error = "internal_error", message = "Internal server error"),
            )
        }
        null -> {
            if (isUniqueViolation(throwable)) {
                PortfolioRouteResult(
                    HttpStatusCode.Conflict,
                    ApiErrorResponse(error = "conflict", message = "Resource already exists"),
                )
            } else {
                onError(throwable)
                PortfolioRouteResult(
                    HttpStatusCode.InternalServerError,
                    ApiErrorResponse(error = "internal_error", message = "Internal server error"),
                )
            }
        }
    }
}

private suspend fun ApplicationCall.respondResult(result: PortfolioRouteResult) {
    val payload = result.payload
    if (payload != null) {
        respond(result.status, payload)
    } else {
        respond(result.status)
    }
}

private suspend fun ensureUser(tgUserId: Long): Long {
    val existing = DatabaseFactory.dbQuery {
        UsersTable
            .selectAll()
            .where { UsersTable.tgUserId eq tgUserId }
            .limit(1)
            .singleOrNull()
            ?.get(UsersTable.userId)
    }
    if (existing != null) {
        return existing
    }

    return try {
        DatabaseFactory.dbQuery {
            UsersTable.insert { statement ->
                statement[UsersTable.tgUserId] = tgUserId
            } get UsersTable.userId
        }
    } catch (ex: ExposedSQLException) {
        if (ex.sqlState == UNIQUE_VIOLATION || isUniqueViolation(ex.cause)) {
            DatabaseFactory.dbQuery {
                UsersTable
                    .selectAll()
                    .where { UsersTable.tgUserId eq tgUserId }
                    .limit(1)
                    .singleOrNull()
                    ?.get(UsersTable.userId)
            } ?: throw ex
        } else {
            throw ex
        }
    }
}

private fun isUniqueViolation(throwable: Throwable?): Boolean {
    if (throwable == null) return false
    return when (throwable) {
        is ExposedSQLException -> throwable.sqlState == UNIQUE_VIOLATION || isUniqueViolation(throwable.cause)
        is SQLException -> throwable.sqlState == UNIQUE_VIOLATION || isUniqueViolation(throwable.cause)
        else -> if (throwable.cause === throwable) false else isUniqueViolation(throwable.cause)
    }
}

private const val UNIQUE_VIOLATION = "23505"
private val valuationCacheKey = AttributeKey<ConcurrentHashMap<String, String>>("PortfolioValuationCache")
