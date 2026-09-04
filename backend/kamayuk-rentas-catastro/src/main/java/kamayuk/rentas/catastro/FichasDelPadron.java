package kamayuk.rentas.catastro;

import java.time.LocalDate;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * La grilla de fichas del padron, publicada para otros contextos acotados (ADR-0015 §2, #344).
 *
 * <p>Es la quinta API publica de este modulo, despues de {@link LectorDeFichas}, {@link
 * PrediosDelContribuyente}, {@link LectorDeCaracteristicas} y {@link PadronDePredios}. Vive en el
 * paquete raiz por lo mismo que las otras cuatro: Spring Modulith trata como interno todo lo que
 * esta en un subpaquete.
 *
 * <h2>Para que existe</h2>
 *
 * <p>Para que {@code rentas} pueda servir la consulta de fichas <b>con su estado de
 * conciliacion</b> (ADR-0015). La conciliacion es un derivado de {@code declaracion_jurada}, que es
 * de rentas, y {@code catastro} no puede mirarla: dependeria de rentas y {@code
 * verificarArquitectura} rechaza el ciclo. La lectura compuesta vive por tanto en {@code rentas}
 * —el patron de {@code ConsultaPrediosController}—, y necesita pedirle a catastro las mismas filas
 * que catastro sirve en {@code GET /api/v1/catastro/fichas}.
 *
 * <p>Sin este puerto la unica salida habria sido que {@code rentas} consultara {@code predio},
 * {@code ficha_catastral} y {@code titularidad} por su cuenta, cruzando el limite del contexto y
 * duplicando —en otro SQL, que envejeceria aparte— la resolucion de la version vigente a una fecha.
 *
 * <h2>Lo que este puerto no da</h2>
 *
 * <p>No da el identificador del titular (ver {@link FichaDelPadron}), no da importes y no escribe:
 * es exactamente la misma proyeccion que la grilla, con la misma fecha de corte.
 */
public interface FichasDelPadron {

    /**
     * La pagina de fichas que cumplen el criterio, con la version y el titular <b>vigentes a esa
     * fecha</b>.
     *
     * <p>La fecha entra como argumento y no se lee del reloj (regla 9): la grilla muestra la ficha
     * y el titular vigentes a una fecha, no «los ultimos», y atender una reclamacion de 2027 en
     * 2029 con el titular de hoy dirige la notificacion a quien ya no era propietario.
     *
     * @throws IllegalArgumentException si {@code criterio.tipo()} no es un tipo de ficha conocido
     */
    Pagina<FichaDelPadron> buscar(
            BusquedaDeFichas criterio, LocalDate aLaFecha, Paginacion paginacion);
}
