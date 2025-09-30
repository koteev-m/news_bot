package billing

import billing.model.BillingPlan
import billing.model.SubStatus
import billing.model.Tier
import billing.model.UserSubscription
import billing.service.BillingService
import billing.bot.StarsBotCommands
import billing.bot.StarsBotRouter
import billing.bot.StarsBotRouter.BotRoute
import billing.bot.StarsBotRouter.BotRoute.Buy
import billing.bot.StarsBotRouter.BotRoute.Callback
import billing.bot.StarsBotRouter.BotRoute.Plans
import billing.bot.StarsBotRouter.BotRoute.Status
import com.pengrad.telegrambot.TelegramBot
import com.pengrad.telegrambot.request.BaseRequest
import com.pengrad.telegrambot.request.SendMessage
import com.pengrad.telegrambot.response.BaseResponse
import com.pengrad.telegrambot.response.SendResponse
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

class WebhookIntegrationTest {

    @Test
    fun `webhook reads body once and routes payment and command`() = testApplication {
        environment {
            config = MapApplicationConfig().apply {
                put("telegram.webhookSecret", "secret")
                put("telegram.botToken", "dummy-token")
                put("billing.defaultDurationDays", "30")
                put("security.jwtSecret", "secret-key")
                put("security.issuer", "test-issuer")
                put("security.audience", "test-audience")
                put("security.realm", "test-realm")
                put("security.accessTtlMinutes", "60")
            }
        }

        val billing = RecordingBillingService()
        val bot = RecordingTelegramBot()
        val receiveCounter = AtomicInteger(0)
        application {
            val appInstance = this
            appInstance.install(createApplicationPlugin(name = "ReceiveCounter") {
                onCallReceive { _, _ -> receiveCounter.incrementAndGet() }
            })
            appInstance.routing {
                post("/telegram/webhook") {
                    val expectedSecret = environment?.config?.propertyOrNull("telegram.webhookSecret")?.getString()
                    val providedSecret = call.request.headers["X-Telegram-Bot-Api-Secret-Token"]
                    if (expectedSecret.isNullOrBlank() || providedSecret != expectedSecret) {
                        call.respond(HttpStatusCode.Forbidden)
                        return@post
                    }

                    val raw = call.receiveText()
                    val update = StarsWebhookHandler.json.decodeFromString(TgUpdate.serializer(), raw)
                    StarsWebhookHandler.handleParsed(call, update, billing)
                    val botUpdate = com.pengrad.telegrambot.utility.BotUtils.parseUpdate(raw)
                    when (StarsBotRouter.route(botUpdate)) {
                        Plans -> StarsBotCommands.handlePlans(botUpdate, bot, billing)
                        Buy -> StarsBotCommands.handleBuy(botUpdate, bot, billing)
                        Status -> StarsBotCommands.handleStatus(botUpdate, bot, billing)
                        Callback -> StarsBotCommands.handleCallback(botUpdate, bot, billing)
                        BotRoute.Unknown -> Unit
                    }
                }
            }
        }

        val response = client.post("/telegram/webhook") {
            header(HttpHeaders.ContentType, ContentType.Application.Json)
            header("X-Telegram-Bot-Api-Secret-Token", "secret")
            setBody(updateJson())
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, receiveCounter.get())
        assertEquals(1, billing.appliedPayments.size)
        withTimeout(2000) { bot.executedSignal.await() }
        val messages = bot.executed.filterIsInstance<SendMessage>()
        assertEquals(1, messages.size)
        assertEquals("charge-1", billing.appliedPayments.first().providerPaymentId)
    }

    private fun updateJson(): String = """
        {
          "update_id": 1001,
          "message": {
            "message_id": 42,
            "date": 1716740000,
            "text": "/status",
            "from": {"id": 555123, "is_bot": false, "first_name": "Test"},
            "chat": {"id": 555123, "type": "private"},
            "successful_payment": {
              "currency": "XTR",
              "total_amount": 777,
              "invoice_payload": "555123:PRO:abc",
              "provider_payment_charge_id": "charge-1"
            }
          }
        }
    """.trimIndent()

    private class RecordingBillingService : BillingService {
        data class ApplyCall(
            val userId: Long,
            val tier: Tier,
            val amountXtr: Long,
            val providerPaymentId: String?,
            val payload: String?
        )

        val appliedPayments = mutableListOf<ApplyCall>()

        override suspend fun applySuccessfulPayment(
            userId: Long,
            tier: Tier,
            amountXtr: Long,
            providerPaymentId: String?,
            payload: String?
        ): Result<Unit> {
            appliedPayments += ApplyCall(userId, tier, amountXtr, providerPaymentId, payload)
            return Result.success(Unit)
        }

        override suspend fun listPlans(): Result<List<BillingPlan>> {
            return Result.failure(UnsupportedOperationException("not used"))
        }

        override suspend fun createInvoiceFor(userId: Long, tier: Tier): Result<String> {
            return Result.failure(UnsupportedOperationException("not used"))
        }

        override suspend fun getMySubscription(userId: Long): Result<UserSubscription?> {
            val subscription = UserSubscription(
                userId = userId,
                tier = Tier.PRO,
                status = SubStatus.ACTIVE,
                startedAt = Instant.parse("2024-01-01T00:00:00Z"),
                expiresAt = Instant.parse("2024-02-01T00:00:00Z")
            )
            return Result.success(subscription)
        }
    }

    private class RecordingTelegramBot : TelegramBot("test-token") {
        val executed = mutableListOf<BaseRequest<*, *>>()
        val executedSignal = CompletableDeferred<Unit>()

        override fun <T : BaseRequest<T, R>, R : BaseResponse> execute(request: BaseRequest<T, R>): R {
            executed += request
            if (!executedSignal.isCompleted) {
                executedSignal.complete(Unit)
            }
            @Suppress("UNCHECKED_CAST")
            return when (request) {
                is SendMessage -> newSendResponse() as R
                else -> newBaseResponse() as R
            }
        }

        private fun newSendResponse(): SendResponse {
            val constructor = SendResponse::class.java.getDeclaredConstructor()
            constructor.isAccessible = true
            return constructor.newInstance()
        }

        private fun newBaseResponse(): BaseResponse {
            val constructor = BaseResponse::class.java.getDeclaredConstructor()
            constructor.isAccessible = true
            return constructor.newInstance()
        }
    }
}
