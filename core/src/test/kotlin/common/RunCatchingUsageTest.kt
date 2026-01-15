package common

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.io.path.readText

class RunCatchingUsageTest {
    @Test
    fun `src main should not use runCatching`() {
        val root = Path.of("").toAbsolutePath()
        val matches = Files.walk(root).use { paths ->
            paths
                .filter { path -> path.toString().contains("${Path.of("src", "main")}") }
                .filter { path -> path.toString().endsWith(".kt") }
                .filterNot { path -> path.toString().contains("${Path.of("build")}") }
                .mapNotNull { path -> path.takeIf { it.readText().contains(Regex("\\brunCatching\\(")) } }
                .toList()
        }

        assertTrue(
            matches.isEmpty(),
            "Найдены вызовы runCatching в src/main: ${matches.joinToString()}"
        )
    }
}
