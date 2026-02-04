package app

import audit.AuditPlugin
import audit.AuditService
import audit.auditRoutes
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import repo.AuditLedgerRepository

private val AuditServiceKey = AttributeKey<AuditService>("auditService")
private val AuditRepositoryKey = AttributeKey<AuditLedgerRepository>("auditRepository")

fun Application.installAuditLayer() {
    val repository = AuditLedgerRepository()
    val service = AuditService(repository)
    attributes.put(AuditServiceKey, service)
    attributes.put(AuditRepositoryKey, repository)
    install(AuditPlugin) {
        auditService = service
    }
    routing {
        auditRoutes(repository)
    }
}

val Application.auditService: AuditService
    get() = attributes[AuditServiceKey]

val Application.auditRepository: AuditLedgerRepository
    get() = attributes[AuditRepositoryKey]
