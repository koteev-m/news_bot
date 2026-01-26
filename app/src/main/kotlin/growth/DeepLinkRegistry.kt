package growth

import io.ktor.http.Parameters
import org.slf4j.LoggerFactory

/**
 * Реестр deeplink-пейлоадов (?start / ?startapp) с безопасной валидацией.
 * Лимиты подтягиваются из конфига (символы, не байты).
 * Правила:
 *  - start:   ^[A-Za-z0-9_-]{1,limitStart}$
 *  - startapp:^[A-Za-z0-9_-]{1,limitStartApp}$
 * Логи не содержат значения payload, только длину/валидность.
 */
class DeepLinkRegistry(
    private val limitStart: Int,
    private val limitStartApp: Int,
) {
    private val log = LoggerFactory.getLogger(DeepLinkRegistry::class.java)
    private val startLimitSafe = limitStart.coerceAtLeast(1)
    private val startAppLimitSafe = limitStartApp.coerceAtLeast(1)
    private val startRegex = Regex("^[A-Za-z0-9_-]{1,$startLimitSafe}$")
    private val startAppRegex = Regex("^[A-Za-z0-9_-]{1,$startAppLimitSafe}$")

    sealed class Parsed(open val raw: String) {
        data class Start(
            override val raw: String,
        ) : Parsed(raw)

        data class StartApp(
            override val raw: String,
        ) : Parsed(raw)
    }

    fun parse(parameters: Parameters): Parsed? {
        val starts = parameters.getAll("start")?.filterNot { it.isBlank() }.orEmpty()
        val startApps = parameters.getAll("startapp")?.filterNot { it.isBlank() }.orEmpty()
        if (starts.isNotEmpty() && startApps.isNotEmpty()) {
            log.debug("deeplink: mixed params rejected (start+startapp)")
            return null
        }
        return when {
            starts.size == 1 -> parseStart(starts.first())
            startApps.size == 1 -> parseStartApp(startApps.first())
            else -> null
        }
    }

    fun parseStart(raw: String): Parsed.Start? {
        if (!startRegex.matches(raw)) {
            log.debug("deeplink:start invalid pattern len={}", raw.length)
            return null
        }
        return Parsed.Start(raw = raw)
    }

    fun parseStartApp(raw: String): Parsed.StartApp? {
        if (!startAppRegex.matches(raw)) {
            log.debug("deeplink:startapp invalid pattern len={}", raw.length)
            return null
        }
        return Parsed.StartApp(raw = raw)
    }
}
