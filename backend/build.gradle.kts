// Raiz del build del backend. No produce artefactos: solo agrupa.
// Las convenciones viven en buildSrc/ como plugins precompilados, no en un
// bloque `subprojects {}`: un modulo debe declarar que convenciones aplica.

tasks.register("verificarAislamiento") {
    group = "verification"
    description =
        "Aislamiento multi-tenant: la prueba del esquema y la del pool. Bloqueante. Requiere Docker."
    dependsOn(":kamayuk-rentas-esquema:test", ":kamayuk-rentas-plataforma:test")
}

tasks.register("verificarArquitectura") {
    group = "verification"
    description =
        "Reglas de ArchUnit, escaner del codigo fuente y limites de Spring Modulith. Bloqueante."
    dependsOn(":kamayuk-rentas-aplicacion:test")
}
