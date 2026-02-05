package routes.dto

import kotlinx.serialization.Serializable

@Serializable
data class CreatePortfolioRequest(
    val name: String,
    val baseCurrency: String,
    val valuationMethod: String? = null,
)

@Serializable
data class PortfolioItemResponse(
    val id: String,
    val name: String,
    val baseCurrency: String,
    val valuationMethod: String,
    val isActive: Boolean,
    val createdAt: String,
)

@Serializable
data class ValidationError(
    val field: String,
    val message: String,
)

sealed class ValidatedCreate {
    data class Valid(
        val name: String,
        val baseCurrency: String,
        val valuationMethod: String,
    ) : ValidatedCreate()

    data class Invalid(
        val errors: List<ValidationError>,
    ) : ValidatedCreate()
}

fun CreatePortfolioRequest.validate(defaultMethod: String): ValidatedCreate {
    val errors = mutableListOf<ValidationError>()

    val normalizedName = name.trim()
    if (normalizedName.length !in NAME_LENGTH_RANGE) {
        errors += ValidationError("name", "Name must be between 2 and 64 characters")
    }

    val normalizedCurrency = baseCurrency.trim().uppercase()
    if (!CURRENCY_REGEX.matches(normalizedCurrency)) {
        errors += ValidationError("baseCurrency", "Base currency must be a 3-letter uppercase code")
    }

    val normalizedDefault = defaultMethod.trim().uppercase()
    val normalizedMethod =
        valuationMethod
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.uppercase()
            ?: normalizedDefault
    if (normalizedMethod !in ALLOWED_METHODS) {
        errors +=
            ValidationError(
                field = "valuationMethod",
                message = "Valuation method must be one of: ${ALLOWED_METHODS.joinToString(",")}",
            )
    }

    return if (errors.isEmpty()) {
        ValidatedCreate.Valid(
            name = normalizedName,
            baseCurrency = normalizedCurrency,
            valuationMethod = normalizedMethod,
        )
    } else {
        ValidatedCreate.Invalid(errors)
    }
}

private val NAME_LENGTH_RANGE = 2..64
private val CURRENCY_REGEX = Regex("^[A-Z]{3}$")
private val ALLOWED_METHODS = setOf("AVERAGE", "FIFO")
