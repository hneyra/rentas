package kamayuk.rentas.fiscalizacion.aplicacion;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaConLoDeclarado;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ComparacionHalladoDeclarado;
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
 * columnas en raya en todas sus filas. El lado declarado lo dice la declaración jurada del
 * ejercicio del programa —no la ficha inscrita el día de la visita (#344)—, y se resuelve aquí
 * —{@link ActaConLoDeclarado}— en <b>una</b> lectura por página. No se guarda en la fila del acta:
 * sería una segunda verdad sobre lo mismo, que es lo que #397 y #481 se negaron a introducir.
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
        Map<Long, ComparacionHalladoDeclarado.LoDeclarado> porActa =
                actas.loDeclaradoDeLasActas(idsDe(pagina));
        return pagina.mapear(
                acta ->
                        ActaConLoDeclarado.de(
                                acta, acta.id() == null ? null : porActa.get(acta.id())));
    }

    /**
     * Las actas de esta pagina: el lado declarado se resuelve para todas en <b>una</b> lectura, el
     * mismo reparto con que {@code DeteccionDeOmisos} resuelve los titulares de la suya. Fila a
     * fila seria una consulta por acta.
     */
    private static Set<Long> idsDe(Pagina<ActaFiscalizacion> pagina) {
        Set<Long> ids = new HashSet<>();
        for (ActaFiscalizacion acta : pagina.contenido()) {
            if (acta.id() != null) {
                ids.add(acta.id());
            }
        }
        return ids;
    }
}
