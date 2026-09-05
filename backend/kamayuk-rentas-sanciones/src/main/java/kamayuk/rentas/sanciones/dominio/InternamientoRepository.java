package kamayuk.rentas.sanciones.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;

/**
 * El depósito municipal contra PostgreSQL: los ingresos y lo que les pasa después. Ningún método
 * recibe la municipalidad (regla 2).
 *
 * <p>Un solo repositorio para las dos tablas, como {@code ExpedienteRepository} con el expediente y
 * sus valores (#40): {@code internamiento_movimiento} no tiene vida propia —no se consulta sin su
 * internamiento— y separarlo obligaría a que cada caso de uso compusiera dos repositorios para
 * responder una pregunta que es una.
 *
 * <p><b>Solo inserta.</b> V41 le retira a {@code kamayuk_app} el {@code UPDATE} sobre {@code
 * internamiento} y no se lo da a sus movimientos.
 */
public interface InternamientoRepository {

    Internamiento registrar(Internamiento internamiento);

    Optional<Internamiento> porId(long id);

    /**
     * El internamiento <b>vigente</b> de esa placa: el que todavía no se liberó.
     *
     * <p>Por placa y no por identificador porque es lo que la pantalla teclea, y «vigente» porque
     * un vehículo puede haber entrado y salido varias veces: liberar «el de la placa» sin más
     * entregaría el acta contra un internamiento cerrado hace un año.
     */
    Optional<Internamiento> vigenteDePlaca(String placa);

    MovimientoDeInternamiento registrar(MovimientoDeInternamiento movimiento);

    /** Los movimientos de un internamiento, del más antiguo al más reciente. */
    List<MovimientoDeInternamiento> movimientosDe(long internamientoId);

    /** Los movimientos de los internamientos de una papeleta, para el listado de actos (AC 4). */
    List<MovimientoDeInternamiento> movimientosDePapeleta(long papeletaId);

    /** Los internamientos que dispuso una papeleta. */
    List<Internamiento> dePapeleta(long papeletaId);

    /**
     * La grilla de la pantalla, paginada.
     *
     * @param aLaFecha el día con el que se cuentan los días en depósito (regla 9, RNF-075)
     */
    Pagina<InternamientoEnConsulta> buscar(
            CriterioDeInternamiento criterio, LocalDate aLaFecha, Paginacion paginacion);
}
