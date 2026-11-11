package app.myeotbeonideun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 화면 함수의 인자 순서: Compose 규칙(lint ModifierParameter)대로 `modifier: Modifier = Modifier` 가 첫째 선택 인자여야 한다
 * (검토 NEW-9 — SettingsScreen 에 기본값이 있는 콜백을 modifier 앞에 넣어 lint 경고가 새로 났다). lint 를 돌리지 않아도
 * 단위 시험에서 바로 잡히게 소스를 읽어 본다.
 */
class ComposeSignatureTest {

    private val uiDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }
        .let { File(it, "app/src/main/java/app/myeotbeonideun/ui") }

    @Test
    fun `modifier 는 화면 함수의 첫째 선택 인자다`() {
        val problems = mutableListOf<String>()
        var checked = 0
        uiDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            for (m in COMPOSABLE_FUN.findAll(file.readText())) {
                // 식 본문(= …)인 함수는 다음 선언까지 잡히므로 건너뛴다
                if (m.groupValues[2].contains("fun ") || m.groupValues[2].contains("@Composable")) continue
                val params = splitParams(m.groupValues[2])
                val modifierAt = params.indexOfFirst { MODIFIER_PARAM.matches(it) }
                if (modifierAt < 0) continue
                checked++
                val optionalBefore = params.take(modifierAt).filter { it.contains(" = ") }
                if (optionalBefore.isNotEmpty()) problems += "${file.name} ${m.groupValues[1]}: modifier 앞의 선택 인자 $optionalBefore"
            }
        }
        assertTrue("modifier 를 받는 화면 함수를 하나도 찾지 못했다(정규식 확인)", checked >= 5)
        assertEquals(emptyList<String>(), problems)
    }

    /** 괄호 깊이를 보며 최상위 쉼표로 인자를 나눈다(람다 타입 안의 쉼표는 나누지 않는다). */
    private fun splitParams(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        var previous = ' '
        for (ch in text) {
            when {
                ch == '(' || ch == '<' || ch == '{' || ch == '[' -> depth++
                ch == ')' || ch == '}' || ch == ']' -> depth--
                ch == '>' && previous != '-' -> depth-- // 함수 타입의 '->' 는 괄호가 아니다
            }
            previous = ch
            if (ch == ',' && depth == 0) {
                out += current.toString().trim()
                current.clear()
            } else {
                current.append(ch)
            }
        }
        if (current.isNotBlank()) out += current.toString().trim()
        return out.map { it.lines().filterNot { l -> l.trim().startsWith("//") }.joinToString(" ").trim() }.filter { it.isNotEmpty() }
    }

    private companion object {
        /** `@Composable` 바로 뒤의 함수 선언과 그 인자 목록(여는 괄호부터 `) {` 앞까지). */
        val COMPOSABLE_FUN = Regex("""@Composable\s+(?:(?:private|internal)\s+)?fun\s+(\w+)\(([\s\S]*?)\)\s*(?::\s*\w+\s*)?\{""")
        val MODIFIER_PARAM = Regex("""modifier:\s*Modifier\s*=\s*Modifier""")
    }
}
