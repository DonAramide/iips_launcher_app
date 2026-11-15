package com.iips.launcher.security.pds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PdsTenantIdentityTest {
    private val approved = "830cb9cf-5717-4b68-8e29-dfb28ce755f3"

    @Test
    fun acceptsCanonicalTenantUuid() {
        assertTrue(PdsTenantIdentity.isValidTenantUuid(approved))
        assertEquals(approved, PdsTenantIdentity.requireExplicitTenantUuid(approved))
    }

    @Test
    fun rejectsBusinessNameDefaultAndBlank() {
        assertFalse(PdsTenantIdentity.isValidTenantUuid("test mop45"))
        assertFalse(PdsTenantIdentity.isValidTenantUuid("default"))
        assertFalse(PdsTenantIdentity.isValidTenantUuid("DEFAULT"))
        assertFalse(PdsTenantIdentity.isValidTenantUuid(""))
        assertFalse(PdsTenantIdentity.isValidTenantUuid(null))
        assertFalse(PdsTenantIdentity.isValidTenantUuid("AAA000"))
    }

    @Test
    fun requireExplicit_failClosedWithoutChallengeSideEffect() {
        try {
            PdsTenantIdentity.requireExplicitTenantUuid("test mop45")
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("explicit tenant UUID"))
        }
        try {
            PdsTenantIdentity.requireExplicitTenantUuid("default")
            fail("Expected SecurityException")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("explicit tenant UUID"))
        }
    }
}
