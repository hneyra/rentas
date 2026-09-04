plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Los plugins que aplican las convenciones tienen que estar en el classpath
    // de buildSrc, no solo declarados en el catalogo.
    implementation("com.diffplug.spotless:spotless-plugin-gradle:8.9.0")
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.1.0")
}
