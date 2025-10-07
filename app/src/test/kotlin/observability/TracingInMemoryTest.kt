package observability

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class TracingInMemoryTest {
    @Test
    fun server_span_created() = testApplication {
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

        application {
            routing {
                get("/ping") {
                    call.respondText("pong")
                }
            }
            intercept(ApplicationCallPipeline.Plugins) {
                withServerSpan()
            }
        }

        val response = client.get("/ping")
        assertEquals(HttpStatusCode.OK, response.status)
        val spans = exporter.finishedSpanItems
        OpenTelemetryAssertions.assertThat(spans).isNotEmpty
        exporter.reset()
    }
}
