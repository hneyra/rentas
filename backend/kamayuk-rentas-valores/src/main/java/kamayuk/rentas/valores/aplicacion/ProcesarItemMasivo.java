package kamayuk.rentas.valores.aplicacion;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.dominio.SelectorDeObligacion;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.dominio.ValorMasivoItem;
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resuelve un candidato de una corrida masiva: emite su valor, o lo marca sin deuda (#38).
 *
 * <p><b>Es un {@code @Service} distinto de {@link GenerarCorridaMasiva} a proposito</b> -mismo
 * patron que {@code catastro.RegistrarVia} frente a {@code ImportarVias}-. {@link #procesar} lleva
 * su propio {@code @Transactional}; llamarlo desde el bucle de {@link GenerarCorridaMasiva} pasa
 * por el proxy de Spring y abre una transaccion nueva por candidato. Si estuviera en la misma clase
 * que el bucle, la llamada seria auto-invocacion -el proxy no intercepta una llamada a {@code
 * this}- y todos los candidatos de la corrida caerian en una sola transaccion: la primera fila que
 * fallara se llevaria consigo a todas las que ya se habian resuelto en esa misma corrida,
 * exactamente el defecto que la carga inicial de vias ya demostro y que esta clase evita igual.
 *
 * <h2>Por que emitir y marcar van juntos</h2>
 *
 * <p>{@link kamayuk.rentas.valores.dominio.ValorMasivoRepository#marcarGenerado} tiene que ocurrir
 * en <b>la misma transaccion</b> que {@link RegistrarValor#emitir}: si el valor se emitiera y el
 * proceso se cortara antes de marcar el item, una reanudacion volveria a ver el item {@code
 * PENDIENTE} y emitiria un segundo valor para la misma obligacion -exactamente lo que el AC de #38
 * prohibe ("sin duplicar valores")-. {@link RegistrarValor#emitir} ya es {@code @Transactional}; al
 * llamarlo desde dentro de {@link #procesar} -tambien {@code @Transactional}, propagacion por
 * omision {@code REQUIRED}- la emision se une a la transaccion de este metodo en vez de abrir la
 * suya, y las dos escrituras se confirman o se deshacen juntas.
 *
 * <p>{@link #procesar} recibe la {@link Observacion} de la corrida <b>en su propia firma</b>, y no
 * solo dentro de {@code corrida}: es lo que exige la regla 10 sobre todo metodo transaccional que
 * escribe (ArchUnit, {@code ConObservacionEnLasEscrituras}), y lo que deja a la vista, sin abrir el
 * agregado, con que observacion queda auditada cada valor de la corrida.
 */
@Service
public class ProcesarItemMasivo {

    private final ConsultaDeDeudaPublica deuda;
    private final RegistrarValor registrar;
    private final ValorMasivoRepository repositorio;

    public ProcesarItemMasivo(
            ConsultaDeDeudaPublica deuda,
            RegistrarValor registrar,
            ValorMasivoRepository repositorio) {
        this.deuda = deuda;
        this.registrar = registrar;
        this.repositorio = repositorio;
    }

    /** Como termino de resolver el candidato. */
    public enum Resultado {
        GENERADO,
        SIN_DEUDA
    }

    @Transactional
    public Resultado procesar(ValorMasivo corrida, ValorMasivoItem item, Observacion observacion) {
        long itemId = Objects.requireNonNull(item.id(), "Un item leido de la base ya tiene su id");
        List<ObligacionPublica> disponibles =
                deuda.deTodoElContribuyente(item.contribuyenteId(), corrida.fechaCriterio());

        List<SelectorDeObligacion> obligaciones = new ArrayList<>();
        for (ObligacionPublica obligacion : disponibles) {
            if (corrida.coincideTributo(obligacion.tributo())
                    && corrida.coincideEjercicio(obligacion.ejercicio())
                    && obligacion.total().esPositivo()) {
                obligaciones.add(
                        new SelectorDeObligacion(
                                obligacion.tributo(),
                                obligacion.ejercicio(),
                                obligacion.predioId(),
                                obligacion.vehiculoId()));
            }
        }

        if (obligaciones.isEmpty()) {
            repositorio.marcarSinDeuda(itemId);
            return Resultado.SIN_DEUDA;
        }

        var emitido =
                registrar.emitir(
                        corrida.tipo(),
                        item.contribuyenteId(),
                        obligaciones,
                        observacion,
                        corrida.fechaCriterio());
        long valorId =
                Objects.requireNonNull(emitido.id(), "El valor recien emitido ya tiene su id");
        repositorio.marcarGenerado(itemId, valorId);
        return Resultado.GENERADO;
    }
}
