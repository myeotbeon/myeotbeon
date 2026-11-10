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
    testLogging {
        events("failed")
        showStandardStreams = false
    }
}
