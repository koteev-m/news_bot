package db

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigException
import com.zaxxer.hikari.HikariConfig

object HikariConfigFactory {
    fun create(config: Config = ConfigFactory.load()): HikariConfig {
        val db = config.getConfig("db")
        val performanceDb = performanceDbConfig(config)
        val poolMax = envInt("DB_POOL_MAX")
            ?: performanceDb?.intOrNull("poolMax")
            ?: db.getInt("maximumPoolSize")
        val poolMinIdle = envInt("DB_POOL_MIN_IDLE")
            ?: performanceDb?.intOrNull("poolMinIdle")
            ?: db.getInt("minimumIdle")
        return HikariConfig().apply {
            jdbcUrl = db.getString("jdbcUrl")
            username = db.getString("username")
            password = db.getString("password")
            maximumPoolSize = poolMax
            minimumIdle = poolMinIdle
            driverClassName = db.getString("driver")
            schema = db.getString("schema")
            if (db.hasPath("leakDetectionThresholdMs")) {
                leakDetectionThreshold = db.getLong("leakDetectionThresholdMs")
            }
        }
    }

    private fun performanceDbConfig(config: Config): Config? {
        return if (config.hasPath("performance.db")) {
            config.getConfig("performance.db")
        } else {
            null
        }
    }

    private fun envInt(name: String): Int? {
        val raw = System.getenv(name) ?: return null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return trimmed.toIntOrNull()
    }

    private fun Config.intOrNull(path: String): Int? {
        if (!hasPath(path)) {
            return null
        }
        return try {
            getInt(path)
        } catch (_: ConfigException) {
            null
        }
    }
}
