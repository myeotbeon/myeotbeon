// 순수 Kotlin 모듈. 안드로이드 의존이 없어 JVM 단위 테스트만으로 검증한다.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    // -PscriptFile=<대본 파일> 을 주면 그 대본으로 엔진 테스트를 돌린다(새 대본을 넣기 전의 확인용).
    systemProperty("scriptFile", (project.findProperty("scriptFile") as String?) ?: "")
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}

// 대본 검사 도구: ./gradlew :engine:validateScript [-Pfile=<대본 파일>]
tasks.register<JavaExec>("validateScript") {
    group = "verification"
    description = "대본 파일(scripts_ko.json)이 말의 원칙을 지키는지 검사한다"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.myeotbeonideun.engine.script.ValidateScriptKt")
    workingDir = projectDir
    args((project.findProperty("file") as String?) ?: "../app/src/main/assets/scripts_ko.json")
}
