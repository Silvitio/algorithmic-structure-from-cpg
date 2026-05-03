plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.neo4j.driver:neo4j-java-driver:6.0.3")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("app.Main")
}

tasks.register<JavaExec>("runTestRunner") {
    group = "application"
    description = "Runs the interactive analysis test runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.AnalysisTestRunner")
    standardInput = System.`in`
}

tasks.register<JavaExec>("runModelAnalysisReport") {
    group = "application"
    description = "Runs the model-only analysis report over all test cases"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.ModelAnalysisReportRunner")
}
