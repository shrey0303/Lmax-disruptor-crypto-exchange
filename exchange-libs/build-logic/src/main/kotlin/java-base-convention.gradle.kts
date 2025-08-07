val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

plugins {
    java
}

extensions.configure<JavaPluginExtension> {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    //maven local if using locally built Aeron
    mavenLocal()
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}