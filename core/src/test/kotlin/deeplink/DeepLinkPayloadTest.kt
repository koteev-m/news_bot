package deeplink

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DeepLinkPayloadTest :
    FunSpec({
        test("canonical label for ticker") {
            val payload = DeepLinkPayload(type = DeepLinkType.TICKER, id = "SBER")
            payload.canonicalLabel() shouldBe "TICKER_SBER"
        }

        test("canonical label for topic") {
            val payload = DeepLinkPayload(type = DeepLinkType.TOPIC, id = "CBRATE")
            payload.canonicalLabel() shouldBe "TOPIC_CBRATE"
        }

        test("canonical label for portfolio") {
            val payload = DeepLinkPayload(type = DeepLinkType.PORTFOLIO)
            payload.canonicalLabel() shouldBe "PORTFOLIO"
        }

        test("invalid token maps to unknown") {
            val payload = DeepLinkPayload(type = DeepLinkType.TICKER, id = "BTC/USDT")
            payload.canonicalLabel() shouldBe "TICKER_UNKNOWN"
        }
    })
