package crypto

import io.ktor.server.routing.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.http.*
import io.ktor.client.*
import io.ktor.server.config.*
import kms.VaultKmsAdapter
import repo.SecretStore

data class PutSecretReq(val tenantId: Long, val name: String, val plaintext: String)

fun Route.cryptoRoutes(http: HttpClient, store: SecretStore, cfg: ApplicationConfig) {
    val base = cfg.propertyOrNull("vault.transitBase")?.getString() ?: ""
    val token = cfg.propertyOrNull("vault.token")?.getString() ?: ""
    val keyId = cfg.propertyOrNull("vault.keyId")?.getString() ?: ""
    val kms = VaultKmsAdapter(http, base, token)

    post("/api/crypto/put") {
        val req = call.receive<PutSecretReq>()
        val env = EnvelopeCrypto.encrypt(kms, keyId, req.plaintext.encodeToByteArray())
        val id = store.put(req.tenantId, req.name, env)
        call.respond(HttpStatusCode.Created, mapOf("secretId" to id))
    }

    get("/api/crypto/get") {
        val tenantId = call.request.queryParameters["tenantId"]?.toLongOrNull() ?: return@get call.respond(HttpStatusCode.BadRequest)
        val name = call.request.queryParameters["name"] ?: return@get call.respond(HttpStatusCode.BadRequest)
        val env = store.get(tenantId, name) ?: return@get call.respond(HttpStatusCode.NotFound)
        val pt = EnvelopeCrypto.decrypt(kms, env)
        call.respondText(String(pt, Charsets.UTF_8))
    }
}
