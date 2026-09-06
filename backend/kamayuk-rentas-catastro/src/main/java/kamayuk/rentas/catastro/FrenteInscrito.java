package kamayuk.rentas.catastro;

import kamayuk.rentas.dominio.Medida;
import org.jspecify.annotations.Nullable;

/**
 * Un frente del predio: a que via da, cuantos metros lineales y quien los afirmo.
 *
 * <h2>{@code longitudEstado} no es un adorno: es la mitad del dato</h2>
 *
 * <p>{@code PROPUESTA} la corto una maquina contra el eje de la via; {@code CONFIRMADA} la firmo
 * una persona (ADR-0021). De esta cifra cuelga un arbitrio que determina <b>este</b> sistema, y sin
 * este campo las dos llegan iguales: quien determine sobre metros que nadie confirmo tiene que
 * poder saberlo. Por eso viajan tambien {@code confirmadoPor} y {@code confirmadoEn} — confirmar es
 * un acto, y un acto tiene autor y hora—.
 *
 * <h2>La longitud es una {@link Medida}, con su unidad dentro</h2>
 *
 * <p>El barrido se determina sobre metros LINEALES y el recojo sobre metros CUADRADOS, y leer unos
 * por otros no falla: cobra otra cosa. {@code catastro} la publica como {@code "18.50 ML"} y este
 * lado la parte en magnitud y unidad en vez de quedarse la cifra sola, que es como se pierde.
 *
 * <p><b>Ni un importe.</b> Ni tarifa, ni factor, ni el nombre de un servicio.
 *
 * @param retiro el retiro municipal, cuando lo tiene; {@code null} si el frente no lo declara
 * @param confirmadoEn el instante en que se confirmo, como texto; {@code null} si nadie la
 *     confirmo. Texto y no instante porque es constancia y no una cifra que este sistema opere
 */
public record FrenteInscrito(
        long id,
        long viaId,
        String viaCodigo,
        String viaNombre,
        Medida longitud,
        String longitudEstado,
        boolean esPrincipal,
        @Nullable String numeracion,
        @Nullable Medida retiro,
        @Nullable String confirmadoPor,
        @Nullable String confirmadoEn) {}
