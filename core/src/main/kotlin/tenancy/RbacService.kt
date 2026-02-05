package tenancy

interface RbacService {
    fun canReadPortfolio(ctx: TenantContext): Boolean

    fun canWritePortfolio(ctx: TenantContext): Boolean

    fun canManageAlerts(ctx: TenantContext): Boolean

    fun canAdmin(ctx: TenantContext): Boolean
}

class DefaultRbacService : RbacService {
    override fun canReadPortfolio(ctx: TenantContext): Boolean = Role.VIEWER in ctx.roles || canWritePortfolio(ctx)

    override fun canWritePortfolio(ctx: TenantContext): Boolean =
        Role.DEVELOPER in ctx.roles || Role.ADMIN in ctx.roles || Role.OWNER in ctx.roles

    override fun canManageAlerts(ctx: TenantContext): Boolean =
        Role.DEVELOPER in ctx.roles ||
            Role.ADMIN in ctx.roles ||
            Role.OWNER in ctx.roles ||
            "write:alerts" in ctx.scopes

    override fun canAdmin(ctx: TenantContext): Boolean = Role.ADMIN in ctx.roles || Role.OWNER in ctx.roles
}
