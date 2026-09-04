package kamayuk.rentas.tesoreria.aplicacion;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.tesoreria.dominio.CriterioDeRecibos;
import kamayuk.rentas.tesoreria.dominio.ReciboEnConsulta;
import kamayuk.rentas.tesoreria.dominio.ReciboRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El listado de recibos emitidos (#548, RF-082): la grilla «Recibos localizados» de {@code
 * duplicado_recibo}.
 *
 * <h2>El hueco que cierra</h2>
 *
 * <p>Hasta #548 el unico camino a un recibo era {@code GET /tesoreria/recibos/{nro}/duplicado}, o
 * sea <b>saber el numero impreso</b>. Quien pierde el papel —que es la persona que se acerca a
 * ventanilla a pedir un duplicado— no tenia forma de encontrarlo, y la pantalla del manual dibuja
 * una grilla de busqueda por contribuyente, fecha y caja que nadie podia llenar.
 *
 * <h2>Por que es un caso de uso y no una llamada suelta al repositorio</h2>
 *
 * <p>Por lo mismo que {@link ConsultaDeConvenios}: sin transaccion no hay {@code SET LOCAL}, y sin
 * el la politica RLS de {@code recibo} no devuelve vacio sino que <b>revienta</b> —{@code
 * current_setting('app.municipalidad_id')::bigint} sobre la cadena vacia no se puede evaluar
 * (#486)—. El {@code @Transactional(readOnly = true)} de aqui es lo que garantiza el contexto de
 * tenant.
 *
 * <h2>Los nombres se resuelven en la MISMA transaccion</h2>
 *
 * <p>Una consulta al padron por pagina, no una por fila ({@link
 * DirectorioDeContribuyentes#porIds}). Y dentro de la misma transaccion que la pagina: en dos,
 * entre una y otra cabe un alta de contribuyente y la grilla saldria con una fila sin nombre que no
 * lo esta por ningun motivo real.
 *
 * <h2>Ninguna cifra se recalcula</h2>
 *
 * <p>El importe de cada fila es el que el recibo congelo, con la fecha a la que estaba actualizado
 * (regla 9). Este caso de uso <b>no tiene reloj</b> y no lo necesita: aqui no hay nada que dependa
 * de que dia es hoy.
 */
@Service
public class ConsultaDeRecibos {

    private final ReciboRepository recibos;
    private final DirectorioDeContribuyentes padron;

    public ConsultaDeRecibos(ReciboRepository recibos, DirectorioDeContribuyentes padron) {
        this.recibos = recibos;
        this.padron = padron;
    }

    /**
     * La pagina de recibos que pide el criterio, con el nombre de cada contribuyente resuelto.
     *
     * <p>Un criterio sin resultados devuelve una pagina vacia con {@code totalElementos = 0}, nunca
     * un 404: un contribuyente sin recibos no es un error, es una busqueda sin resultados —el mismo
     * criterio de {@code consulta_deuda} y de {@code valores_busqueda}—.
     */
    @Transactional(readOnly = true)
    public Pagina<FilaDeRecibo> listar(CriterioDeRecibos criterio, Paginacion paginacion) {
        Objects.requireNonNull(criterio, "La consulta necesita su criterio");
        Objects.requireNonNull(paginacion, "Sin paginacion no hay orden garantizado");

        Pagina<ReciboEnConsulta> pagina = recibos.buscar(criterio, paginacion);
        if (pagina.estaVacia()) {
            return pagina.mapear(fila -> new FilaDeRecibo(fila, null));
        }

        Set<Long> titulares = new LinkedHashSet<>();
        for (ReciboEnConsulta fila : pagina.contenido()) {
            titulares.add(fila.contribuyenteId());
        }
        Map<Long, ResumenDeContribuyente> nombres = padron.porIds(titulares);

        return pagina.mapear(fila -> new FilaDeRecibo(fila, nombres.get(fila.contribuyenteId())));
    }

    /**
     * Una fila de la grilla: el recibo y a quien se le cobro.
     *
     * <p>{@code contribuyente} nulo significa que el padron no devolvio ese identificador. El
     * recibo sale igual en la grilla, sin nombre: es justo la fila que hay que revisar, y
     * esconderla no la arregla.
     */
    public record FilaDeRecibo(
            ReciboEnConsulta recibo, @Nullable ResumenDeContribuyente contribuyente) {}
}
