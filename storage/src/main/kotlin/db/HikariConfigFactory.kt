package db

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig

object HikariConfigFactory {
    fun create(config: Config = ConfigFactory.load()): HikariConfig {
        val db = config.getConfig("db")
        return HikariConfig().apply {
            jdbcUrl = db.getString("jdbcUrl")
            username = db.getString("username")
            password = db.getString("password")
            maximumPoolSize = db.getInt("maximumPoolSize")
            minimumIdle = db.getInt("minimumIdle")
            driverClassName = db.getString("driver")
            schema = db.getString("schema")
            if (db.hasPath("leakDetectionThresholdMs")) {
                leakDetectionThreshold = db.getLong("leakDetectionThresholdMs")
            }
        }
    }
}
