package repo

import org.jetbrains.exposed.dao.id.LongIdTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import storage.db.DatabaseFactory.dbQuery

object SecretsTable : LongIdTable("secrets", "secret_id") {
    val tenantId: Column<Long> = long("tenant_id")
    val name: Column<String> = text("name")
    val envelopeJson: Column<String> = text("envelope_json")
}

class SecretStore {

    suspend fun put(tenantId: Long, name: String, envelopeJson: String): Long = dbQuery {
        SecretsTable.insert {
            it[SecretsTable.tenantId] = tenantId
            it[SecretsTable.name] = name
            it[SecretsTable.envelopeJson] = envelopeJson
        }.resultedValues?.first()?.get(SecretsTable.id)?.value ?: -1L
    }

    suspend fun get(tenantId: Long, name: String): String? = dbQuery {
        SecretsTable.select {
            (SecretsTable.tenantId eq tenantId) and (SecretsTable.name eq name)
        }.firstOrNull()?.get(SecretsTable.envelopeJson)
    }
}
