package com.puppetlight.oneclick.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExprTest {
    private val vars = mapOf("a" to "1.5", "b" to "0.12", "on" to "true", "off" to "false")

    @Test fun arithmetic() {
        assertEquals(0.18, Expr.eval("\${a} * \${b}", vars), 1e-9)
        assertEquals(-1.0, Expr.eval("-(2 - 1)"), 1e-9)
        assertEquals(7.0, Expr.eval("1 + 2 * 3"), 1e-9)
        assertEquals(1.0, Expr.eval("min(\${a} + 0.1, 1)", vars), 1e-9)
        assertEquals(2.0, Expr.eval("max(1, 2, abs(-0.5))"), 1e-9)
    }

    @Test fun truthy() {
        assertTrue(Expr.truthy("\${on}", vars))
        assertFalse(Expr.truthy("\${off}", vars))
        assertTrue(Expr.truthy("!\${off}", vars))
        assertTrue(Expr.truthy(null, vars))
        assertFalse(Expr.truthy("0", vars))
    }

    @Test fun format() {
        assertEquals("1", Expr.format(1.0))
        assertEquals("0.5", Expr.format(0.5))
        assertEquals("0.333", Expr.format(1.0 / 3))
    }

    @Test fun substituteKeepsUnknown() {
        assertEquals("x \${zzz}", Expr.substitute("x \${zzz}", vars))
    }
}
