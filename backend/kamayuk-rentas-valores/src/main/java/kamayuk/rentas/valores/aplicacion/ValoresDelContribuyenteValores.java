package kamayuk.rentas.valores.aplicacion;

import java.time.LocalDate;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.valores.ValorDelContribuyente;
import kamayuk.rentas.valores.ValoresDelContribuyente;
import kamayuk.rentas.valores.dominio.CriterioDeConsultaDeValores;
import kamayuk.rentas.valores.dominio.Valor;
import kamayuk.rentas.valores.dominio.ValorEnConsulta;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementa {@link ValoresDelContribuyente} sobre {@link ConsultaDeValores} (#25, RF-046).
 *
 * <p><b>No escribe una segunda consulta.</b> Arma el mismo {@code CriterioDeConsultaDeValores} que
 * arma {@code ConsultaValoresController} para {@code consulta_valores} —con el contribuyente como
 * unico filtro— y llama al mismo {@link ConsultaDeValores#buscar}. Es lo que garantiza que la
 * pestaña «Valores» de la consulta unificada y {@code GET /consultas/valores} no puedan discrepar:
 * la situacion se resuelve en el mismo SQL, los tributos se agregan en el mismo {@code string_agg},
 * y el periodo se compone en el mismo sitio.
 *
 * <p>Lo unico que este adaptador tira por el camino es el nombre del contribuyente que {@code
 * ConsultaDeValores} resuelve para su grilla: la ficha unificada ya sabe de quien es —lo resolvio
 * para poder responder 404—, y traerlo veinte veces mas seria pagar la misma lectura dos veces.
 */
@Service
public class ValoresDelContribuyenteValores implements ValoresDelContribuyente {

    private final ConsultaDeValores consulta;

    public ValoresDelContribuyenteValores(ConsultaDeValores consulta) {
        this.consulta = consulta;
    }

    /**
     * {@code @Transactional(readOnly = true)} aunque {@link ConsultaDeValores#buscar} ya lo lleve:
     * quien llama desde otro contexto no tiene por que saber que la implementacion delega, y una
     * anotacion que sobra se une a la transaccion de fuera sin coste.
     */
    @Override
    @Transactional(readOnly = true)
    public Pagina<ValorDelContribuyente> deTodoElContribuyente(
            long contribuyenteId, LocalDate aLaFecha, Paginacion paginacion) {
        CriterioDeConsultaDeValores criterio =
                new CriterioDeConsultaDeValores(null, contribuyenteId, null, null, null, aLaFecha);
        return consulta.buscar(criterio, paginacion).mapear(fila -> aPublico(fila.valor()));
    }

    private static ValorDelContribuyente aPublico(ValorEnConsulta fila) {
        Valor valor = fila.valor();
        return new ValorDelContribuyente(
                // El codigo -«OP», «RD», «RM»- y no el nombre de la constante: es lo que
                // `consulta_valores` publica en su columna «Tipo», y dos pantallas que
                // nombren distinto el mismo documento son dos vocabularios que mantener.
                valor.tipo().codigo(),
                valor.numero(),
                valor.ejercicio(),
                valor.fechaEmision(),
                fila.tributos(),
                fila.periodo(),
                fila.situacion().name(),
                fila.situacionA(),
                valor.montoInsoluto(),
                valor.montoReajuste(),
                valor.montoInteres(),
                valor.montoGasto(),
                valor.total(),
                valor.proyectadoA());
    }
}
