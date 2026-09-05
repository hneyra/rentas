package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro;
import kamayuk.rentas.nucleo.dominio.proyeccion.HechoRecibido;
import kamayuk.rentas.nucleo.dominio.proyeccion.ProyeccionDeCatastro;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El ingestor: trae los hechos de {@code catastro}, los aplica y los acusa (C-8, ADR-0027).
 *
 * <h2>Este objeto NO abre transaccion</h2>
 *
 * <p>Las abre {@link AplicarUnHecho}, una por hecho. Ver su javadoc: envolver el bucle es el
 * defecto que este proyecto ha medido cuatro veces.
 *
 * <h2>El acuse va DESPUES de confirmar, y por lotes</h2>
 *
 * <p>Se acusa lo que quedo escrito, no lo que se intento escribir. Un acuse que se pierda hace que
 * el emisor vuelva a servir lo mismo y este lado lo descarte por deduplicacion: <b>la entrega es al
 * menos una vez y quien deduplica es el receptor</b>. Acusar antes de confirmar seria lo contrario
 * —el hecho dejaria de servirse sin estar aplicado— y nada lo diria.
 *
 * <h2>Los dos fallos no se tratan igual</h2>
 *
 * <table>
 *   <tr><th>Que paso</th><th>Que se hace</th><th>Por que</th></tr>
 *   <tr><td>{@code catastro} no contesta</td><td>se corta la vuelta, sin acusar nada</td>
 *       <td>Se arregla levantando un despliegue y va a arreglarse solo</td></tr>
 *   <tr><td>El hecho no se puede aplicar</td><td>se aparta, SE ACUSA y se avisa a una persona</td>
 *       <td>Reintentarlo no lo arregla y <b>bloquea la cola detras de el</b>: la proyeccion se
 *           quedaria congelada sin un solo error visible</td></tr>
 * </table>
 *
 * <p><b>La vuelta se corta en el primer fallo de transporte y no sigue con el resto</b>, y eso es
 * deliberado: los hechos llegan en orden y aplicar el 40 saltandose el 39 es exactamente lo que la
 * secuencia existe para impedir.
 */
public class IngestarHechosDeCatastro {

    private static final Logger log = LoggerFactory.getLogger(IngestarHechosDeCatastro.class);

    /** Cuantos se piden por vuelta. Un lote y no la cola entera: una vuelta tiene que acabar. */
    private static final int POR_VUELTA = 200;

    private final FuenteDeHechosDeCatastro fuente;
    private final AplicarUnHecho aplicador;
    private final AlertaDeHechosSinAplicar alerta;
    private final Clock reloj;

    public IngestarHechosDeCatastro(
            FuenteDeHechosDeCatastro fuente,
            AplicarUnHecho aplicador,
            AlertaDeHechosSinAplicar alerta,
            Clock reloj) {
        this.fuente = fuente;
        this.aplicador = aplicador;
        this.alerta = alerta;
        this.reloj = reloj;
    }

    /**
     * Una vuelta: trae un lote, lo aplica y lo acusa.
     *
     * @throws FuenteDeHechosDeCatastro.CatastroNoContesta si no se pudo preguntar. No se acusa
     *     nada: la vuelta siguiente lo reintenta
     */
    public Vuelta ingerir() {
        Instant cuando = reloj.instant();
        FuenteDeHechosDeCatastro.Lote lote = fuente.pendientes(POR_VUELTA);
        List<UUID> resueltos = new ArrayList<>();
        int aplicados = 0;
        int yaEstaban = 0;
        int descartados = 0;
        int muertos = 0;

        for (HechoRecibido hecho : lote.hechos()) {
            try {
                ProyeccionDeCatastro.Aplicacion resultado = aplicador.aplicar(hecho, cuando);
                switch (resultado) {
                    case APLICADO -> aplicados++;
                    case YA_APLICADO -> yaEstaban++;
                    case DESCARTADO_POR_VIEJO -> {
                        descartados++;
                        // SE DICE. Descartar en silencio es la mitad del defecto que la secuencia
                        // existe para impedir: la fila queda plausible y nadie sabe que se ignoro
                        // un hecho. Nivel de aviso y no de error: es una decision correcta, no un
                        // fallo — lo que separa uno de otro en este sistema es si deja incidencia.
                        log.warn(
                                "Hecho {} ({}, secuencia {}) DESCARTADO POR VIEJO: la fila que hay"
                                        + " en la proyeccion salio de un hecho posterior. No es un"
                                        + " fallo: es lo que impide que un hecho viejo que llega"
                                        + " tarde pise a uno nuevo ya aplicado",
                                hecho.eventoId(),
                                hecho.tipo(),
                                hecho.secuencia());
                    }
                    // El `default` esta por Checkstyle y no sobra: el dia que la proyeccion
                    // devuelva un cuarto resultado, contarlo como si no hubiera pasado nada seria
                    // exactamente el silencio que este informe existe para no tener.
                    default ->
                            throw new IllegalStateException(
                                    "La proyeccion devolvio «"
                                            + resultado
                                            + "», que este informe no sabe contar");
                }
                resueltos.add(hecho.eventoId());
            } catch (ProyeccionDeCatastro.NoSePuedeAplicar noSePuede) {
                String motivo = motivoDe(noSePuede);
                aplicador.matar(hecho, motivo, cuando);
                muertos++;
                // Se acusa igual: apartado y acusado deja de servirse, que es lo que impide que
                // bloquee la cola detras de el. Sin esto, un solo hecho imposible congela la
                // proyeccion entera y ninguna cifra lo dice.
                resueltos.add(hecho.eventoId());
                alerta.hayUnHechoSinAplicar(hecho, motivo, aplicador.muertosSinExplicar());
            }
        }

        fuente.acusar(List.copyOf(resueltos));
        return new Vuelta(
                lote.hechos().size(), aplicados, yaEstaban, descartados, muertos, lote.quedan());
    }

    private static String motivoDe(RuntimeException noSePudo) {
        String mensaje = noSePudo.getMessage();
        return mensaje == null ? noSePudo.getClass().getSimpleName() : mensaje;
    }

    /**
     * Lo que hizo una vuelta.
     *
     * @param quedan cuantos le quedaban al emisor tras servir este lote. Es lo que permite decir
     *     «faltan 9 000» en vez de «faltan», y decidir si hay que dar otra vuelta
     */
    public record Vuelta(
            int leidos, int aplicados, int yaEstaban, int descartados, int muertos, long quedan) {

        public Vuelta {
            Objects.requireNonNull(Integer.valueOf(leidos), "la vuelta cuenta lo que leyo");
        }

        /** Si esta vuelta no leyo nada, no hace falta dar otra. */
        public boolean vacia() {
            return leidos == 0;
        }

        @Override
        public String toString() {
            return leidos
                    + " hecho(s) leidos: "
                    + aplicados
                    + " aplicados, "
                    + yaEstaban
                    + " ya estaban, "
                    + descartados
                    + " descartados por viejos, "
                    + muertos
                    + " sin poder aplicar; quedan "
                    + quedan
                    + " en el buzon del emisor";
        }
    }
}
