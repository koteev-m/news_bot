package observability

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.util.pipeline.PipelineContext
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapPropagator
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.time.Duration

object Tracing {
    data class Cfg(
        val enabled: Boolean,
        val serviceName: String,
        val otlpEndpoint: String,
        val samplerRatio: Double,
    )

    private val propagator: TextMapPropagator =
        TextMapPropagator.composite(
            W3CTraceContextPropagator.getInstance(),
            W3CBaggagePropagator.getInstance(),
        )

    fun readConfig(app: Application): Cfg {
        val cfg = app.environment.config
        val enabled = cfg.propertyOrNull("tracing.enabled")?.getString()?.toBooleanStrictOrNull() ?: false
        val name = cfg.propertyOrNull("tracing.serviceName")?.getString()?.ifBlank { null } ?: "newsbot-app"
        val endpoint =
            cfg.propertyOrNull("tracing.otlp.endpoint")?.getString()?.ifBlank { null } ?: "http://localhost:4317"
        val ratio = cfg.propertyOrNull("tracing.sampler.ratio")?.getString()?.toDoubleOrNull() ?: 0.1
        return Cfg(enabled, name, endpoint, ratio.coerceIn(0.0, 1.0))
    }

    fun initOpenTelemetry(cfg: Cfg): OpenTelemetry {
        val propagators = ContextPropagators.create(propagator)
        if (!cfg.enabled) {
            val tracerProvider =
                SdkTracerProvider
                    .builder()
                    .setSampler(Sampler.alwaysOff())
                    .build()
            val sdk =
                OpenTelemetrySdk
                    .builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(propagators)
                    .build()
            GlobalOpenTelemetry.resetForTest()
            GlobalOpenTelemetry.set(sdk)
            return sdk
        }

        val resource =
            Resource.getDefault().merge(
                Resource.create(Attributes.of(serviceNameKey, cfg.serviceName)),
            )

        val exporter =
            OtlpGrpcSpanExporter
                .builder()
                .setEndpoint(cfg.otlpEndpoint)
                .setTimeout(Duration.ofSeconds(5))
                .build()

        val tracerProvider =
            SdkTracerProvider
                .builder()
                .setResource(resource)
                .setSampler(Sampler.traceIdRatioBased(cfg.samplerRatio))
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build()

        val sdk =
            OpenTelemetrySdk
                .builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(propagators)
                .build()

        GlobalOpenTelemetry.resetForTest()
        GlobalOpenTelemetry.set(sdk)
        return sdk
    }

    fun tracer(): Tracer = GlobalOpenTelemetry.getTracer("newsbot-app/ktor")
}

private val serviceNameKey: AttributeKey<String> = AttributeKey.stringKey("service.name")
private val httpRequestMethodKey: AttributeKey<String> = AttributeKey.stringKey("http.request.method")
private val urlPathKey: AttributeKey<String> = AttributeKey.stringKey("url.path")
private val httpResponseStatusKey: AttributeKey<Long> = AttributeKey.longKey("http.response.status_code")

private object ApplicationCallGetter : TextMapGetter<PipelineCall> {
    override fun keys(carrier: PipelineCall?): Iterable<String> = carrier?.request?.headers?.names() ?: emptyList()

    override fun get(
        carrier: PipelineCall?,
        key: String,
    ): String? = carrier?.request?.headers?.get(key)
}

suspend fun PipelineContext<Unit, PipelineCall>.withServerSpan(
    block: suspend PipelineContext<Unit, PipelineCall>.(Span) -> Unit = { proceed() },
) {
    val tracer = Tracing.tracer()
    val request = call.request
    val parent =
        GlobalOpenTelemetry.getPropagators().textMapPropagator.extract(
            Context.current(),
            call,
            ApplicationCallGetter,
        )
    val span =
        tracer
            .spanBuilder("HTTP ${request.httpMethod.value}")
            .setSpanKind(SpanKind.SERVER)
            .setParent(parent)
            .setAttribute(httpRequestMethodKey, request.httpMethod.value)
            .setAttribute(urlPathKey, request.path())
            .startSpan()
    val scope: Scope = span.makeCurrent()
    val traceId = span.spanContext.traceId
    if (span.spanContext.isValid) {
        call.response.header("X-Request-Id", traceId)
        call.response.header("Trace-Id", traceId)
    }
    try {
        withContext(TraceContext(traceId)) {
            this@withServerSpan.block(span)
        }
        call.response.status()?.value?.let { status ->
            span.setAttribute(httpResponseStatusKey, status.toLong())
        }
    } catch (cancellation: CancellationException) {
        span.recordException(cancellation)
        span.setStatus(StatusCode.ERROR)
        throw cancellation
    } catch (err: Error) {
        span.recordException(err)
        span.setStatus(StatusCode.ERROR)
        throw err
    } catch (t: Throwable) {
        span.recordException(t)
        span.setStatus(StatusCode.ERROR)
        throw t
    } finally {
        scope.close()
        span.end()
    }
}

fun Application.installTracing() {
    val cfg = Tracing.readConfig(this)
    if (cfg.enabled) {
        Tracing.initOpenTelemetry(cfg)
    }
    intercept(ApplicationCallPipeline.Plugins) {
        withServerSpan()
    }
}
