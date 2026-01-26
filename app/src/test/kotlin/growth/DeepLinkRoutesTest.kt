package growth

import deeplink.DeepLinkPayload
import deeplink.DeepLinkType
import deeplink.InMemoryDeepLinkStore
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlin.time.Duration.Companion.days

class DeepLinkRoutesTest : FunSpec({
    test("cta redirect 302 with canonical payload metric path") {
        testApplication {
            val store = InMemoryDeepLinkStore()
            environment {
                config = io.ktor.server.config.MapApplicationConfig(
                    "telegram.botUsername" to "my_test_bot",
                    "growth.limits.start" to "12",
                    "growth.limits.startapp" to "12",
                )
            }
            application { installGrowthRoutes(SimpleMeterRegistry(), store) }

            val client = createClient {
                followRedirects = false
                expectSuccess = false
            }

            val payload = DeepLinkPayload(type = DeepLinkType.TICKER, id = "SBER")
            val shortCode = store.put(payload, 14.days)
            val resp = client.get("/r/cta/123?ab=A2&start=$shortCode")
            resp.status shouldBe HttpStatusCode.Found
            // Проверяем Location:
            resp.headers["Location"] shouldBe "https://t.me/my_test_bot?start=$shortCode"
        }
    }

    test("cta redirect 302 when start invalid -> homepage") {
        testApplication {
            val store = InMemoryDeepLinkStore()
            environment {
                config = io.ktor.server.config.MapApplicationConfig(
                    "telegram.botUsername" to "my_test_bot",
                    "growth.limits.start" to "12",
                    "growth.limits.startapp" to "12",
                )
            }
            application { installGrowthRoutes(SimpleMeterRegistry(), store) }

            val client = createClient {
                followRedirects = false
                expectSuccess = false
            }

            val resp = client.get("/r/cta/123?start==") // заведомо невалидно
            resp.status shouldBe HttpStatusCode.Found
            resp.headers["Location"] shouldBe "https://t.me/my_test_bot"
        }
    }

    test("app redirect 302 when startapp invalid -> homepage") {
        testApplication {
            val meterRegistry = SimpleMeterRegistry()
            val store = InMemoryDeepLinkStore()
            environment {
                config = io.ktor.server.config.MapApplicationConfig(
                    "telegram.botUsername" to "my_test_bot",
                    "growth.limits.start" to "12",
                    "growth.limits.startapp" to "12",
                )
            }
            application { installGrowthRoutes(meterRegistry, store) }

            val client = createClient {
                followRedirects = false
                expectSuccess = false
            }

            val resp = client.get("/r/app?startapp==") // заведомо невалидно
            resp.status shouldBe HttpStatusCode.Found
            resp.headers["Location"] shouldBe "https://t.me/my_test_bot"
            meterRegistry.find("bot_start_total").counter().shouldBe(null)
        }
    }

    test("app redirect 302 with bot_start_total on valid startapp") {
        testApplication {
            val meterRegistry = SimpleMeterRegistry()
            val store = InMemoryDeepLinkStore()
            environment {
                config = io.ktor.server.config.MapApplicationConfig(
                    "telegram.botUsername" to "my_test_bot",
                    "growth.limits.start" to "12",
                    "growth.limits.startapp" to "12",
                )
            }
            application { installGrowthRoutes(meterRegistry, store) }

            val client = createClient {
                followRedirects = false
                expectSuccess = false
            }

            val payload = DeepLinkPayload(type = DeepLinkType.TICKER, id = "BTC")
            val shortCode = store.put(payload, 14.days)
            val resp = client.get("/r/app?startapp=$shortCode")
            resp.status shouldBe HttpStatusCode.Found
            resp.headers["Location"] shouldBe "https://t.me/my_test_bot?startapp=$shortCode"
            meterRegistry.get("bot_start_total").tag("payload", "TICKER_BTC").counter().count() shouldBe 1.0
        }
    }
})
