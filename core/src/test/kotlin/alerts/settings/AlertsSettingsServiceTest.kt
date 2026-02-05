package alerts.settings

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertsSettingsServiceTest {
    private val defaults =
        AlertsConfig(
            quiet = QuietHours(start = "22:00", end = "07:00"),
            budget = Budget(maxPushesPerDay = 6),
            hysteresis = Hysteresis(enterPct = Percent(2.0), exitPct = Percent(1.5)),
            cooldownMinutes = 60,
            dynamic = DynamicScale(enabled = false, min = 0.7, max = 1.3),
            matrix =
            MatrixV11(
                portfolioDayPct = Percent(2.0),
                portfolioDrawdown = Percent(5.0),
                perClass =
                mapOf(
                    "MOEX_BLUE" to Thresholds(Percent(2.0), Percent(4.0), volMultFast = 1.8),
                    "MOEX_SECOND" to Thresholds(Percent(3.0), Percent(6.0), volMultFast = 2.2),
                ),
            ),
        )

    @Test
    fun `merge applies overrides to config`() {
        val patch =
            AlertsOverridePatch(
                cooldownMinutes = 120,
                budgetPerDay = 8,
                quietHours = QuietHoursPatch(start = "23:30"),
                hysteresis = HysteresisPatch(exitPct = 1.2),
                dynamic = DynamicPatch(enabled = true, min = 0.8),
                thresholds =
                mapOf(
                    "MOEX_BLUE" to ThresholdsPatch(pctFast = 2.5),
                    "MOEX_SECOND" to ThresholdsPatch(volMultFast = 3.0),
                ),
            )

        val merged = defaults.merge(patch)

        assertEquals(120, merged.cooldownMinutes)
        assertEquals(8, merged.budget.maxPushesPerDay)
        assertEquals("23:30", merged.quiet.start)
        assertEquals(1.2, merged.hysteresis.exitPct.value)
        assertTrue(merged.dynamic.enabled)
        assertEquals(0.8, merged.dynamic.min)
        assertEquals(
            2.5,
            merged.matrix.perClass
                .getValue("MOEX_BLUE")
                .pctFast.value,
        )
        assertEquals(
            3.0,
            merged.matrix.perClass
                .getValue("MOEX_SECOND")
                .volMultFast,
        )
    }

    @Test
    fun `validate detects invalid values`() {
        val patch =
            AlertsOverridePatch(
                cooldownMinutes = 4,
                budgetPerDay = 0,
                quietHours = QuietHoursPatch(start = "25:61"),
                hysteresis = HysteresisPatch(enterPct = 1.0, exitPct = 1.2),
                dynamic = DynamicPatch(min = 1.5, max = 0.8),
                thresholds = mapOf("MOEX_BLUE" to ThresholdsPatch(pctFast = -1.0, pctDay = 0.0, volMultFast = -0.5)),
            )

        val errors = patch.validate()

        assertTrue(errors.any { it.contains("cooldownMinutes") })
        assertTrue(errors.any { it.contains("budgetPerDay") })
        assertTrue(errors.any { it.contains("quietHours.start") })
        assertTrue(errors.any { it.contains("enterPct") })
        assertTrue(errors.any { it.contains("dynamic.min") })
        assertTrue(errors.any { it.contains("dynamic.max") })
        assertTrue(errors.any { it.contains("thresholds[MOEX_BLUE].pctFast") })
    }

    @Test
    fun `updatesFlow increments and effective config reflects overrides`() =
        runTest {
            val repository = InMemoryAlertsRepository()
            val service = AlertsSettingsServiceImpl(defaults, repository, this)
            val userId = 42L

            assertEquals(0L, service.updatesFlow.value)

            service.upsert(userId, AlertsOverridePatch(cooldownMinutes = 90))
            assertEquals(1L, service.updatesFlow.value)
            assertEquals(90, service.effectiveFor(userId).cooldownMinutes)

            service.upsert(userId, AlertsOverridePatch(quietHours = QuietHoursPatch(start = "01:00")))
            assertEquals(2L, service.updatesFlow.value)
            val effective = service.effectiveFor(userId)
            assertEquals("01:00", effective.quiet.start)
            assertEquals(90, effective.cooldownMinutes)
            assertTrue(repository.store.containsKey(userId))
        }

    private class InMemoryAlertsRepository : AlertsSettingsRepository {
        val store = mutableMapOf<Long, String>()

        override suspend fun upsert(
            userId: Long,
            json: String,
        ) {
            store[userId] = json
        }

        override suspend fun find(userId: Long): String? = store[userId]
    }
}
