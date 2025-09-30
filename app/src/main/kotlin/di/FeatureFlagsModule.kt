package di

import app.Services
import features.FeatureFlags
import features.FeatureFlagsService
import features.FeatureFlagsServiceImpl
import io.ktor.server.application.Application
import io.ktor.server.config.ApplicationConfig
import io.ktor.util.AttributeKey
import repo.FeatureOverridesRepositoryImpl

object FeatureFlagsModule {
    data class Component(
        val service: FeatureFlagsService,
        val adminUserIds: Set<Long>
    )

    private val Key: AttributeKey<Component> = AttributeKey("FeatureFlagsModule")

    fun install(application: Application): Component {
        val attributes = application.attributes
        if (attributes.contains(Key)) {
            val component = attributes[Key]
            attributes.updateServices(component)
            return component
        }

        val config = application.environment.config
        val defaults = config.loadFeatureDefaults()
        val adminIds = config.loadAdminUserIds()
        val repository = FeatureOverridesRepositoryImpl()
        val service = FeatureFlagsServiceImpl(defaults, repository)
        val component = Component(service = service, adminUserIds = adminIds)

        attributes.put(Key, component)
        attributes.updateServices(component)
        return component
    }

    private fun io.ktor.util.Attributes.updateServices(component: Component) {
        if (contains(Services.Key)) {
            val current = this[Services.Key]
            val enriched = current.withFeatureFlags(component.service, component.adminUserIds)
            put(Services.Key, enriched)
        }
    }
}

private fun ApplicationConfig.loadFeatureDefaults(): FeatureFlags {
    return FeatureFlags(
        importByUrl = getFlag("features.importByUrl", default = false),
        webhookQueue = getFlag("features.webhookQueue", default = true),
        newsPublish = getFlag("features.newsPublish", default = true),
        alertsEngine = getFlag("features.alertsEngine", default = true),
        billingStars = getFlag("features.billingStars", default = true),
        miniApp = getFlag("features.miniApp", default = true),
    )
}

private fun ApplicationConfig.loadAdminUserIds(): Set<Long> {
    return propertyOrNull("admin.adminUserIds")
        ?.getList()
        ?.mapNotNull { raw -> raw.trim().takeIf { it.isNotEmpty() }?.toLongOrNull() }
        ?.toSet()
        ?: emptySet()
}

private fun ApplicationConfig.getFlag(path: String, default: Boolean): Boolean {
    val raw = propertyOrNull(path)?.getString()?.trim() ?: return default
    return when (raw.lowercase()) {
        "true", "1", "yes", "y" -> true
        "false", "0", "no", "n" -> false
        else -> default
    }
}

private fun Services.withFeatureFlags(
    service: FeatureFlagsService,
    adminUserIds: Set<Long>
): Services {
    return copy(featureFlags = service, adminUserIds = adminUserIds)
}
