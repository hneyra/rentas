/**
 * Licencias de funcionamiento con giros CIIU, licencias de edificacion (FUE) y autorizaciones de
 * anuncios (ARQ-01 §3.11).
 *
 * <p>Genera deuda por la tasa correspondiente <b>pidiendosela a la cuenta corriente</b>; no asienta
 * por su cuenta. Con #51 eso deja de ser una promesa del diseño y pasa a ser codigo: registrar una
 * autorizacion de anuncio llama a {@code cuentacorriente.GeneradorDeCargos} —la API publica del
 * libro— y este modulo no conoce {@code cuenta_corriente_asiento} ni tiene privilegio sobre ella.
 * Spring Modulith rechaza cualquier entrada por sus paquetes internos (ARQ-01 §4 regla 2).
 *
 * <p>La licencia de funcionamiento sigue sin generar deuda, y es distinto: su derecho de tramite se
 * paga en caja de tasas antes de emitir, y un derecho de tramite no se determina, no devenga
 * interes y no prescribe.
 */
@org.jspecify.annotations.NullMarked
package kamayuk.rentas.licencias;
