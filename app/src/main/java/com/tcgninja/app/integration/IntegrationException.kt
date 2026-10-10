package com.tcgninja.app.integration

/** Safe to display; never contains provider response bodies, credentials, or image data. */
class IntegrationException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind {
        SETTINGS, AUTHENTICATION, RATE_LIMIT, NETWORK, UNAVAILABLE, NOT_FOUND,
        INVALID_RESPONSE, AMBIGUOUS_MATCH, UNSUPPORTED, IMAGE
    }
}

internal fun invalidResponse(): Nothing = throw IntegrationException(
    IntegrationException.Kind.INVALID_RESPONSE,
    "The service returned an incomplete or unexpected response. Try again or enter the card manually."
)
