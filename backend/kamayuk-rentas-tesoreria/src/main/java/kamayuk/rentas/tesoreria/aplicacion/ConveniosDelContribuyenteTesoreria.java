package kamayuk.rentas.tesoreria.aplicacion;

import java.time.LocalDate;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.tesoreria.ConvenioDelContribuyente;
import kamayuk.rentas.tesoreria.ConveniosDelContribuyente;
import kamayuk.rentas.tesoreria.dominio.ConvenioEnConsulta;
import kamayuk.rentas.tesoreria.dominio.CriterioDeConvenios;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link ConveniosDelContribuyente} sobre {@link ConsultaDeConvenios} (#25, RF-046).
 *
 * <p><b>No escribe una segunda consulta.</b> Arma el mismo {@code CriterioDeConvenios} que arma
 * {@code ConvenioController} para {@code consulta_convenios} —con el codigo del contribuyente como
 * unico filtro— y llama al mismo {@link ConsultaDeConvenios#listar}. Es lo que garantiza que la
 * pestaña «Fraccionamientos» de la consulta unificada y el listado de convenios no puedan decir
 * cosas distintas del mismo convenio: el estado se deriva de los movimientos igual, las cuotas
 * vencidas se cuentan a la misma fecha igual, y el saldo se compone igual.
 *
 * <p>Duplicar el SQL habria sido mas corto —dos filtros y un {@code ORDER BY}— y habria dejado dos
 * escrituras de la misma regla, que es exactamente el defecto que {@code SituacionDelValor} ya
 * tiene que vigilar con una prueba porque en {@code valores} no se pudo evitar.
 */
@Service
public class ConveniosDelContribuyenteTesoreria implements ConveniosDelContribuyente {

    private final ConsultaDeConvenios consulta;

    public ConveniosDelContribuyenteTesoreria(ConsultaDeConvenios consulta) {
        this.consulta = consulta;
    }

    /**
     * {@code @Transactional(readOnly = true)} aunque {@link ConsultaDeConvenios#listar} ya lo
     * lleve: quien llama desde otro contexto no tiene por que saber que la implementacion delega, y
     * una anotacion que sobra se une a la transaccion de fuera sin coste. Quitarla aqui dejaria el
     * puerto dependiendo de un detalle de su propia implementacion.
     */
    @Override
    @Transactional(readOnly = true)
    public Pagina<ConvenioDelContribuyente> deTodoElContribuyente(
            String codigoContribuyente, LocalDate aLaFecha, Paginacion paginacion) {
        CriterioDeConvenios criterio =
                new CriterioDeConvenios(null, codigoContribuyente, null, null, null, aLaFecha);
        return consulta.listar(criterio, paginacion)
                .mapear(ConveniosDelContribuyenteTesoreria::aPublico);
    }

    private static ConvenioDelContribuyente aPublico(ConvenioEnConsulta fila) {
        return new ConvenioDelContribuyente(
                fila.numero().impreso(),
                fila.fecha(),
                fila.fechaCorte(),
                fila.deudaAcogida(),
                fila.cuotas(),
                fila.pagadas(),
                fila.vencidas(),
                fila.saldo(),
                fila.saldoA(),
                fila.estado().name(),
                fila.motivoDelCierre());
    }
}
