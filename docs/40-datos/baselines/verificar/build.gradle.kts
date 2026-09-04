// El arnes de los baselines. No es un modulo del backend: se ejecuta a mano o desde CI, y por
// eso vive aqui y no en `backend/settings.gradle.kts`.
plugins { application }

repositories { mavenCentral() }

dependencies {
    implementation("org.postgresql:postgresql:42.7.4")
    // La MISMA version de Flyway que `backend/gradle/libs.versions.toml`. Si divergen, este
    // arnes verificaria un esquema y el despliegue aplicaria otro.
    implementation("org.flywaydb:flyway-core:11.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:11.10.0")
}

application { mainClass.set(System.getProperty("clase", "Guardas")) }

tasks.named<JavaExec>("run") { systemProperty("sql", System.getProperty("sql", "")) }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
