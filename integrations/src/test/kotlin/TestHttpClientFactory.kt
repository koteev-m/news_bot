package testutils

import http.configureDefaultHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData

fun testHttpClient(
    appName: String = "newsbot/test",
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData
): HttpClient = HttpClient(MockEngine) {
    configureDefaultHttpClient(appName)
    engine {
        addHandler(handler)
    }
}
