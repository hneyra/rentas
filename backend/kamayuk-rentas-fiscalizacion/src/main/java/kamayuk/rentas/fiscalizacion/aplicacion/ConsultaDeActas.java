package kamayuk.rentas.fiscalizacion.aplicacion;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaConLoDeclarado;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
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
 * <h2>Y devuelve el contraste entero, no media tabla (#191)</h2>
 *
 * <p>Hasta #191 esta lectura devolvía el acta desnuda, que guarda lo <b>hallado</b> y no lo
 * declarado: la tabla «Declarado contra verificado» de la pantalla salía con dos de sus cinco
 * columnas en raya en todas sus filas. El lado declarado lo dice la versión de ficha que el acta
 * referencia en {@code fichaId}, y se resuelve aquí —{@link ActaConLoDeclarado}— en <b>una</b>
 * lectura por página. No se guarda en la fila del acta: sería una segunda verdad sobre lo mismo,
 * que es lo que #397 y #481 se negaron a introducir.
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
    public Pagina<ActaConLoDeclarado> buscar(Paginacion paginacion) {
        Pagina<ActaFiscalizacion> pagina = actas.consultar(paginacion);
        Map<Long, ActaConLoDeclarado.LoDeclarado> porFicha =
                actas.loDeclaradoPorFicha(fichasDe(pagina));
        return pagina.mapear(
                acta ->
                        ActaConLoDeclarado.de(
                                acta,
                                acta.fichaId() == null ? null : porFicha.get(acta.fichaId())));
    }

    /**
     * Las versiones de ficha que esta pagina referencia, <b>una sola vez cada una</b>.
     *
     * <p>Es una lectura por pagina y no una por fila, el mismo reparto con que {@code
     * DeteccionDeOmisos} resuelve los titulares de la suya. Resolverlo fila a fila seria una
     * consulta por acta, y por el puerto HTTP de catastro seria una peticion por acta.
     *
     * <p>Un acta vehicular no referencia ninguna, y un acta predial de un predio sin ficha
     * registrada a la fecha de la visita tampoco: esas no aportan ninguna llave y salen con su lado
     * declarado nulo.
     */
    private static Set<Long> fichasDe(Pagina<ActaFiscalizacion> pagina) {
        Set<Long> fichas = new HashSet<>();
        for (ActaFiscalizacion acta : pagina.contenido()) {
            if (acta.fichaId() != null) {
                fichas.add(acta.fichaId());
            }
        }
        return fichas;
    }
}
