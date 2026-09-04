// El panel de recaudacion (#56, RF-130). NO es un contexto acotado.
//
// ARQ-01 §3 fija doce contextos y este no es el trece: no tiene modelo, no tiene
// tablas y no decide nada. Es una LECTURA TRANSVERSAL que agrega lo que otros ya
// publican, y por eso vive junto a `kamayuk-rentas-esquema` y `kamayuk-rentas-plataforma` en la lista
// de modulos que no son contextos.
//
// Lo que este archivo declara es la frontera del panel, y es su mitad mas util:
// solo ve `cuentacorriente` y `tesoreria`. Sumar un tercer contexto al panel
// cuesta una linea aqui, y esa linea la escribe alguien que tiene que decidir
// —y dejar escrito— por que el panel necesita mirar ahi. Spring Modulith vigila
// que no se cruce el limite por dentro (AC 3); Gradle vigila que ni siquiera este
// en el classpath.

plugins {
    id("sgtm.modulo")
}

dependencies {
    testImplementation(testFixtures(project(":kamayuk-rentas-parametros")))
    // Lo recaudado, lo cargado y la cartera pendiente: RecaudacionDelLibro y
    // CarteraDelLibro, las dos del paquete raiz. Ninguna tabla (ARQ-01 §4 regla 1).
    implementation(project(":kamayuk-rentas-cuentacorriente"))
    // El avance del dia, por AvanceDeCaja. Reutiliza #36 en vez de volver a sumar
    // recibos: si el panel los sumara por su cuenta, la pantalla de inicio y la de
    // recaudacion podrian decir cifras distintas del mismo dia.
    implementation(project(":kamayuk-rentas-tesoreria"))

    // --- El trabajo parado por modulo (#549) -------------------------------
    //
    // Cuatro contextos mas, y las cuatro lineas se escriben con su motivo porque
    // es lo que este archivo pide. Lo que las justifica es que la pregunta —«que
    // esta esperando un acto y no cobra mientras espera»— NO se puede contestar
    // desde un solo modulo: son cuatro poblaciones de cuatro contextos, y la
    // alternativa era que la pantalla de aterrizaje hiciera cuatro peticiones
    // mas, cada una con su permiso, e inventara en el cliente el concepto
    // «parado». Cada uno entra por UN puerto de su paquete raiz que devuelve un
    // AGREGADO —un recuento, a lo sumo con su suma—, nunca filas: es el AC 4 de
    // #56 otra vez, y `PanelSinRecorrerElLibroTest` lo comprueba tambien sobre
    // `ConsultaDeTrabajoParado`.
    //
    // Papeletas de transito impuestas y sin notificar, por PapeletasSinNotificar.
    implementation(project(":kamayuk-rentas-sanciones"))
    // Valores emitidos y sin notificar a una fecha, por ValoresSinNotificar.
    implementation(project(":kamayuk-rentas-valores"))
    // Expedientes coactivos que siguen en INICIADO, por ExpedientesSinRec.
    implementation(project(":kamayuk-rentas-coactiva"))
    // Predios con ficha y sin declaracion jurada del ejercicio, por
    // PrediosSinConciliar — que NO cuenta por su cuenta: delega en el resumen de
    // #564, el mismo que publica GET /catastro/fichas/conciliacion/resumen. El
    // frente es de Catastro y la dependencia es de `rentas` porque el derivado
    // sale de `declaracion_jurada`, que es de rentas (ADR-0015).
    implementation(project(":kamayuk-rentas-rentas"))

    // MockMvc para el endpoint: transporte sin base de datos. El panel no persiste
    // nada, asi que no hay una sola prueba contra PostgreSQL en este modulo; las que
    // demuestran que las cifras cuadran con el libro y que RLS las separa viven donde
    // vive el SQL, en kamayuk-rentas-cuentacorriente.
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
}
