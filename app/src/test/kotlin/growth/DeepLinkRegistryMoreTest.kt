package growth

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.Base64

class DeepLinkRegistryMoreTest : FunSpec({
    val reg = DeepLinkRegistry(limitStart = 64, limitStartApp = 512)

    fun b64u(s: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    test("PRD: TICKER_SBER v1") {
        val json = """{"v":1,"t":"w","s":"SBER","b":"TQBR","a":"A2"}"""
        val p = reg.parseStart(b64u(json))!!
        p.canonicalId shouldBe "TICKER_SBER"
    }

    test("PRD: TICKER_BTC v1") {
        val json = """{"v":1,"t":"w","s":"BTC","h":"2p","a":"B3"}"""
        val p = reg.parseStart(b64u(json))!!
        p.canonicalId shouldBe "TICKER_BTC"
    }

    test("PRD: TOPIC_CBRATE v1") {
        val json = """{"v":1,"t":"topic","i":"CBRATE","a":"C1"}"""
        val p = reg.parseStart(b64u(json))!!
        p.canonicalId shouldBe "TOPIC_CBRATE"
    }

    test("PRD: PORTFOLIO v1") {
        val json = """{"v":1,"t":"p"}"""
        val p = reg.parseStart(b64u(json))!!
        p.canonicalId shouldBe "PORTFOLIO"
    }

    test("reject: non-base64url char") {
        reg.parseStart("hello@").shouldBeNull()
    }

    test("reject: start too long (>64)") {
        val payload = b64u("a".repeat(600))
        reg.parseStart(payload).shouldBeNull()
    }

    test("reject: startapp too long (>512)") {
        val payload = b64u("a".repeat(1200))
        reg.parseStartApp(payload).shouldBeNull()
    }
})
