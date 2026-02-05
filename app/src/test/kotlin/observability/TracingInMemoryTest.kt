package observability

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TracingInMemoryTest {
    @Test
    fun server_span_is_recorded() =
        testApplication {
            val exporter = InMemorySpanExporter.create()
            val provider =
                SdkTracerProvider
                    .builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                    .build()
            val sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
            GlobalOpenTelemetry.resetForTest()
            GlobalOpenTelemetry.set(sdk)

            application {
                installTracing()
                routing { get("/ping") { call.respondText("pong") } }
            }

            val resp = client.get("/ping")
            assertEquals(HttpStatusCode.OK, resp.status)
            val spans = exporter.finishedSpanItems
            assertTrue(spans.isNotEmpty(), "expected at least one server span")
            exporter.reset()
        }
}
