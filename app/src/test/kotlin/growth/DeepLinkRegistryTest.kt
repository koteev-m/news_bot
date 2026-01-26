package growth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.parametersOf

class DeepLinkRegistryTest :
    FunSpec({
        val registry = DeepLinkRegistry(limitStart = 64, limitStartApp = 512)

        test("accepts valid start payload") {
            val payload = "Abcdef12"
            val result = registry.parse(parametersOf("start" to listOf(payload)))
            val start = result as DeepLinkRegistry.Parsed.Start
            start.raw shouldBe payload
        }

        test("rejects mixed start and startapp") {
            val payload = "Abcdef12"
            registry.parse(
                parametersOf(
                    "start" to listOf(payload),
                    "startapp" to listOf(payload),
                ),
            ).shouldBeNull()
        }

        test("enforces length limits") {
            val longPayload = "a".repeat(600)
            registry.parse(parametersOf("startapp" to listOf(longPayload))).shouldBeNull()
        }

        test("rejects non base64url input") {
            registry.parse(parametersOf("start" to listOf("hello!@"))) shouldBe null
        }
    })
