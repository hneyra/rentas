package kamayuk.rentas.valores.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.ClaveDeObligacionPublica;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionCompartida;
import kamayuk.rentas.cuentacorriente.OrigenDeLaObligacion;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.EmisionDeValoresDeMultas;
import kamayuk.rentas.valores.ValorDeMulta;
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
    private final ConsultaDeDeudaPublica deuda;
    private final OrigenDeLaObligacion origen;

    public EmisionDeValoresDeMultasValores(
            RegistrarValor registrar, ConsultaDeDeudaPublica deuda, OrigenDeLaObligacion origen) {
        this.registrar = registrar;
        this.deuda = deuda;
        this.origen = origen;
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
    @Transactional(
            noRollbackFor = {
                SinDeudaQueFormalizar.class,
                ObligacionCompartida.class,
                ObligacionYaFormalizada.class
            })
    public ValorDeMulta emitirPorMulta(
            long contribuyenteId,
            String tributo,
            Ejercicio ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String referenciaDelOrigen,
            LocalDate fecha,
            Observacion observacion) {

        Objects.requireNonNull(referenciaDelOrigen, "Hay que decir que multa se formaliza (#371)");
        Objects.requireNonNull(tributo, "La multa se formaliza sobre un tributo");
        Objects.requireNonNull(ejercicio, "La multa se formaliza sobre un ejercicio");
        Objects.requireNonNull(fecha, "La emision necesita su fecha (regla 9)");
        Objects.requireNonNull(observacion, "Sin observacion no se emite (regla 10, RNF-052)");

        SeleccionDeObligacion obligacion =
                new SeleccionDeObligacion(tributo, ejercicio, predioId, vehiculoId);

        // Hasta #401 esto era un parche: RegistrarValor aceptaba una obligacion en 0,00 y
        // emitia una resolucion de multa de 0,00 por una papeleta ya pagada, asi que aqui se
        // reescribia a mano que es «tener deuda». Desde #401 RegistrarValor solo formaliza lo
        // que `pendientesDe` devuelve, y la regla vive en `ObligacionPublica#estaPendiente`.
        // La pregunta se sigue haciendo AQUI, y ANTES, por dos motivos que no son el parche:
        // una papeleta pagada tiene que salir SIN_DEUDA y no tropezar antes con la contencion
        // de #371 de abajo; y el rechazo de RegistrarValor cruza su proxy transaccional, que
        // marca rollback-only la transaccion en la que quien llama anota SIN_DEUDA. Medido al
        // quitar esta pregunta: `ValoresMasivosYReportesJdbcTest` «sin deuda que formalizar»
        // sale con UnexpectedRollbackException.
        if (!estaPendiente(contribuyenteId, obligacion, fecha)) {
            throw new SinDeudaQueFormalizar(
                    "La obligacion de "
                            + tributo
                            + " del ejercicio "
                            + ejercicio.valor()
                            + " no debe nada al "
                            + fecha
                            + ": no hay nada que formalizar");
        }

        // La contencion de #371, ANTES de numerar nada: la obligacion es la de todas las multas
        // del obligado en ese tributo, ejercicio y unidad. Si otra papeleta tiene deuda en ella,
        // una RM la formalizaria tambien; y si ya salio de ORDINARIA, un valor ya la formalizo y
        // `moverAValor` volveria a abonar una ORDINARIA que no debe nada.
        origen.exigirQueSoloLaOrigine(contribuyenteId, obligacion, referenciaDelOrigen);
        if (!origen.sigueEnOrdinaria(contribuyenteId, obligacion)) {
            throw new ObligacionYaFormalizada(
                    "La obligacion de "
                            + tributo
                            + " del ejercicio "
                            + ejercicio.valor()
                            + " ya no esta en ORDINARIA: un valor ya la formalizo, y otra"
                            + " resolucion de multa seria un segundo titulo por la misma deuda");
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

    /**
     * Si esa obligacion concreta debe algo a esa fecha: si esta entre las {@link
     * ConsultaDeDeudaPublica#pendientesDe pendientes}, cruzada por la clave del libro (#401, #407).
     */
    private boolean estaPendiente(
            long contribuyenteId, SeleccionDeObligacion obligacion, LocalDate fecha) {
        ClaveDeObligacionPublica buscada =
                new ClaveDeObligacionPublica(
                        obligacion.tributo(),
                        obligacion.ejercicio(),
                        obligacion.predioId(),
                        obligacion.vehiculoId());
        return deuda.pendientesDe(contribuyenteId, fecha).stream()
                .anyMatch(o -> o.clave().equals(buscada));
    }

    private static String mensajeDe(RuntimeException fallo) {
        String mensaje = fallo.getMessage();
        return mensaje == null ? "La obligacion no tiene deuda a esa fecha" : mensaje;
    }
}
