package docs

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

fun Route.apiDocsRoutes() {
    staticOpenApi()
    get("/docs/api") {
        call.respondText(
            """
            <!doctype html>
            <html>
              <head>
                <meta charset=\"utf-8\"/>
                <title>NewsBot API</title>
                <script src=\"https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js\"></script>
              </head>
              <body>
                <redoc spec-url=\"/docs/openapi.yaml\"></redoc>
              </body>
            </html>
            """.trimIndent(),
            ContentType.Text.Html,
        )
    }
}

private fun Route.staticOpenApi() {
    get("/docs/openapi.yaml") {
        val resource = this::class.java.classLoader.getResourceAsStream("api/openapi.yaml")
        if (resource == null) {
            call.respondText("OpenAPI spec not found", ContentType.Text.Plain, status = HttpStatusCode.NotFound)
            return@get
        }
        val spec = resource.bufferedReader().use { it.readText() }
        call.respondText(spec, ContentType.parse("application/yaml"))
    }
}
