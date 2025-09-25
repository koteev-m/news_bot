package repo

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestampWithTimeZone

object BillingPlansTable : Table("billing_plans") {
    val planId = integer("plan_id").autoIncrement()
    val tier = text("tier")
    val title = text("title")
    val priceXtr = long("price_xtr")
    val isActive = bool("is_active").default(true)
    val createdAt = timestampWithTimeZone("created_at")

    override val primaryKey = PrimaryKey(planId)

    init {
        uniqueIndex("uk_billing_plans_tier", tier)
        index("idx_billing_plans_active_tier", false, isActive, tier)
    }
}

object UserSubscriptionsTable : Table("user_subscriptions") {
    val userId = long("user_id")
    val tier = text("tier")
    val status = text("status")
    val startedAt = timestampWithTimeZone("started_at")
    val expiresAt = timestampWithTimeZone("expires_at")
    val provider = text("provider").default("STARS")
    val lastPaymentId = text("last_payment_id").nullable()

    override val primaryKey = PrimaryKey(userId, provider)

    init {
        index("idx_user_subscriptions_status", false, userId, status)
    }
}

object StarPaymentsTable : Table("star_payments") {
    val paymentId = long("payment_id").autoIncrement()
    val userId = long("user_id")
    val tier = text("tier")
    val amountXtr = long("amount_xtr")
    val providerPaymentChargeId = text("provider_payment_charge_id")
    val invoicePayload = text("invoice_payload").nullable()
    val createdAt = timestampWithTimeZone("created_at")
    val status = text("status")

    override val primaryKey = PrimaryKey(paymentId)

    init {
        uniqueIndex("uk_star_payments_provider_charge", providerPaymentChargeId)
        index("idx_star_payments_user_created", false, userId, createdAt)
    }
}
