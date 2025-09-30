package billing.bot

import billing.model.BillingPlan
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import billing.model.Xtr
import billing.service.BillingService
import com.pengrad.telegrambot.utility.BotUtils
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.InlineKeyboardMarkup
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class StarsBotCommandsTest {

    @Test
    fun `plans command renders markdown list`() = runBlocking {
        val service = FakeBillingService().apply {
            plansResult = Result.success(
                listOf(
                    BillingPlan(Tier.FREE, "Free", Xtr(0), isActive = true),
                    BillingPlan(Tier.PRO, "Pro", Xtr(1500), isActive = true),
                    BillingPlan(Tier.PRO_PLUS, "Pro Plus", Xtr(3000), isActive = false),
                    BillingPlan(Tier.VIP, "Vip", Xtr(5000), isActive = true)
                )
            )
        }
        val bot = FakeTelegramBot()
        val update = messageUpdate(100, 42L, "/plans")

        StarsBotCommands.handlePlans(update, bot, service)

        val request = bot.singleRequest<SendMessage>()
        val params = request.parameters
        val text = params["text"] as String
        assertTrue(text.startsWith("*Available plans*"))
        assertTrue(text.contains("• *FREE* — Free — 0 XTR"))
        assertTrue(text.contains("• *PRO* — Pro — 1500 XTR"))
        assertTrue(text.contains("• *VIP* — Vip — 5000 XTR"))
        assertTrue(!text.contains("Pro Plus"))
    }

    @Test
    fun `buy command emits inline keyboard`() = runBlocking {
        val service = FakeBillingService()
        val bot = FakeTelegramBot()
        val update = messageUpdate(101, 55L, "/buy")

        StarsBotCommands.handleBuy(update, bot, service)

        val request = bot.singleRequest<SendMessage>()
        val params = request.parameters
        val markup = params["reply_markup"] as InlineKeyboardMarkup
        val buttons: Array<Array<InlineKeyboardButton>> = markup.inlineKeyboard()
        assertEquals(1, buttons.size)
        val row = buttons[0]
        assertEquals(listOf("buy:PRO", "buy:PRO_PLUS", "buy:VIP"), row.map { it.callbackData!! })
    }

    @Test
    fun `callback creates invoice once`() = runBlocking {
        val service = FakeBillingService().apply {
            invoiceResult = Result.success("https://invoice.test")
        }
        val bot = FakeTelegramBot()
        val update = callbackUpdate(102, userId = 77L, chatId = 77L, data = "buy:PRO")

        StarsBotCommands.handleCallback(update, bot, service)

        assertEquals(listOf(77L to Tier.PRO), service.invoiceRequests)
        val requests = bot.requests
        assertEquals(2, requests.size)
        val send = assertIs<SendMessage>(requests[0])
        val sendParams = send.parameters
        val text = sendParams["text"] as String
        assertTrue(text.contains("https://invoice.test"))
        val ack = assertIs<AnswerCallbackQuery>(requests[1])
        val ackParams = ack.parameters
        assertEquals("Invoice sent", ackParams["text"])
    }

    @Test
    fun `status reports active subscription`() = runBlocking {
        val expiresAt = java.time.Instant.parse("2025-01-01T00:00:00Z")
        val service = FakeBillingService().apply {
            subscriptionResult = Result.success(
                UserSubscription(88L, Tier.PRO_PLUS, SubStatus.ACTIVE, expiresAt.minusSeconds(3600), expiresAt)
            )
        }
        val bot = FakeTelegramBot()
        val update = messageUpdate(103, 88L, "/status")

        StarsBotCommands.handleStatus(update, bot, service)

        val request = bot.singleRequest<SendMessage>()
        val params = request.parameters
        val text = params["text"] as String
        assertTrue(text.contains("*Tier:* PRO+"))
        assertTrue(text.contains("*Status:* ACTIVE"))
        assertTrue(text.contains("*Expires:* 2025-01-01T00:00:00Z"))
    }

    @Test
    fun `status without subscription reports free`() = runBlocking {
        val service = FakeBillingService().apply {
            subscriptionResult = Result.success(null)
        }
        val bot = FakeTelegramBot()
        val update = messageUpdate(104, 91L, "/status")

        StarsBotCommands.handleStatus(update, bot, service)

        val request = bot.singleRequest<SendMessage>()
        val params = request.parameters
        val text = params["text"] as String
        assertTrue(text.contains("*Tier:* FREE"))
        assertTrue(text.contains("*Status:* EXPIRED"))
    }

    @Test
    fun `duplicate update is ignored`() = runBlocking {
        val service = FakeBillingService().apply {
            plansResult = Result.success(
                listOf(BillingPlan(Tier.PRO, "Pro", Xtr(1000), isActive = true))
            )
        }
        val bot = FakeTelegramBot()
        val update = messageUpdate(105, 123L, "/plans")

        StarsBotCommands.handlePlans(update, bot, service)
        StarsBotCommands.handlePlans(update, bot, service)

        assertEquals(1, bot.requests.size)
    }

    @Test
    fun `service errors map to user friendly message`() = runBlocking {
        val service = FakeBillingService().apply {
            invoiceResult = Result.failure(NoSuchElementException("missing"))
        }
        val bot = FakeTelegramBot()
        val update = callbackUpdate(106, userId = 66L, chatId = 66L, data = "buy:VIP")

        StarsBotCommands.handleCallback(update, bot, service)

        val request = assertIs<SendMessage>(bot.requests[0])
        val params = request.parameters
        val text = params["text"] as String
        assertEquals("Plan not found", text)
    }

    private fun messageUpdate(updateId: Int, userId: Long, text: String): Update {
        val json = """
            {
              "update_id": $updateId,
              "message": {
                "message_id": 1,
                "from": {"id": $userId, "is_bot": false, "first_name": "User"},
                "chat": {"id": $userId, "type": "private"},
                "date": 0,
                "text": "$text"
              }
            }
        """
        return BotUtils.parseUpdate(json)
    }

    private fun callbackUpdate(updateId: Int, userId: Long, chatId: Long, data: String): Update {
        val json = """
            {
              "update_id": $updateId,
              "callback_query": {
                "id": "${'$'}{updateId}cb",
                "from": {"id": $userId, "is_bot": false, "first_name": "User"},
                "message": {
                  "message_id": 9,
                  "chat": {"id": $chatId, "type": "private"}
                },
                "data": "$data"
              }
            }
        """
        return BotUtils.parseUpdate(json)
    }

    private class FakeBillingService : BillingService {
        var plansResult: Result<List<BillingPlan>> = Result.success(emptyList())
        var invoiceResult: Result<String> = Result.success("https://noop")
        var subscriptionResult: Result<UserSubscription?> = Result.success(null)
        val invoiceRequests = mutableListOf<Pair<Long, Tier>>()

        override suspend fun listPlans(): Result<List<BillingPlan>> = plansResult

        override suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String> {
            invoiceRequests += userId to tier
            return invoiceResult
        }

        override suspend fun applySuccessfulPayment(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<Unit> = Result.success(Unit)

        override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> = subscriptionResult
    }

    private class FakeTelegramBot : TelegramBot("test-token") {
        val requests = mutableListOf<BaseRequest<*, *>>()

        @Suppress("UNCHECKED_CAST")
        override fun <T : BaseRequest<T, R>, R : BaseResponse> execute(request: BaseRequest<T, R>): R {
            requests += request
            val ctor = BaseResponse::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            return ctor.newInstance() as R
        }

        inline fun <reified T : BaseRequest<*, *>> singleRequest(): T {
            assertEquals(1, requests.size)
            val request = requests.first()
            assertIs<T>(request)
            return request
        }
    }
}
