package app.myeotbeonideun.engine

import app.myeotbeonideun.engine.script.ScriptBook
import java.io.File

/** 앱에 실제로 들어가는 대본(app/src/main/assets/scripts_ko.json)을 읽는다. */
object TestScripts {
    private const val ASSET = "app/src/main/assets/scripts_ko.json"

    val assetFile: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, ASSET)
            if (f.exists()) return@lazy f
            dir = dir.parentFile
        }
        error("대본 파일을 찾지 못했습니다: $ASSET")
    }

    val book: ScriptBook by lazy { ScriptBook.parse(assetFile.readText(Charsets.UTF_8)) }
}
