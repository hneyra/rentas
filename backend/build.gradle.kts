// Raiz del build de `rentas`. No produce artefactos: solo agrupa y declara las dos tareas
// bloqueantes, con los mismos nombres que en los otros cuatro repositorios.
//
// Van SEPARADAS a proposito, y en CI son dos pasos: cuando algo se rompe, el nombre del paso ya
// dice que barrera cayo.

tasks.register("verificarAislamiento") {
    group = "verification"
    description =
        "Aislamiento multi-tenant: RLS, los roles y la trampa del superusuario. " +
            "Bloqueante. Requiere Docker."
    dependsOn(":kamayuk-esquema:test")
}

tasks.register("verificarArquitectura") {
    group = "verification"
    description =
        "Reglas de ArchUnit, escaner del codigo fuente, aserciones y frontera de sistema. " +
            "Bloqueante."
    dependsOn(":kamayuk-verificaciones:test")
}
