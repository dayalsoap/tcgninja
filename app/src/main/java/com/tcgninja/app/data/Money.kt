package com.tcgninja.app.data

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

object Money {
    /** USD input only. Exact conversion: never round sub-cent values or use floating point. */
    fun parseCents(input: String): Long? {
        val value = input.trim().removePrefix("$").trim()
        if (input.isBlank()) return null
        require(Regex("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?|\\.[0-9]{1,2}").matches(value)) {
            "Enter a nonnegative USD amount with at most two decimal places"
        }
        return try {
            BigDecimal(value.replace(",", "")).movePointRight(2).longValueExact()
        } catch (error: ArithmeticException) {
            throw IllegalArgumentException("Amount is too large", error)
        }
    }

    fun format(cents: Long?): String = cents?.let {
        NumberFormat.getCurrencyInstance(Locale.US).format(BigDecimal.valueOf(it, 2))
    } ?: "Not priced"
}
