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
    implementation(libs.lmaxDisruptor)
    implementation(libs.kafkaClient)
    implementation(project(":exchange-libs:common"))
    implementation(project(":exchange-libs:exchange-proto"))
    implementation(project(":exchange-core"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation(libs.mysqlConnector)
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // Protobuf
    runtimeOnly("io.grpc:grpc-netty-shaded:${libs.versions.grpcVersion.get()}")
    implementation("io.grpc:grpc-services:${libs.versions.grpcVersion.get()}")
    implementation("io.grpc:grpc-protobuf:${libs.versions.grpcVersion.get()}")
    implementation("io.grpc:grpc-stub:${libs.versions.grpcVersion.get()}")
    compileOnly("org.apache.tomcat:annotations-api:${libs.versions.annotationsApiVersion.get()}")
    implementation("com.google.protobuf:protobuf-java-util:${libs.versions.protocVersion.get()}")
}

@Suppress("DEPRECATION")
val generatedDir = file("${buildDir}/generated/src/main/java")
val codecGeneration = configurations.create("codecGeneration")

application {
    mainClass.set("shrey.exchange.ExchangeApplication")
}

sourceSets {
    main {
        java.srcDirs("src/main/java", generatedDir)
    }
}

tasks {
    task("run-leader", JavaExec::class) {
        group = "run"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("shrey.exchange.ExchangeApplication")
        jvmArgs("-Dspring.profiles.active=leader")
    }
    task("run-follower", JavaExec::class) {
        group = "run"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("shrey.exchange.ExchangeApplication")
        jvmArgs("-Dspring.profiles.active=follower")
    }
    task("run-learner", JavaExec::class) {
        group = "run"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("shrey.exchange.ExchangeApplication")
        jvmArgs("-Dspring.profiles.active=learner")
    }
}
