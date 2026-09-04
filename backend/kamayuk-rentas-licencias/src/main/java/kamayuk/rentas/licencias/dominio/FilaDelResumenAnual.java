package kamayuk.rentas.licencias.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Un año del resumen de licencias por año (#54, RF-115).
 *
 * <h2>«Vigentes al cierre» tiene un cierre, y por eso viaja</h2>
 *
 * <p>Cuantas licencias de 2024 seguian vigentes depende del dia al que se pregunte, y para un año
 * cerrado ese dia es el 31 de diciembre; para el año en curso, la fecha de corte del reporte.
 * {@link #alCierre} dice cual de los dos se uso. Sin ella la misma fila impresa en enero y en
 * diciembre diria numeros distintos sin que ninguno pudiera explicarse (regla 9, RNF-075).
 *
 * <h2>El derecho de tramite puede faltar, y entonces no vale cero</h2>
 *
 * <p>La recaudacion por derecho de tramite se le pide a {@code tesoreria} por el <b>concepto del
 * TUPA</b> que el conjunto sellado de ese año nombra. Un año cuyo conjunto no lo tenga —o que no
 * tenga conjunto sellado— no se puede sumar: {@link #derechoDeTramite} llega nulo y {@link
 * #derechoNoDisponible} dice que llave falta.
 *
 * <p><b>No se pone cero</b>, y es la leccion literal de #48: un cero es indistinguible de una cifra
 * correcta cuando llega al papel, y esta hoja se usa para conciliar lo que la caja recaudo. La
 * interfaz imprime «—».
 *
 * @param ejercicio el año
 * @param emitidas cuantas licencias se emitieron ese año
 * @param canceladas cuantas se cancelaron ese año, sean del año que sean
 * @param duplicados cuantos duplicados se autorizaron ese año
 * @param vigentesAlCierre cuantas de las emitidas ese año seguian vigentes en {@link #alCierre}
 * @param derechoDeTramite lo recaudado por el concepto del TUPA; nulo si no se pudo resolver
 * @param derechoNoDisponible por que no se pudo, nombrando la llave que falta; nulo si si se pudo
 * @param alCierre el dia al que se derivo «vigentes al cierre» y hasta el que se sumo lo recaudado
 */
public record FilaDelResumenAnual(
        Ejercicio ejercicio,
        long emitidas,
        long canceladas,
        long duplicados,
        long vigentesAlCierre,
        @Nullable Dinero derechoDeTramite,
        @Nullable String derechoNoDisponible,
        LocalDate alCierre) {

    public FilaDelResumenAnual {
        Objects.requireNonNull(ejercicio, "Una fila del resumen anual es de un año concreto");
        Objects.requireNonNull(alCierre, "Toda cifra indica a que fecha esta (regla 9, RNF-075)");
        if (emitidas < 0 || canceladas < 0 || duplicados < 0 || vigentesAlCierre < 0) {
            throw new IllegalArgumentException(
                    "Un resumen no cuenta menos de cero licencias en " + ejercicio);
        }
        if (vigentesAlCierre > emitidas) {
            throw new IllegalArgumentException(
                    "En "
                            + ejercicio
                            + " se emitieron "
                            + emitidas
                            + " licencias y quedarian "
                            + vigentesAlCierre
                            + " vigentes al cierre: no puede haber mas vigentes que emitidas, y que"
                            + " las haya significa que las dos cifras se contaron sobre poblaciones"
                            + " distintas");
        }
        if ((derechoDeTramite == null) == (derechoNoDisponible == null)) {
            throw new IllegalArgumentException(
                    "O hay recaudacion con su cifra, o hay un motivo por el que no se pudo"
                            + " calcular, y exactamente uno de los dos: una fila con las dos cosas"
                            + " -o con ninguna- deja al lector adivinando si el cero es un cero o"
                            + " un dato que falta (#48)");
        }
    }

    /** La fila de un año cuya recaudacion si se pudo resolver. */
    public static FilaDelResumenAnual con(
            Ejercicio ejercicio,
            long emitidas,
            long canceladas,
            long duplicados,
            long vigentesAlCierre,
            Dinero derecho,
            LocalDate alCierre) {
        return new FilaDelResumenAnual(
                ejercicio,
                emitidas,
                canceladas,
                duplicados,
                vigentesAlCierre,
                Objects.requireNonNull(derecho, "La recaudacion resuelta lleva su cifra"),
                null,
                alCierre);
    }

    /** La fila de un año cuya recaudacion no se pudo resolver, con el motivo. */
    public static FilaDelResumenAnual sinDerecho(
            Ejercicio ejercicio,
            long emitidas,
            long canceladas,
            long duplicados,
            long vigentesAlCierre,
            String motivo,
            LocalDate alCierre) {
        return new FilaDelResumenAnual(
                ejercicio,
                emitidas,
                canceladas,
                duplicados,
                vigentesAlCierre,
                null,
                Objects.requireNonNull(motivo, "Sin cifra hay que decir por que"),
                alCierre);
    }
}
