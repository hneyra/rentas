package kamayuk.rentas.fiscalizacion.aplicacion;

import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeActas;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La grilla de actas de inspección (RF-051, RF-052, #599).
 *
 * <p><b>Es la lectura que faltaba, y no se pudo publicar antes.</b> Un acta se registraba y no se
 * podía volver a leer: el único sitio donde asomaba era {@code MuestraResource.visitado}, que dice
 * <b>si</b> un predio de la muestra ya tiene acta y nada más. #546 midió que publicarla entonces no
 * habría desbloqueado nada —el cuerpo del {@code POST} tenía nueve campos contra los veintitrés que
 * la pantalla del manual dibuja, así que el listado habría publicado la misma foto incompleta—, y
 * que lo que faltaba era <b>dónde guardar</b> el uso hallado. Con {@code
 * acta_fiscalizacion.uso_hallado} (V76) el acta ya sostiene los dos hallazgos que la fiscalización
 * predial persigue, y entonces sí hay algo que leer.
 *
 * <p>{@code @Transactional(readOnly = true)}: sin transacción no hay contexto de tenant fijado, y
 * sin él la política RLS no devuelve una página vacía sino que <b>revienta</b> —{@code invalid
 * input syntax for type bigint: ""}—. Es el defecto de clase de #486, repetido en seis issues.
 */
@Service
public class ConsultaDeActas {

    private final ActaFiscalizacionRepository actas;

    public ConsultaDeActas(ActaFiscalizacionRepository actas) {
        this.actas = actas;
    }

    @Transactional(readOnly = true)
    public Pagina<ActaFiscalizacion> buscar(CriterioDeActas criterio, Paginacion paginacion) {
        return actas.consultar(criterio, paginacion);
    }
}
