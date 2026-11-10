package app.myeotbeonideun.engine.script

import java.io.File
import kotlin.system.exitProcess

/**
 * 대본 파일을 검사해 결과를 적는다. 대본을 바꾼 사람이 앱을 빌드하지 않고도 규칙 위반을 볼 수 있게 한 도구.
 *
 *   ./gradlew :engine:validateScript                      # 앱에 들어가는 대본
 *   ./gradlew :engine:validateScript -Pfile=<대본 파일>     # 다른 파일
 *
 * 결과는 화면과 engine/build/script-report.txt(UTF-8)에 적는다. 오류가 있으면 실패로 끝난다.
 */
fun main(args: Array<String>) {
    val file = File(args.firstOrNull() ?: "../app/src/main/assets/scripts_ko.json")
    val report = StringBuilder()
    var failed = false
    try {
        val book = ScriptBook.parse(file.readText(Charsets.UTF_8))
        val findings = ScriptValidator.validate(book)
        val errors = findings.filter { it.severity == Severity.ERROR }
        val warnings = findings.filter { it.severity == Severity.WARNING }
        report.appendLine("대본: ${file.name}")
        report.appendLine("상황 ${book.situations.size}개, 문장 ${book.situations.values.sumOf { it.lines.size }}개")
        report.appendLine("오류 ${errors.size}건, 주의 ${warnings.size}건")
        (errors + warnings).forEach { finding ->
            report.appendLine(finding.toString())
            if (finding.lineIndex >= 0) {
                book.situation(finding.situation)?.lines?.getOrNull(finding.lineIndex)?.let { report.appendLine("    $it") }
            }
        }
        failed = errors.isNotEmpty()
    } catch (e: Exception) {
        report.appendLine("대본을 읽지 못했습니다: ${e.message}")
        failed = true
    }
    File("build").mkdirs()
    File("build/script-report.txt").writeText(report.toString(), Charsets.UTF_8)
    println(report)
    if (failed) exitProcess(1)
}
