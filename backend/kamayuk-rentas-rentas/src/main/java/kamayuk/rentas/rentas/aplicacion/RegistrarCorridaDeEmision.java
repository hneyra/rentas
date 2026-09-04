package kamayuk.rentas.rentas.aplicacion;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.rentas.dominio.CorridaDeEmision;
import kamayuk.rentas.rentas.dominio.CorridaDeEmisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escribe y lee el rastro de una corrida de emision predial (#523).
 *
 * <h2>Por que es un colaborador aparte y no un metodo de {@link DeterminarPredialMasivo}</h2>
 *
 * <p>Porque hace falta una transaccion, y esa clase <b>no abre ninguna a proposito</b>: cada
 * determinacion abre la suya al entrar en {@code RegistrarDeterminacionPredial}, que es lo que hace
 * que el contribuyente que falla no se lleve por delante al siguiente. Envolver el bucle es el
 * defecto que #328 y #247 §2 documentan. Y sin transaccion no hay {@code SET LOCAL}, asi que un
 * {@code INSERT} desde alli fallaria con RLS.
 *
 * <p>Se llama <b>al final</b>, cuando el bucle ya termino y sus determinaciones ya estan
 * confirmadas cada una en la suya: si escribir el resumen falla, lo que se pierde es el resumen, no
 * la emision.
 *
 * <h2>Las simulaciones tambien se guardan</h2>
 *
 * <p>Una simulacion no asienta ninguna determinacion, pero <b>si es un hecho</b>: alguien corrio el
 * proceso y vio sus observados, que es exactamente lo que el prototipo pide hacer antes de emitir
 * —«simular primero no es una formalidad: es la unica forma de ver los observados antes de
 * emitir»—. Guardarla es lo que permite volver a esa lista sin volver a correr nada. La columna
 * {@code simulacion} las distingue, y quien lea la ultima sabe cual de las dos fue.
 */
@Service
public class RegistrarCorridaDeEmision {

    private final CorridaDeEmisionRepository repositorio;

    public RegistrarCorridaDeEmision(CorridaDeEmisionRepository repositorio) {
        this.repositorio = repositorio;
    }

    /** Deja constancia de lo que la corrida hizo, con la observacion que la ordeno (regla 10). */
    @Transactional
    public CorridaDeEmision registrar(CorridaDeEmision corrida, Observacion observacion) {
        return repositorio.guardar(corrida, observacion);
    }

    /** La ultima corrida del ejercicio, sin sus observados. Vacio si no se ha corrido ninguna. */
    @Transactional(readOnly = true)
    public Optional<CorridaDeEmision> ultimaDe(Ejercicio ejercicio) {
        return repositorio.ultimaDe(ejercicio);
    }

    /** Las ultimas corridas, mas reciente primero. */
    @Transactional(readOnly = true)
    public List<CorridaDeEmision> ultimas(int cuantas) {
        return repositorio.ultimas(cuantas);
    }

    /** Los observados de una corrida: la lista de cosas que arreglar. */
    @Transactional(readOnly = true)
    public Pagina<CorridaDeEmision.Observado> observadosDe(long corridaId, Paginacion paginacion) {
        return repositorio.observadosDe(corridaId, paginacion);
    }
}
