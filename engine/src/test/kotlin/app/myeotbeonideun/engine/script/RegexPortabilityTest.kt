package app.myeotbeonideun.engine.script

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM 단위 테스트는 java.util.regex 로 돌지만 앱은 안드로이드(ICU) 정규식으로 돈다.
 * ICU 는 문자 묶음([...]) 밖의 이스케이프 안 된 `}` 를 받지 않는다. JVM 에서는 통과하던
 * `\{...}` 꼴이 2026-11-11 Pleos Connect 에뮬레이터에서 ScriptEngine 초기화 실패(앱 종료)를 냈다.
 * 이 테스트는 엔진·앱 소스의 정규식 문자열을 모두 찾아 그 꼴이 다시 들어오지 않게 막는다.
 */
class RegexPortabilityTest {

    /** 문자 묶음 밖에서 반복 횟수({n}, {n,m})를 닫지 않는 `}` 와, 숫자가 뒤따르지 않는 `{` 를 찾는다. */
    private fun icuProblems(pattern: String): List<String> {
        val problems = mutableListOf<String>()
        var i = 0
        var inClass = false
        var openInterval = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' -> i++ // 다음 글자는 이스케이프된 글자
                inClass -> if (c == ']') inClass = false
                c == '[' -> inClass = true
                c == '{' -> {
                    val rest = pattern.substring(i + 1)
                    if (Regex("""^\d+(,\d*)?\}""").containsMatchIn(rest)) openInterval = true
                    else problems += "이스케이프 안 된 '{' (위치 $i)"
                }
                c == '}' -> if (openInterval) openInterval = false else problems += "이스케이프 안 된 '}' (위치 $i)"
            }
            i++
        }
        return problems
    }

    private val rawLiteral = Regex("Regex\\(\\s*\"\"\"(.*?)\"\"\"")
    // (?:a|b)* 꼴의 반복 묶음은 긴 파일에서 StackOverflowError 를 내므로 풀어 쓴 꼴(소유 수량자)을 쓴다.
    private val plainLiteral = Regex("Regex\\(\\s*\"([^\"\\\\\\n]*+(?:\\\\.[^\"\\\\\\n]*+)*+)\"")
    private val toRegexLiteral = Regex("\"([^\"\\\\\\n]*+(?:\\\\.[^\"\\\\\\n]*+)*+)\"\\s*\\.toRegex\\(")

    private fun unescapeKotlin(s: String): String =
        s.replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")

    private fun sourceRoots(): List<File> {
        val here = File("").absoluteFile
        val root = generateSequence(here) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        return listOf(File(root, "engine/src/main"), File(root, "app/src/main"))
    }

    @Test
    fun `알려진 대본 빈칸 정규식은 ICU 에서도 안전한 꼴이다`() {
        assertEquals(emptyList<String>(), icuProblems(ScriptEngine.SLOT_PATTERN.pattern))
        // 검사기 자체가 옛 꼴을 잡는지 확인
        assertTrue(icuProblems("""\{([^{}:]+)(?::([^{}]*))?}""").isNotEmpty())
        assertEquals(emptyList<String>(), icuProblems("""a{2,3}b[{}]\}"""))
    }

    @Test
    fun `엔진과 앱 소스의 모든 정규식 문자열이 ICU 에서도 안전한 꼴이다`() {
        val found = mutableListOf<String>()
        val bad = mutableListOf<String>()
        sourceRoots().filter { it.exists() }.forEach { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                val text = file.readText()
                val patterns = rawLiteral.findAll(text).map { it.groupValues[1] } +
                    plainLiteral.findAll(text).map { unescapeKotlin(it.groupValues[1]) } +
                    toRegexLiteral.findAll(text).map { unescapeKotlin(it.groupValues[1]) }
                patterns.forEach { p ->
                    found += p
                    val problems = icuProblems(p)
                    if (problems.isNotEmpty()) bad += "${file.name}: /$p/ → $problems"
                }
            }
        }
        assertTrue("정규식 문자열을 하나도 찾지 못했다(경로 확인)", found.isNotEmpty())
        assertEquals(emptyList<String>(), bad)
    }
}
