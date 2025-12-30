plugins {
    id("java-application-conventions")
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
    implementation(libs.okHttp)
    implementation(libs.json)
    implementation("org.hdrhistogram:HdrHistogram:2.1.12")
    implementation(project(":exchange-libs:common"))
    implementation(project(":exchange-libs:exchange-proto"))
    implementation(project(":exchange-core"))
    compileOnly("org.projectlombok:lombok:${libs.versions.lombokVersion.get()}")
    annotationProcessor("org.projectlombok:lombok:${libs.versions.lombokVersion.get()}")

    // Protobuf
    runtimeOnly("io.grpc:grpc-netty-shaded:${libs.versions.grpcVersion.get()}")
    implementation("io.grpc:grpc-services:${libs.versions.grpcVersion.get()}")
    implementation("io.grpc:grpc-protobuf:${libs.versions.grpcVersion.get()}")
    implementation("io.grpc:grpc-stub:${libs.versions.grpcVersion.get()}")
    compileOnly("org.apache.tomcat:annotations-api:${libs.versions.annotationsApiVersion.get()}")
    implementation("com.google.protobuf:protobuf-java-util:${libs.versions.protocVersion.get()}")

    // Kafka — for full-pipeline replay benchmark
    implementation(libs.kafkaClient)
}

@Suppress("DEPRECATION")
val generatedDir = file("${buildDir}/generated/src/main/java")

val benchmarkJvmArgs = listOf(
    "-XX:+UseZGC",
    "-Xms4g",
    "-Xmx8g",
    "-XX:+AlwaysPreTouch",
    "-Xlog:gc*:file=gc.log:time,uptime,level,tags"
)

application {
    mainClass.set("shrey.benchmarkbinance.BinanceBenchmarkRunner")
    applicationDefaultJvmArgs = benchmarkJvmArgs
}

sourceSets {
    main {
        java.srcDirs("src/main/java", generatedDir)
    }
}

tasks {
    // ═══ Live Binance Feed Benchmarks ═══
    task("run-steady", JavaExec::class) {
        group = "benchmark"
        description = "30-minute steady-state benchmark with live Binance feed"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("shrey.benchmarkbinance.BinanceBenchmarkRunner")
        args("--steady")
        jvmArgs = benchmarkJvmArgs
    }
    task("run-burst", JavaExec::class) {
        group = "benchmark"
        description = "5-minute 100x burst benchmark"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("shrey.benchmarkbinance.BinanceBenchmarkRunner")
        args("--burst")
        jvmArgs = benchmarkJvmArgs
    }
    task("run-hft", JavaExec::class) {
        group = "benchmark"
        description = "10-minute HFT pattern (90% cancels)"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("shrey.benchmarkbinance.BinanceBenchmarkRunner")
        args("--hft")
        jvmArgs = benchmarkJvmArgs
    }

    // ═══ Data Capture ═══
    task("run-capture", JavaExec::class) {
        group = "benchmark"
        description = "Capture 30min of raw Binance depth data to JSONL for offline replay"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("shrey.benchmarkbinance.BinanceDataCapture")
        args("--duration", "30")
        jvmArgs = benchmarkJvmArgs
    }

    // ═══ Offline Replay Benchmarks ═══
    task("run-replay", JavaExec::class) {
        group = "benchmark"
        description = "Replay captured Binance data at max speed — matching engine only, 12h default"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("shrey.benchmarkbinance.ReplayBenchmarkRunner")
        // No args needed — auto-detects data file, default 12h duration
        // Override: --args="binance_depth_5min.jsonl --duration 60"
        jvmArgs = benchmarkJvmArgs
    }
    task("run-replay-kafka", JavaExec::class) {
        group = "benchmark"
        description = "Replay through FULL pipeline including Kafka journaling, 12h default"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass.set("shrey.benchmarkbinance.ReplayBenchmarkRunner")
        args("--kafka")
        jvmArgs = benchmarkJvmArgs
    }
}
