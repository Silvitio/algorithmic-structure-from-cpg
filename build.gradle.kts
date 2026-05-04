plugins {
    application
}

val cpgVersion = "10.8.2"

repositories {
    mavenCentral()
    ivy {
        url = uri("https://download.eclipse.org/tools/cdt/releases/")
        patternLayout {
            artifact("[organisation].[module]_[revision].[ext]")
        }
        metadataSources {
            artifact()
        }
    }
}

dependencies {
    implementation("org.neo4j.driver:neo4j-java-driver:6.0.3")
    compileOnly("org.neo4j:neo4j-ogm-core:4.0.10")
    implementation("de.fraunhofer.aisec:cpg-neo4j:$cpgVersion")
    implementation("de.fraunhofer.aisec:cpg-language-cxx:$cpgVersion")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("app.Main")
    applicationDefaultJvmArgs = listOf("-Xss4m")
}

tasks.register<JavaExec>("runTestRunner") {
    group = "application"
    description = "Runs the interactive analysis test runner"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.AnalysisTestRunner")
    standardInput = System.`in`
    jvmArgs("-Xss4m")
}

tasks.register<JavaExec>("runModelAnalysisReport") {
    group = "application"
    description = "Runs the model-only analysis report over all test cases"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("app.ModelAnalysisReportRunner")
    jvmArgs("-Xss4m")
}
