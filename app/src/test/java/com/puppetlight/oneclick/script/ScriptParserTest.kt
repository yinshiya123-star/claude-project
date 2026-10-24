package com.puppetlight.oneclick.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ScriptParserTest {

    private fun defaultScript(): Script =
        ScriptParser.parse(File("src/main/assets/scripts/default_flow.json").readText())

    @Test fun defaultScriptParsesAndExpandsMacros() {
        val s = defaultScript()
        assertTrue(s.steps.size > 80)
        assertTrue(s.steps.none { it.action == ActionType.MACRO })
        assertEquals(5, s.phases.size)
        // 每个步骤 id 唯一
        assertEquals(s.steps.size, s.steps.map { it.id }.toSet().size)
        // 绿幕检测带修正步骤
        val g = s.steps.first { it.action == ActionType.GREEN_CHECK }
        val fix = ScriptParser.parseStepList(g.json.getJSONArray("fixSteps"), g.phase, "x.")
        assertEquals(listOf(ActionType.SET_VAR, ActionType.SET_VALUE), fix.map { it.action })
    }

    @Test fun macroArgsAreSubstituted() {
        val s = defaultScript()
        val pick = s.steps.first { it.id == "2.4.4" }
        val t = Target.from(pick.json.getJSONObject("target"), emptyMap())!!
        assertEquals(listOf("灰光", "Gray Light", "Grey Light"), t.texts)
        val input = s.steps.first { it.id == "2.4.3" }
        assertEquals("光", input.json.getString("text"))
        // 宏内步骤继承 enabledIf
        assertTrue(s.steps.filter { it.id.startsWith("3.5a.") }.all { it.enabledIf == "\${saturationEnabled}" })
    }

    @Test fun targetResolvesVariablesAndExpressions() {
        val s = defaultScript()
        val drag = s.steps.first { it.id == "1.3" }
        val vars = s.variables
        val to = Target.from(drag.json.getJSONObject("to"), vars)!!
        assertEquals(0.33, to.y!!, 1e-9)
    }

    @Test fun duplicateMacroKeepsNumericIndex() {
        val s = defaultScript()
        val lp = s.steps.first { it.id == "1.9b.1" }
        val t = Target.from(lp.json.getJSONObject("target"), emptyMap())!!
        assertEquals(-1, t.index)
        assertEquals("x", t.sort)
    }

    @Test fun rejectsUnknownAction() {
        try {
            ScriptParser.parse("""{"steps":[{"action":"FLY"}]}""")
            fail()
        } catch (e: ScriptException) {
            assertTrue(e.message!!.contains("FLY"))
        }
    }

    @Test fun rejectsMissingField() {
        try {
            ScriptParser.parse("""{"steps":[{"action":"CLICK"}]}""")
            fail()
        } catch (e: ScriptException) {
            assertTrue(e.message!!.contains("target"))
        }
    }
}
