package growth

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.ktor.http.parametersOf
import java.util.Base64

class DeepLinkRegistryTest :
    FunSpec({
        val registry = DeepLinkRegistry()

        test("accepts valid start payload") {
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("ref=1".toByteArray())
            val result = registry.parse(parametersOf("start" to listOf(payload)))
            result shouldBe DeepLinkPayload.Start(decoded = "ref=1", raw = payload)
        }

        test("rejects mixed start and startapp") {
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("x".toByteArray())
            registry.parse(
                parametersOf(
                    "start" to listOf(payload),
                    "startapp" to listOf(payload),
                ),
            ).shouldBeNull()
        }

        test("enforces length limits") {
            val longPayload = Base64.getUrlEncoder().withoutPadding().encodeToString("a".repeat(600).toByteArray())
            registry.parse(parametersOf("startapp" to listOf(longPayload))).shouldBeNull()
        }

        test("rejects non base64url input") {
            registry.parse(parametersOf("start" to listOf("hello!@"))) shouldBe null
        }

        test("decoding errors are handled") {
            shouldNotThrowAny {
                registry.parse(parametersOf("start" to listOf("===")))
            }
        }
    })
