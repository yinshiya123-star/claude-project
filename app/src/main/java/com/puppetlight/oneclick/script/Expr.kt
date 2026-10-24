package com.puppetlight.oneclick.script

/**
 * 变量替换 + 简单四则运算求值。
 *
 * - `${name}` 会被替换为变量值
 * - 数值字段可写表达式，例如 `"${keyframeTime} * 0.15"`、`"min(${keyTolerance} + 0.1, 1)"`
 * - 支持 + - * / ( ) 、一元负号、函数 min/max/abs/round
 */
object Expr {

    private val VAR = Regex("""\$\{([A-Za-z0-9_.]+)}""")

    fun substitute(input: String, vars: Map<String, String>): String =
        VAR.replace(input) { m -> vars[m.groupValues[1]] ?: m.value }

    fun eval(input: String, vars: Map<String, String> = emptyMap()): Double {
        val s = substitute(input, vars).trim()
        s.toDoubleOrNull()?.let { return it }
        return Parser(s).parse()
    }

    /** "true"/"1"/非零数 为真；以 "!" 开头取反；空串为假。 */
    fun truthy(input: String?, vars: Map<String, String>): Boolean {
        if (input == null) return true
        var s = substitute(input, vars).trim()
        var negate = false
        while (s.startsWith("!")) {
            negate = !negate
            s = s.substring(1).trim()
        }
        val v = when (s.lowercase()) {
            "", "false", "no", "off", "null" -> false
            "true", "yes", "on" -> true
            else -> runCatching { eval(s) != 0.0 }.getOrDefault(true)
        }
        return v xor negate
    }

    fun format(v: Double): String {
        if (v == Math.rint(v) && kotlin.math.abs(v) < 1e12) return v.toLong().toString()
        return String.format(java.util.Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parse(): Double {
            val v = expr()
            skipWs()
            if (pos != s.length) throw IllegalArgumentException("表达式无法解析: \"$s\" (位置 $pos)")
            return v
        }

        private fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char? {
            skipWs()
            return if (pos < s.length) s[pos] else null
        }

        private fun expr(): Double {
            var v = term()
            while (true) {
                when (peek()) {
                    '+' -> { pos++; v += term() }
                    '-' -> { pos++; v -= term() }
                    else -> return v
                }
            }
        }

        private fun term(): Double {
            var v = factor()
            while (true) {
                when (peek()) {
                    '*' -> { pos++; v *= factor() }
                    '/' -> { pos++; v /= factor() }
                    else -> return v
                }
            }
        }

        private fun factor(): Double {
            val c = peek() ?: throw IllegalArgumentException("表达式意外结束: \"$s\"")
            if (c == '-') { pos++; return -factor() }
            if (c == '+') { pos++; return factor() }
            if (c == '(') {
                pos++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("缺少右括号: \"$s\"")
                pos++
                return v
            }
            if (c.isLetter()) {
                val start = pos
                while (pos < s.length && s[pos].isLetter()) pos++
                val name = s.substring(start, pos)
                if (peek() != '(') throw IllegalArgumentException("未知标识符 \"$name\" (变量是否未定义?)")
                pos++
                val args = mutableListOf(expr())
                while (peek() == ',') { pos++; args += expr() }
                if (peek() != ')') throw IllegalArgumentException("缺少右括号: \"$s\"")
                pos++
                return when (name.lowercase()) {
                    "min" -> args.min()
                    "max" -> args.max()
                    "abs" -> kotlin.math.abs(args[0])
                    "round" -> Math.rint(args[0])
                    else -> throw IllegalArgumentException("未知函数 $name")
                }
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (start == pos) throw IllegalArgumentException("表达式无法解析: \"$s\" (位置 $pos)")
            return s.substring(start, pos).toDouble()
        }
    }
}
