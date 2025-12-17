pluginManagement {
    // Include 'plugins build' to define convention plugins.
    includeBuild("exchange-libs/build-logic")
}
plugins {
    // Apply plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}
include(
    "exchange-libs:common",
    "exchange-libs:exchange-proto"
)
include(
    "exchange-core",
    "exchange-app"
)
include(
    "benchmark:benchmark-cluster",
    "benchmark:benchmark-cluster-jmh",
    "benchmark:benchmark-binance"
)
include(
    "exchange-client-core",
    "exchange-client-user",
    "exchange-client-admin"
)
rootProject.name = "lmax-disruptor-exchange"


