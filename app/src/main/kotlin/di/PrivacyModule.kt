package di

import io.ktor.server.application.Application
import privacy.ErasureCfg
import privacy.PrivacyConfig
import privacy.PrivacyService
import privacy.PrivacyServiceImpl
import privacy.RetentionCfg
import privacy.RetentionScheduler
import repo.PrivacyRepository

object PrivacyModule {
    data class Holder(
        val service: PrivacyService,
        val scheduler: RetentionScheduler,
        val config: PrivacyConfig,
        val adminUserIds: Set<Long>
    )

    fun install(app: Application, adminUserIds: Set<Long>): Holder {
        val conf = app.environment.config
        val cfg = PrivacyConfig(
            retention = RetentionCfg(
                analyticsDays = conf.property("privacy.retention.analyticsDays").getString().toInt(),
                alertsDays = conf.property("privacy.retention.alertsDays").getString().toInt(),
                botStartsDays = conf.property("privacy.retention.botStartsDays").getString().toInt()
            ),
            erasure = ErasureCfg(
                enabled = conf.property("privacy.erasure.enabled").getString().toBooleanStrictOrNull() ?: false,
                dryRun = conf.property("privacy.erasure.dryRun").getString().toBooleanStrictOrNull() ?: false,
                batchSize = conf.property("privacy.erasure.batchSize").getString().toInt()
            )
        )
        val repository = PrivacyRepository()
        val service = PrivacyServiceImpl(repository, cfg)
        val scheduler = RetentionScheduler(app, service)
        scheduler.start()
        return Holder(service, scheduler, cfg, adminUserIds)
    }
}
