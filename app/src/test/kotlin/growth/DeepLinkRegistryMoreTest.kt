package growth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DeepLinkRegistryMoreTest :
    FunSpec({
        val reg = DeepLinkRegistry(limitStart = 12, limitStartApp = 12)

        test("accepts short code length 8-12") {
            reg.parseStart("Abcdef12")!!.raw shouldBe "Abcdef12"
            reg.parseStartApp("Abcdef123456")!!.raw shouldBe "Abcdef123456"
        }

        test("rejects invalid characters") {
            reg.parseStart("hello@") shouldBe null
        }

        test("rejects too long start") {
            reg.parseStart("a".repeat(13)) shouldBe null
        }

        test("rejects too long startapp") {
            reg.parseStartApp("a".repeat(13)) shouldBe null
        }
    })
