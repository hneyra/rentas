package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.RecaudacionDeUnTributo;
import kamayuk.rentas.cuentacorriente.RecaudacionDelLibro;
import kamayuk.rentas.cuentacorriente.RecaudadoEnElLibro;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.RecaudacionAgregada;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link RecaudacionDelLibro} sobre {@link AsientoRepository} (#53, RF-073, RF-074).
 *
 * <p><b>No interpreta nada.</b> No sabe que es una papeleta ni que {@code MULTA_TRANSITO} sea una
 * multa: recibe nombres de tributo y devuelve lo que el libro dice de ellos. Quien pregunta es
 * quien sabe que tributos le corresponden, igual que en {@link ConciliacionDeCajaCuentaCorriente}
 * es tesoreria quien sabe cuales de sus recibos abonan.
 *
 * <p>{@code readOnly = true} y ni un bloqueo: un resumen de recaudacion se mira mientras la
 * ventanilla sigue cobrando, y una lectura que pidiera {@code FOR UPDATE} pondria la cola a esperar
 * por un informe. Sin transaccion no hay {@code SET LOCAL}, y sin el la politica RLS no puede
 * evaluar {@code current_setting('app.municipalidad_id')} —la consulta <b>falla</b>—.
 */
@Service
public class RecaudacionDelLibroCuentaCorriente implements RecaudacionDelLibro {

    private final AsientoRepository asientos;

    public RecaudacionDelLibroCuentaCorriente(AsientoRepository asientos) {
        this.asientos = asientos;
    }

    @Override
    @Transactional(readOnly = true)
    public RecaudadoEnElLibro recaudadoPor(
            Collection<String> tributos, LocalDate desde, LocalDate hasta, LocalDate aLaFecha) {
        Objects.requireNonNull(tributos, "La coleccion es vacia, no nula");
        Objects.requireNonNull(desde, "El resumen dice desde cuando cuenta");
        Objects.requireNonNull(hasta, "El resumen dice hasta cuando cuenta");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException("«hasta» no puede ser anterior a «desde»");
        }

        // Sin repetidos, por lo mismo que en la conciliacion de caja: preguntar dos veces
        // por el mismo tributo no lo cobra dos veces, pero deja la lista IN(…) mas larga de
        // lo necesario y confunde a quien lea el plan.
        Collection<String> unicos = new LinkedHashSet<>(tributos);
        List<RecaudacionDeUnTributo> lineas =
                asientos.recaudadoPorTributo(unicos, desde, hasta).stream()
                        .map(RecaudacionDelLibroCuentaCorriente::aPublico)
                        .toList();
        return new RecaudadoEnElLibro(lineas, desde, hasta, aLaFecha);
    }

    /**
     * Lo cobrado de <b>todos</b> los tributos en el rango (#56, RF-130).
     *
     * <p>Sin lista de tributos que preparar: es un metodo distinto y no la lista vacia
     * reinterpretada —ver {@code AsientoRepository#recaudadoDeTodos}—. La forma de la respuesta es
     * la misma, y por eso el panel de inicio y el resumen de un area no pueden discrepar en el
     * criterio.
     */
    @Override
    @Transactional(readOnly = true)
    public RecaudadoEnElLibro recaudadoDeTodos(
            LocalDate desde, LocalDate hasta, LocalDate aLaFecha) {
        Objects.requireNonNull(desde, "El resumen dice desde cuando cuenta");
        Objects.requireNonNull(hasta, "El resumen dice hasta cuando cuenta");
        Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        if (hasta.isBefore(desde)) {
            throw new IllegalArgumentException("«hasta» no puede ser anterior a «desde»");
        }
        List<RecaudacionDeUnTributo> lineas =
                asientos.recaudadoDeTodos(desde, hasta).stream()
                        .map(RecaudacionDelLibroCuentaCorriente::aPublico)
                        .toList();
        return new RecaudadoEnElLibro(lineas, desde, hasta, aLaFecha);
    }

    private static RecaudacionDeUnTributo aPublico(RecaudacionAgregada agregada) {
        return new RecaudacionDeUnTributo(
                agregada.tributo(),
                agregada.ejercicio(),
                agregada.mes(),
                agregada.fase().name(),
                agregada.recaudado(),
                agregada.abonos());
    }
}
