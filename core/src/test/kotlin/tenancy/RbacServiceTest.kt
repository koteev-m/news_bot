package tenancy

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RbacServiceTest {
    private val service = DefaultRbacService()

    private fun ctx(vararg roles: Role) = TenantContext(
        tenant = Tenant(tenantId = 1, orgId = 1, slug = "t1", displayName = "Tenant"),
        userId = 42L,
        roles = roles.toSet(),
        scopes = emptySet()
    )

    @Test
    fun admin_can_manage() {
        val c = ctx(Role.ADMIN)
        assertTrue(service.canAdmin(c))
        assertTrue(service.canManageAlerts(c))
    }

    @Test
    fun viewer_cannot_write() {
        val c = ctx(Role.VIEWER)
        assertFalse(service.canWritePortfolio(c))
    }
}
