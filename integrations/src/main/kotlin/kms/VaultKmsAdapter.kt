package kms

import crypto.KmsAdapter
import io.ktor.client.HttpClient
import org.slf4j.LoggerFactory

@Deprecated("Replace with real Vault adapter", level = DeprecationLevel.WARNING)
class VaultKmsAdapter(
    private val http: HttpClient,
    private val baseUrl: String,
    private val token: String,
) : KmsAdapter {
    @Suppress("DEPRECATION")
    private val log = LoggerFactory.getLogger(VaultKmsAdapter::class.java)

    @Volatile
    private var warned = false

    override suspend fun wrapDek(
        kid: String,
        rawKey: ByteArray,
    ): ByteArray {
        warnOnce()
        return rawKey.copyOf()
    }

    override suspend fun unwrapDek(
        kid: String,
        wrapped: ByteArray,
    ): ByteArray {
        warnOnce()
        return wrapped.copyOf()
    }

    private fun warnOnce() {
        if (!warned) {
            warned = true
            log.warn("VaultKmsAdapter is a temporary no-op stub. Replace with real Vault integration.")
        }
    }
}
