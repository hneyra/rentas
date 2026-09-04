/**
 * La forma de exponer los casos de uso por HTTP: contrato, errores, paginacion y la fecha de toda
 * cifra.
 *
 * <p>Se escribe una vez, como {@code persistencia}, y por el mismo motivo: con 134 pantallas por
 * delante, lo que aqui no quede resuelto se resolvera 134 veces y de once maneras distintas.
 *
 * <h2>Las cuatro decisiones</h2>
 *
 * <ol>
 *   <li><b>JSON en español {@code camelCase}</b> (ARQ-04 §3), y los importes como <b>cadena
 *       decimal</b>, nunca como numero JSON: el {@code number} de JavaScript es binario de doble
 *       precision y pierde centimos (RNF-055). Lo impone {@link
 *       kamayuk.rentas.web.ConfiguracionDeJson}, no la disciplina de quien escribe cada DTO.
 *   <li><b>Errores como {@code application/problem+json}</b> (RFC 9457), con un catalogo de codigos
 *       estable —{@link kamayuk.rentas.web.CodigoDeError}— para que la interfaz pueda reaccionar a
 *       un codigo y no a un texto que cambia con la traduccion.
 *   <li><b>Ningun error filtra el nombre de una tabla ni una linea de SQL.</b> Un mensaje del motor
 *       devuelto tal cual le dice a un atacante como se llama cada columna. Hay una prueba que lo
 *       verifica sobre el mensaje que sale de verdad.
 *   <li><b>Toda cifra de deuda viaja con la fecha a la que esta actualizada</b> (RNF-075, regla 9).
 *       No existe «la deuda»: existe {@code deudaActualizadaA(fecha)}, y la respuesta lo dice. Lo
 *       verifica una regla de ArchUnit sobre los DTO, no una revision.
 * </ol>
 *
 * <p>Y una que no se ve porque ya estaba: <b>ningun endpoint acepta ni devuelve {@code
 * municipalidadId}</b> (regla 2). Sale del token.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.web;
