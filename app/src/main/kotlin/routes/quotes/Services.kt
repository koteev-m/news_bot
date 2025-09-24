package routes.quotes

import io.ktor.util.AttributeKey
import portfolio.service.PricingService

object Services {
    val Key: AttributeKey<PricingService> = AttributeKey("QuotesPricingService")
}
