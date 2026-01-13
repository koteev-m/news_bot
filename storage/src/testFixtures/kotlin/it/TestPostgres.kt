package it

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Assertions.assertTrue
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
object TestPostgres {
    @Container
    @JvmStatic
    val pg: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("newsbot")
        .withUsername("test")
        .withPassword("test")
        .withEnv("TZ", "UTC")

    fun dataSource(): HikariDataSource {
        if (!pg.isRunning) {
            synchronized(pg) {
                if (!pg.isRunning) {
                    pg.start()
                }
            }
        }
        val config = HikariConfig().apply {
            jdbcUrl = pg.jdbcUrl
            username = pg.username
            password = pg.password
            driverClassName = pg.driverClassName
            maximumPoolSize = 8
            minimumIdle = 1
            isAutoCommit = false
        }
        return HikariDataSource(config)
    }

    fun migrate(dataSource: DataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun connectExposed(dataSource: DataSource) {
        Database.connect(dataSource)
    }

    fun assumeDockerAvailable() {
        val check = runCatching { DockerClientFactory.instance().isDockerAvailable }
        val dockerAvailable = check.getOrElse { false }
        val message = check.exceptionOrNull()?.let { "Docker not available: ${it.message}" }
            ?: "Docker is required for integration tests"
        val inCi = System.getenv("CI") == "true"
        if (inCi) {
            assertTrue(dockerAvailable, message)
        } else {
            Assumptions.assumeTrue(dockerAvailable, message)
        }
    }
}
