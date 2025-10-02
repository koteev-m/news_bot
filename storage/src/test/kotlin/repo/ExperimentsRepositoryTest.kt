package repo

import ab.Assignment
import ab.Experiment
import it.TestDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

class ExperimentsRepositoryTest {
    @Test
    fun `upsert and assignments persist`() = runBlocking {
        TestDb.withMigratedDatabase {
            val repository = ExperimentsRepository()

            repository.upsert(Experiment(key = "cta_copy", enabled = true, traffic = mapOf("A" to 50, "B" to 50)))
            val experiments = repository.list()
            assertEquals(1, experiments.size)
            val experiment = experiments.single()
            assertEquals("cta_copy", experiment.key)
            assertEquals(mapOf("A" to 50, "B" to 50), experiment.traffic)
            assertEquals(true, experiment.enabled)

            repository.upsert(Experiment(key = "cta_copy", enabled = false, traffic = mapOf("A" to 100)))
            val updated = repository.list().single()
            assertFalse(updated.enabled)
            assertEquals(mapOf("A" to 100), updated.traffic)

            val missing = repository.getAssignment(userId = 777, key = "cta_copy")
            assertNull(missing)

            repository.saveAssignment(Assignment(userId = 777, key = "cta_copy", variant = "A"))
            val stored = repository.getAssignment(userId = 777, key = "cta_copy")
            assertNotNull(stored)
            assertEquals("A", stored.variant)

            repository.saveAssignment(Assignment(userId = 777, key = "cta_copy", variant = "B"))
            val unchanged = repository.getAssignment(userId = 777, key = "cta_copy")
            assertNotNull(unchanged)
            assertEquals("A", unchanged.variant)
        }
    }
}
