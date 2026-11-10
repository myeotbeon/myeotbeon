package app.myeotbeonideun.engine

import app.myeotbeonideun.engine.script.ScriptBook
import java.io.File

/** 앱에 실제로 들어가는 대본(app/src/main/assets/scripts_ko.json)을 읽는다. */
object TestScripts {
    private const val ASSET = "app/src/main/assets/scripts_ko.json"

    /**
     * 기본은 앱에 들어가는 대본이다. `-PscriptFile=<파일>` 을 주면 그 대본으로 같은 테스트를 돌려,
     * 새 대본을 넣기 전에 엔진과 맞는지 볼 수 있다.
     */
    val assetFile: File by lazy {
        System.getProperty("scriptFile")?.takeIf { it.isNotBlank() }?.let { return@lazy File(it) }
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
