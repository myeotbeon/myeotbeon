package app.myeotbeonideun

import app.myeotbeonideun.ui.theme.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 글자와 바탕의 대비가 4.5:1 이상인지(Pleos 디자인 가이드). */
class PaletteContrastTest {
    @Test
    fun `밤 색의 글자 대비는 모두 4점5 대 1 이상이다`() = check(Palette.Night)

    @Test
    fun `낮 색의 글자 대비는 모두 4점5 대 1 이상이다`() = check(Palette.Day)

    private fun check(palette: Palette) {
        for ((fg, bg, name) in palette.textPairs()) {
            val ratio = Palette.contrast(fg, bg)
            assertTrue("$name 대비 ${"%.2f".format(ratio)}", ratio >= 4.5)
        }
    }

    @Test
    fun `대비 계산은 흰색과 검은색에서 21이다`() {
        assertEquals(21.0, Palette.contrast(0xFFFFFFFF, 0xFF000000), 0.01)
        assertEquals(1.0, Palette.contrast(0xFF336699, 0xFF336699), 0.0001)
    }
}

/** 화면 어디에도 병명·환자라는 말이 없어야 한다(명세 5). 소스의 화면 글을 직접 훑는다. */
class WordingTest {
    private val banned = listOf("치매", "환자", "인지저하", "인지 저하", "인지장애", "인지 장애", "알츠하이머", "노망")

    private fun main(path: String) = File("src/main/$path")

    @Test
    fun `화면 글(strings xml)에 병명과 환자라는 말이 없다`() {
        val text = main("res/values/strings.xml").readText()
        for (word in banned) assertFalse("strings.xml 에 '$word'", text.contains(word))
    }

    @Test
    fun `화면 코드와 대본과 Gleo 스키마에도 그 말이 없다`() {
        val files = main("java/app/myeotbeonideun/ui").walkTopDown().filter { it.extension == "kt" } +
            main("java/app/myeotbeonideun/signal/DemoSignalSource.kt") +
            main("assets/scripts_ko.json") + main("assets/schema.json")
        for (file in files) {
            val text = file.readText()
            for (word in banned) assertFalse("${file.name} 에 '$word'", text.contains(word))
        }
    }

    @Test
    fun `앱 매니페스트는 인터넷과 차량 제어와 경고등 권한을 선언하지 않는다`() {
        val manifest = main("AndroidManifest.xml").readText()
        val declared = Regex("""<uses-permission\s+android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertFalse(declared.contains("android.permission.INTERNET"))
        assertTrue(declared.none { it.contains("CONTROL_CAR") })
        assertTrue(declared.none { it.contains("PRIVATE_CAR") })
        assertTrue(declared.contains("pleos.car.permission.CAR_SEATS"))
    }

    @Test
    fun `주행 중에도 쓰는 화면으로 선언되어 있다`() {
        val manifest = main("AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:name=\"distractionOptimized\""))
    }
}
