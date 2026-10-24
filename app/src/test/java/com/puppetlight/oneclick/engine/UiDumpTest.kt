package com.puppetlight.oneclick.engine

import com.puppetlight.oneclick.script.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiDumpTest {

    private val xml = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation="0">
<node index="0" text="" resource-id="" class="android.widget.FrameLayout" package="com.nv.max" content-desc="" clickable="false" long-clickable="false" scrollable="false" focused="false" bounds="[0,0][1080,2400]">
  <node index="0" text="" resource-id="com.nv.max:id/layers" class="android.widget.LinearLayout" package="com.nv.max" content-desc="" clickable="false" bounds="[0,1600][1080,1800]">
    <node index="0" text="3D摄影机" resource-id="com.nv.max:id/name" class="android.widget.TextView" package="com.nv.max" content-desc="" clickable="true" long-clickable="true" bounds="[700,1650][900,1750]" />
    <node index="1" text="3D摄影机" resource-id="com.nv.max:id/name" class="android.widget.TextView" package="com.nv.max" content-desc="" clickable="true" bounds="[100,1650][300,1750]" />
    <node index="2" text="3D摄影机" resource-id="com.nv.max:id/name" class="android.widget.TextView" package="com.nv.max" content-desc="" clickable="true" bounds="[400,1650][600,1750]" />
  </node>
  <node index="1" text="" resource-id="" class="android.widget.LinearLayout" package="com.nv.max" content-desc="" bounds="[0,1900][1080,2000]">
    <node index="0" text="容差" resource-id="" class="android.widget.TextView" package="com.nv.max" content-desc="" bounds="[40,1920][200,1980]" />
    <node index="1" text="0.3" resource-id="com.nv.max:id/value" class="android.widget.EditText" package="com.nv.max" content-desc="" focused="true" bounds="[800,1920][1000,1980]" />
  </node>
  <node index="2" text="" resource-id="" class="android.widget.ImageButton" package="com.nv.max" content-desc="添加效果 &amp; 滤镜" clickable="true" bounds="[480,2250][600,2370]" />
</node>
</hierarchy>"""

    @Test fun parsesTree() {
        val roots = UiDumpParser.parse(xml)
        assertEquals(1, roots.size)
        assertEquals(9, roots[0].walk().count())
        val btn = roots[0].walk().first { it.desc != null }
        assertEquals("添加效果 & 滤镜", btn.desc)
        assertEquals(Box(480, 2250, 600, 2370), btn.bounds)
        assertTrue(roots[0].walk().any { it.editable && it.focused })
    }

    @Test fun findLeftToRightIndex() {
        val roots = UiDumpParser.parse(xml)
        val t = Target(texts = listOf("3D摄影机"), sort = "x", index = 2)
        assertEquals(700, NodeQuery.findOne(roots, t)!!.bounds.left)
        assertEquals(100, NodeQuery.findOne(roots, t.copy(index = 0))!!.bounds.left)
        assertEquals(700, NodeQuery.findOne(roots, t.copy(index = -1))!!.bounds.left)
        assertNull(NodeQuery.findOne(roots, t.copy(index = 5)))
    }

    @Test fun descMatchesWhenNoDescSelector() {
        val roots = UiDumpParser.parse(xml)
        assertNotNull(NodeQuery.findOne(roots, Target(texts = listOf("添加效果"))))
        assertNotNull(NodeQuery.findOne(roots, Target(viewIds = listOf("layers"))))
    }

    @Test fun editableExcludedUnlessAsked() {
        val roots = UiDumpParser.parse(xml)
        assertNull(NodeQuery.findOne(roots, Target(texts = listOf("0.3"))))
        assertNotNull(NodeQuery.findOne(roots, Target(texts = listOf("0.3"), includeEditable = true)))
    }

    @Test fun nearbyFindsValueField() {
        val roots = UiDumpParser.parse(xml)
        val label = NodeQuery.findOne(roots, Target(texts = listOf("容差")))!!
        val field = NodeQuery.nearby(label) { it.editable }
        assertEquals("0.3", field!!.text)
    }

    @Test fun deepestAtAndIndexOf() {
        val roots = UiDumpParser.parse(xml)
        val n = NodeQuery.deepestAt(roots, 450f, 1700f)!!
        assertEquals("3D摄影机", n.text)
        assertEquals(1, NodeQuery.indexOf(roots, n, Target(texts = listOf("3D摄影机"))))
        assertEquals(n.parent!!.parent, NodeQuery.clickableAncestor(n)!!.parent!!.parent)
    }
}
