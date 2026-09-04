package kamayuk.rentas.valores.aplicacion;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.EmisionDeValoresDeMultas;
import kamayuk.rentas.valores.ValorDeMulta;
import kamayuk.rentas.valores.dominio.MovimientoDeValorRepository;
import kamayuk.rentas.valores.dominio.SelectorDeObligacion;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.Valor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link EmisionDeValoresDeMultas} (#53, RF-066, RF-073).
 *
 * <h2>Toda la clase es una delegacion, y ese es el punto</h2>
 *
 * <p>{@link #emitirPorMulta} <b>no numera</b>: llama a {@link RegistrarValor#emitir}, que es el
 * mismo camino que la emision individual de #37 y el mismo que la corrida masiva por contribuyente
 * de #38. El correlativo lo entrega {@code ValorRepository#siguienteCorrelativo} con un {@code
 * UPDATE} atomico sobre {@code valor_correlativo} (V26), y aqui no hay ni una linea que componga un
 * numero. Es el primer criterio de aceptacion de #53 escrito como codigo: si esta clase inventara
 * su serie, {@code valor_correlativo} se quedaria quieto mientras salen resoluciones de multa
 * numeradas, y el dia que alguien emitiera una a mano el numero chocaria.
 *
 * <p>{@code @Transactional} sin propagacion propia: quien llama —{@code
 * sanciones.ProcesarPapeletaDeLaCorrida}— ya abrio la suya, y la emision se une a ella. Es lo que
 * garantiza que emitir el valor y marcar el item de la corrida se confirmen o se deshagan juntos;
 * si fueran dos transacciones, un corte entre las dos dejaria un valor emitido que la reanudacion
 * volveria a emitir.
 */
@Service
public class EmisionDeValoresDeMultasValores implements EmisionDeValoresDeMultas {

    /** Una multa se formaliza con una resolucion de multa. No se elige desde fuera. */
    private static final TipoValor TIPO = TipoValor.RESOLUCION_DE_MULTA;

    private final RegistrarValor registrar;
    private final MovimientoDeValorRepository movimientos;
    private final ConsultaDeDeudaPublica deuda;

    public EmisionDeValoresDeMultasValores(
            RegistrarValor registrar,
            MovimientoDeValorRepository movimientos,
            ConsultaDeDeudaPublica deuda) {
        this.registrar = registrar;
        this.movimientos = movimientos;
        this.deuda = deuda;
    }

    /**
     * {@code noRollbackFor} no es un adorno: sin el, «esta papeleta no debe nada» dejaria la
     * transaccion marcada para deshacerse y quien llama no podria ni anotar el resultado.
     *
     * <p>La comprobacion de deuda ocurre <b>antes</b> de escribir nada, asi que cuando se lanza
     * {@link SinDeudaQueFormalizar} no hay ni una fila que revertir. Spring, sin embargo, marca
     * {@code rollback-only} ante cualquier {@code RuntimeException}, y el {@code catch} de {@code
     * sanciones.ProcesarPapeletaDeLaCorrida} -que marca el candidato SIN_DEUDA en esa misma
     * transaccion- moria despues con {@code UnexpectedRollbackException}. Se descubrio ejecutando:
     * la prueba de #53 esperaba SIN_DEUDA y recibio un fallo de confirmacion.
     */
    @Override
    @Transactional(noRollbackFor = SinDeudaQueFormalizar.class)
    public ValorDeMulta emitirPorMulta(
            long contribuyenteId,
            String tributo,
            Ejercicio ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            LocalDate fecha,
            Observacion observacion) {

        Objects.requireNonNull(tributo, "La multa se formaliza sobre un tributo");
        Objects.requireNonNull(ejercicio, "La multa se formaliza sobre un ejercicio");
        Objects.requireNonNull(fecha, "La emision necesita su fecha (regla 9)");
        Objects.requireNonNull(observacion, "Sin observacion no se emite (regla 10, RNF-052)");

        // La comprobacion que el puerto promete, y que hay que hacer AQUI porque
        // RegistrarValor no la hace: `emitir` acepta una obligacion con total cero -la
        // encuentra entre las disponibles y no mueve su fase porque no hay nada que mover-,
        // y emitiria una resolucion de multa de 0,00 por una papeleta ya pagada. Se
        // descubrio ejecutando: la prueba de #53 esperaba SIN_DEUDA y recibio GENERADO.
        if (!tieneDeuda(contribuyenteId, tributo, ejercicio, predioId, vehiculoId, fecha)) {
            throw new SinDeudaQueFormalizar(
                    "La obligacion de "
                            + tributo
                            + " del ejercicio "
                            + ejercicio.valor()
                            + " no debe nada al "
                            + fecha
                            + ": no hay nada que formalizar");
        }

        try {
            Valor emitido =
                    registrar.emitir(
                            TIPO,
                            contribuyenteId,
                            List.of(
                                    new SelectorDeObligacion(
                                            tributo, ejercicio, predioId, vehiculoId)),
                            observacion,
                            fecha);
            return new ValorDeMulta(
                    Objects.requireNonNull(emitido.id(), "El valor recien emitido ya tiene su id"),
                    emitido.numero(),
                    emitido.tipo().codigo(),
                    emitido.ejercicio(),
                    emitido.fechaEmision(),
                    emitido.total(),
                    emitido.proyectadoA());
        } catch (RegistrarValor.ObligacionSinDeuda sinDeuda) {
            // Se traduce a la excepcion del puerto: `sanciones` no puede ver una clase de
            // `valores.aplicacion` sin cruzar el limite del modulo (ARQ-01 §4).
            throw new SinDeudaQueFormalizar(mensajeDe(sinDeuda));
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Set<Long> conPaseACoactiva(Collection<Long> valorIds) {
        Objects.requireNonNull(valorIds, "La coleccion es vacia, no nula");
        return movimientos.conPaseACoactiva(valorIds);
    }

    /** Si esa obligacion concreta debe algo a esa fecha. */
    private boolean tieneDeuda(
            long contribuyenteId,
            String tributo,
            Ejercicio ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            LocalDate fecha) {

        for (ObligacionPublica obligacion : deuda.deTodoElContribuyente(contribuyenteId, fecha)) {
            boolean esLaMisma =
                    obligacion.tributo().equals(tributo)
                            && obligacion.ejercicio().equals(ejercicio)
                            && java.util.Objects.equals(obligacion.predioId(), predioId)
                            && java.util.Objects.equals(obligacion.vehiculoId(), vehiculoId);
            if (esLaMisma && obligacion.total().esPositivo()) {
                return true;
            }
        }
        return false;
    }

    private static String mensajeDe(RuntimeException fallo) {
        String mensaje = fallo.getMessage();
        return mensaje == null ? "La obligacion no tiene deuda a esa fecha" : mensaje;
    }
}
