package migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StarSubscriptionsMigrationTest {
    @Test
    fun `applies migrations and enforces constraints`() {
        val pg = runCatching {
            PostgreSQLContainer<Nothing>("postgres:15-alpine").apply {
                withDatabaseName("testdb")
                withUsername("test")
                withPassword("test")
                start()
            }
        }.getOrElse { throwable ->
            assumeTrue(false, "Docker not available: ${throwable.message}")
            return
        }

        pg.use { container ->
            val flyway = Flyway.configure()
                .dataSource(container.jdbcUrl, container.username, container.password)
                .locations("classpath:db/migration")
                .load()
            val result = flyway.migrate()
            assertTrue(result.migrationsExecuted >= 1)

            DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("select count(*) from star_subscriptions").use { rs ->
                        assertTrue(rs.next())
                    }
                }

                assertFailsWith<SQLException> {
                    conn.createStatement().use { st ->
                        st.executeUpdate(
                            "insert into star_subscriptions(user_id, plan, status, auto_renew) values (1, ' ', 'ACTIVE', false)",
                        )
                    }
                }

                assertFailsWith<SQLException> {
                    conn.createStatement().use { st ->
                        st.executeUpdate(
                            "insert into star_subscriptions(user_id, plan, status, auto_renew) values (2, 'pro', 'INVALID', false)",
                        )
                    }
                }
            }
        }
    }
}
