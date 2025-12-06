plugins {
    id("java-application-conventions")
    id("org.springframework.boot") version libs.versions.springBoot.get()
    id("io.spring.dependency-management") version libs.versions.springDependencyManagement.get()
}

group = "shrey"
version = "0.0.1"

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    implementation(libs.slf4j)
    implementation(libs.logback)
    implementation(libs.lmaxDisruptor)
    implementation(project(":exchange-libs:common"))
    implementation(project(":exchange-libs:exchange-proto"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    compileOnly("org.projectlombok:lombok:${libs.versions.lombokVersion.get()}")
    annotationProcessor("org.projectlombok:lombok:${libs.versions.lombokVersion.get()}")
}

@Suppress("DEPRECATION")
val generatedDir = file("${buildDir}/generated/src/main/java")

application {
    mainClass.set("shrey.bankclientappuser.BankClientUserApplication")
}

sourceSets {
    main {
        java.srcDirs("src/main/java", generatedDir)
    }
}

tasks {
    task("run-user", JavaExec::class) {
        group = "run"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("shrey.bankclientappuser.BankClientUserApplication")
    }
}
