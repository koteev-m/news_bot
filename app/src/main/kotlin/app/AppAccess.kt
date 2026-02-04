package app

import access.AccessReviewService
import access.PamService
import access.SodService
import access.accessRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import repo.AccessRepoImpl

fun Application.installAccessLayer() {
    val repo = AccessRepoImpl()
    val reviewSvc = AccessReviewService(repo)
    val sodSvc = SodService(repo)
    val pamSvc = PamService(repo)

    routing { accessRoutes(reviewSvc, sodSvc, pamSvc) }
}
