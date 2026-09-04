package kamayuk.rentas.tesoreria.aplicacion;

import java.util.Objects;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.tesoreria.dominio.CajaEnConsulta;
import kamayuk.rentas.tesoreria.dominio.CajaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El catalogo de ventanillas de la municipalidad (#618, RF-080).
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>Cinco pantallas de Tesoreria piden el <b>codigo de la caja</b> antes de poder pedir nada:
 * {@code caja_tributaria} y {@code caja_tasas} lo llevan en el cuerpo del cobro, {@code
 * cierre_caja} y {@code avance_recaudacion} lo necesitan para resolver el turno, y desde #548 el
 * listado de recibos lo ofrece como filtro. Ninguna lectura lo publicaba: {@link CajaRepository}
 * sabia resolver <b>una</b> caja de la que ya se supiera el codigo, y no enumerarlas. La interfaz
 * lo resolvia con una caja de texto, o sea pidiendole a quien atiende que se sepa de memoria un
 * dato que el sistema conoce.
 *
 * <p><b>No es la situacion de {@code GET /tesoreria/tasas}</b>, que #430 midio y decidio no
 * publicar. Alli el motivo era que nada en produccion escribe la tabla y sus cifras son D-02b: una
 * lectura publicada habria devuelto una lista vacia en toda instalacion real, que se lee como «esta
 * municipalidad no cobra tasas». Aqui {@code cargar-cajas.sh} es el paso 4 de la siembra desde
 * #460, no exige {@code es_demostracion}, y la tabla tiene filas en cuanto una municipalidad se
 * implanta.
 *
 * <h2>Por que es un caso de uso y no una llamada suelta al repositorio</h2>
 *
 * <p>Por lo mismo que {@link ConsultaDeRecibos} y {@link ConsultaDeConvenios}: sin transaccion no
 * hay {@code SET LOCAL}, y sin el la politica RLS de {@code caja} no devuelve vacio sino que
 * <b>revienta</b> —{@code current_setting('app.municipalidad_id')::bigint} sobre la cadena vacia no
 * se puede evaluar (#486)—. El {@code @Transactional(readOnly = true)} de aqui es lo que garantiza
 * el contexto de tenant, y una regla de ArchUnit impide la salida corta de que el controlador
 * sostenga el repositorio.
 *
 * <h2>Sin criterio, y a proposito</h2>
 *
 * <p>Una municipalidad tiene cuatro ventanillas, no cuarenta mil: un filtro aqui seria una promesa
 * mas que mantener sin nada que acotar. La paginacion se conserva porque es el dialecto de las 134
 * y porque es lo unico que garantiza que <b>siempre</b> haya un {@code ORDER BY}.
 */
@Service
public class ConsultaDeCajas {

    private final CajaRepository cajas;

    public ConsultaDeCajas(CajaRepository cajas) {
        this.cajas = cajas;
    }

    /**
     * La pagina del catalogo, con el area de cada ventanilla ya resuelta.
     *
     * <p>Una municipalidad sin ninguna caja cargada devuelve una <b>pagina vacia</b> con {@code
     * totalElementos = 0}, nunca un 404: una instalacion recien implantada y todavia sin
     * ventanillas no es un error, es el estado por el que pasan todas antes del paso 4 de la
     * siembra.
     */
    @Transactional(readOnly = true)
    public Pagina<CajaEnConsulta> listar(Paginacion paginacion) {
        Objects.requireNonNull(paginacion, "Sin paginacion no hay orden garantizado");
        return cajas.listar(paginacion);
    }
}
