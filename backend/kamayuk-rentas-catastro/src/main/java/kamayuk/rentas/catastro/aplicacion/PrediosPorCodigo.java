package kamayuk.rentas.catastro.aplicacion;

import java.util.Optional;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Traduce un codigo de referencia catastral al identificador interno del predio.
 *
 * <h2>Por que es un servicio y no una llamada al repositorio</h2>
 *
 * <p>Por la transaccion. {@link CatastroRepository} es un repositorio: consultado fuera de una, la
 * consulta sale <b>sin</b> {@code SET LOCAL} y la politica RLS no devuelve cero filas
 * —<b>falla</b>, con «unrecognized configuration parameter»—. Es el defecto que {@code
 * ConsultaDeVias} cerro en su dia. Un metodo privado del importador tampoco valdria: la llamada a
 * si mismo no pasa por el proxy de Spring y la anotacion no haria nada.
 *
 * <p>Los controladores no lo usan —resuelven el codigo dentro de su propio caso de uso, que ya trae
 * su transaccion—; existe para los procesos de carga, que no tienen ninguna abierta.
 */
@Service
public class PrediosPorCodigo {

    private final CatastroRepository catastro;

    public PrediosPorCodigo(CatastroRepository catastro) {
        this.catastro = catastro;
    }

    /** El identificador del predio con ese codigo, si esta inscrito en esta municipalidad. */
    @Transactional(readOnly = true)
    public Optional<Long> identificadorDe(CodigoReferenciaCatastral codigo) {
        return catastro.predioPorCodigo(codigo).map(predio -> predio.id());
    }
}
