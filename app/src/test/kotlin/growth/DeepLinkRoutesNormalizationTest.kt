package growth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DeepLinkRoutesNormalizationTest :
    FunSpec({
        test("normalizeAbVariant handles common inputs") {
            normalizeAbVariant("") shouldBe "NA"
            normalizeAbVariant("A2") shouldBe "A"
            normalizeAbVariant("a") shouldBe "A"
            normalizeAbVariant("B1") shouldBe "B"
            normalizeAbVariant("control") shouldBe "NA"
        }

        test("normalizeUserAgent trims, drops empty, and caps length") {
            normalizeUserAgent("   ") shouldBe null
            normalizeUserAgent("  Mozilla  ") shouldBe "Mozilla"

            val oversized = "a".repeat(USER_AGENT_MAX_LEN + 10)
            val normalized = normalizeUserAgent(oversized)
            normalized?.length shouldBe USER_AGENT_MAX_LEN
        }

        test("parsePostId returns only positive long values") {
            parsePostId("123") shouldBe 123L
            parsePostId("0") shouldBe null
            parsePostId("-1") shouldBe null
            parsePostId("abc") shouldBe null
        }
    })
