package http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class ClientTracingPropagatesTest {
    @Test
    fun injects_traceparent() = runTest {
        val exporter = InMemorySpanExporter.create()
        val provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val propagators = ContextPropagators.create(
            TextMapPropagator.composite(
                W3CTraceContextPropagator.getInstance(),
                W3CBaggagePropagator.getInstance()
            )
        )
        val otel = OpenTelemetrySdk.builder()
            .setTracerProvider(provider)
            .setPropagators(propagators)
            .build()
        GlobalOpenTelemetry.resetForTest()
        GlobalOpenTelemetry.set(otel)

        var seenHeader: String? = null
        val engine = MockEngine { request ->
            seenHeader = request.headers["traceparent"]
            respond(
                content = "ok",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "text/plain")
            )
        }
        val httpClient = HttpClient(engine) {
            install(ClientTracing)
        }

        val response = httpClient.get("https://example.test/ok")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
        assertTrue(seenHeader != null && seenHeader!!.startsWith("00-"))
        exporter.reset()
    }
}
