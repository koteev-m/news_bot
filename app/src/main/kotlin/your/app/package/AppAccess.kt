package your.app.package

import io.ktor.server.application.*
import io.ktor.server.routing.*
import access.*
import repo.AccessRepoImpl

fun Application.installAccessLayer() {
    val repo = AccessRepoImpl()
    val reviewSvc = AccessReviewService(repo)
    val sodSvc = SodService(repo)
    val pamSvc = PamService(repo)

    routing { accessRoutes(reviewSvc, sodSvc, pamSvc) }
}
