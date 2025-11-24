package growth

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.util.Base64

class DeepLinkRoutesTest : FunSpec({
    fun b64u(s: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    test("cta redirect 302 with canonical payload metric path") {
        testApplication {
            environment {
                config = io.ktor.server.config.MapApplicationConfig(
                    "telegram.botUsername" to "my_test_bot",
                    "growth.limits.start" to "64",
                    "growth.limits.startapp" to "512",
                )
            }
            application { installGrowthRoutes(SimpleMeterRegistry()) }

            val client = createClient {
                followRedirects = false
                expectSuccess = false
            }

            val payload = b64u("""{"v":1,"t":"w","s":"SBER"}""")
            val resp = client.get("/r/cta/123?ab=A2&start=$payload")
            resp.status shouldBe HttpStatusCode.Found
            // Проверяем Location:
            resp.headers["Location"] shouldBe "https://t.me/my_test_bot?start=$payload"
        }
    }

    test("cta redirect 302 when start invalid -> homepage") {
        testApplication {
            environment {
                config = io.ktor.server.config.MapApplicationConfig(
                    "telegram.botUsername" to "my_test_bot",
                    "growth.limits.start" to "64",
                    "growth.limits.startapp" to "512",
                )
            }
            application { installGrowthRoutes(SimpleMeterRegistry()) }

            val client = createClient {
                followRedirects = false
                expectSuccess = false
            }

            val resp = client.get("/r/cta/123?start==")  // заведомо невалидно
            resp.status shouldBe HttpStatusCode.Found
            resp.headers["Location"] shouldBe "https://t.me/my_test_bot"
        }
    }

    test("app redirect 302 when startapp invalid -> homepage") {
        testApplication {
            environment {
                config = io.ktor.server.config.MapApplicationConfig(
                    "telegram.botUsername" to "my_test_bot",
                    "growth.limits.start" to "64",
                    "growth.limits.startapp" to "512",
                )
            }
            application { installGrowthRoutes(SimpleMeterRegistry()) }

            val client = createClient {
                followRedirects = false
                expectSuccess = false
            }

            val resp = client.get("/r/app?startapp==")  // заведомо невалидно
            resp.status shouldBe HttpStatusCode.Found
            resp.headers["Location"] shouldBe "https://t.me/my_test_bot"
        }
    }
})
