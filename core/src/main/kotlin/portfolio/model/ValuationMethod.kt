package portfolio.model

import kotlinx.serialization.Serializable

@Serializable
enum class ValuationMethod {
    FIFO,
    AVERAGE,
}
