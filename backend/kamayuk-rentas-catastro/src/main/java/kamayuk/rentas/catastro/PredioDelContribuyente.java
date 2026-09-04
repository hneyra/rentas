package kamayuk.rentas.catastro;

import java.util.Objects;
import kamayuk.rentas.dominio.Porcentaje;

/**
 * Un predio del que un contribuyente es titular, publicado para otros contextos acotados (ARQ-01
 * §4, #25).
 *
 * <p>No es {@link kamayuk.rentas.catastro.dominio.Predio} entero: quien consulta desde fuera
 * necesita identificar la unidad y saber cuanto le corresponde, no el catalogo de vias, manzanas ni
 * el estado del padron —eso es {@code .dominio}, y cruzar la frontera del modulo con ello obligaria
 * a este contexto a exponer su modelo interno completo—.
 *
 * @param predioId el identificador interno, para cruzar con la deuda de {@code cuentacorriente}
 * @param codigoReferenciaCatastral el codigo con el que se identifica el predio en ventanilla
 * @param tipo {@code URBANO} o {@code RUSTICO}
 * @param direccion la direccion del predio
 * @param porcentajeTitularidad cuanto le corresponde al contribuyente consultado, a la fecha
 * @param porcentajeRegistradoDelPredio cuanto suman <b>todas</b> las cuotas del predio a esa fecha,
 *     las de este contribuyente y las de los demas. No es lo mismo que el anterior y ahi esta el
 *     dato: si suma menos de 100, el predio tiene dueño <b>a medias</b> —en Catacaos eso pasa en
 *     304 predios, y otros 4 977 no tienen ninguna cuota (#690)— y la base imponible sale ponderada
 *     por lo que hay registrado, no por el predio entero. Quien lee una determinacion ve «60 %» y
 *     entiende que ese es el porcentaje del contribuyente; lo que no puede ver sin esto es que
 *     <b>nadie</b> tiene el 40 % restante
 */
public record PredioDelContribuyente(
        long predioId,
        String codigoReferenciaCatastral,
        String tipo,
        String direccion,
        Porcentaje porcentajeTitularidad,
        Porcentaje porcentajeRegistradoDelPredio) {

    /**
     * La forma anterior a #690, que da el predio por <b>completo</b>.
     *
     * <p>Cien y no la cuota propia: quien no sabe cuanto hay registrado no puede afirmar que falte
     * algo, y tomar la cuota propia haria que <b>toda copropiedad legitima</b> —el 50 % de un
     * predio cuyo otro 50 % tiene dueño— saliera avisada como incompleta. Un aviso que salta en el
     * caso corriente deja de leerse, y entonces no avisa del caso que importa.
     */
    public PredioDelContribuyente(
            long predioId,
            String codigoReferenciaCatastral,
            String tipo,
            String direccion,
            Porcentaje porcentajeTitularidad) {
        this(
                predioId,
                codigoReferenciaCatastral,
                tipo,
                direccion,
                porcentajeTitularidad,
                new Porcentaje(java.math.BigDecimal.valueOf(100)));
    }

    /** El predio tiene dueño completo: sus cuotas suman 100 a la fecha consultada. */
    public boolean titularidadCompleta() {
        return porcentajeRegistradoDelPredio.valor().compareTo(java.math.BigDecimal.valueOf(100))
                == 0;
    }

    public PredioDelContribuyente {
        Objects.requireNonNull(
                codigoReferenciaCatastral, "El predio necesita su codigo de referencia catastral");
        Objects.requireNonNull(tipo, "El predio necesita su tipo");
        Objects.requireNonNull(direccion, "El predio necesita su direccion");
        Objects.requireNonNull(porcentajeTitularidad, "La titularidad necesita su porcentaje");
        Objects.requireNonNull(
                porcentajeRegistradoDelPredio,
                "Hace falta saber cuanto del predio esta registrado, no solo la cuota propia");
    }
}
