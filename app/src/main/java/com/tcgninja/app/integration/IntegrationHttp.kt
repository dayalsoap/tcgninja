package com.tcgninja.app.integration

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class IntegrationHttp(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        // Do not replay a billed recognition request automatically.
        .retryOnConnectionFailure(false)
        .build()
) {
    suspend fun text(request: Request, maxBytes: Int, contentType: String): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(networkError())
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val text = response.use {
                            if (!it.isSuccessful) throw statusError(it.code)
                            val body = it.body ?: invalidResponse()
                            val mime = body.contentType()
                            if (mime == null || "${mime.type}/${mime.subtype}" != contentType) invalidResponse()
                            if (body.contentLength() > maxBytes) invalidResponse()
                            val buffer = ByteArray(8192)
                            val output = ByteArrayOutputStream()
                            body.byteStream().use { input ->
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count == -1) break
                                    if (output.size() + count > maxBytes) invalidResponse()
                                    output.write(buffer, 0, count)
                                }
                            }
                            output.toString("UTF-8")
                        }
                        if (continuation.isActive) continuation.resume(text)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is IntegrationException) e else networkError()
                        )
                    }
                }
            })
        }

    private fun networkError() = IntegrationException(
        IntegrationException.Kind.NETWORK, "Could not reach the service. Check your connection and try again."
    )

    internal fun statusError(status: Int): IntegrationException = when (status) {
        401 -> IntegrationException(IntegrationException.Kind.AUTHENTICATION,
            "The service rejected the API key. Update it in Settings.")
        403 -> IntegrationException(IntegrationException.Kind.UNAVAILABLE,
            "The service denied access. Check account permissions or open the source in your browser.")
        404 -> IntegrationException(IntegrationException.Kind.NOT_FOUND,
            "The requested model or product was not found. Check the model setting or product link.")
        429 -> IntegrationException(IntegrationException.Kind.RATE_LIMIT,
            "The service's rate or account limit was reached. Wait and check your account before retrying.")
        400, 413, 422 -> IntegrationException(IntegrationException.Kind.UNSUPPORTED,
            "The service could not accept this request. Check that the configured model supports images and structured JSON.")
        in 300..399 -> IntegrationException(IntegrationException.Kind.UNAVAILABLE,
            "The source redirected to another page. Confirm the current product URL in your browser.")
        else -> IntegrationException(IntegrationException.Kind.UNAVAILABLE,
            "The service is temporarily unavailable. Try again later.")
    }
}
