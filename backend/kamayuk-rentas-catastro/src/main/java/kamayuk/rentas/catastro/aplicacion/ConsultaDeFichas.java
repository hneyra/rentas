package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.FichaEncontrada;
import kamayuk.rentas.catastro.dominio.FiltroDeFichas;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.VersionDeLaFicha;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La consulta transversal de fichas y su historico (RF-006).
 *
 * <p>Es el unico sitio del contexto que le habla al padron, y lo hace por la API publica del
 * vecino. La division es deliberada: el repositorio consulta <b>solo tablas de catastro</b> y este
 * caso de uso pone los dos lados juntos. Con un {@code JOIN} a la tabla {@code contribuyente} desde
 * el SQL de catastro la consulta seria mas corta y el acoplamiento invisible para Spring Modulith,
 * que es la peor combinacion posible.
 *
 * <p><b>Toda consulta lleva fecha.</b> La grilla muestra la ficha y el titular vigentes a una
 * fecha, no «los ultimos»: si no, atender una reclamacion de 2027 en 2029 daria el titular de hoy,
 * y la notificacion se dirigiria a quien ya no era propietario (regla 9).
 */
@Service
public class ConsultaDeFichas {

    /**
     * Cuantos contribuyentes resuelve como mucho el filtro por nombre.
     *
     * <p>No es una cifra de negocio: es el tope de una lista {@code IN}. Un nombre muy comun —«Juan
     * Perez»— puede parecerse a cientos, y meterlos todos en la clausula convierte la consulta en
     * un recorrido. Doscientos cubren cualquier busqueda que una persona vaya a mirar.
     */
    private static final int TITULARES_MAXIMOS = 200;

    private final FichaCatastralRepository fichas;
    private final DirectorioDeContribuyentes padron;

    public ConsultaDeFichas(FichaCatastralRepository fichas, DirectorioDeContribuyentes padron) {
        this.fichas = fichas;
        this.padron = padron;
    }

    @Transactional(readOnly = true)
    public Pagina<FichaEncontrada> buscar(
            FiltroDeFichas filtro, LocalDate fecha, Paginacion paginacion) {

        Pagina<FichaEncontrada> pagina = sinResolver(filtro, fecha, paginacion);
        Map<Long, ResumenDeContribuyente> titulares = titularesDe(pagina);
        return pagina.mapear(
                fila -> {
                    ResumenDeContribuyente titular = titularDe(fila, titulares);
                    return fila.conTitular(titular == null ? null : titular.nombre());
                });
    }

    /**
     * El listado base de {@code consulta_resumen_predial}: los mismos predios, con el <b>codigo</b>
     * del titular ademas de su nombre (RF-046, #25).
     *
     * <p>Es la misma consulta que {@link #buscar} y por eso no tiene repositorio propio: la
     * pantalla del resumen predial busca predios exactamente igual —codigo catastral, contribuyente
     * y uso—, y lo unico que anade su grilla es la columna «Cod. Propietario». Resolverla desde el
     * controlador con una segunda llamada al padron habria costado dos consultas por pagina para
     * traer el codigo y el nombre del mismo contribuyente.
     *
     * <p><b>Las tres pestañas de esa pantalla no salen de aqui</b>, y conviene decir cuales y por
     * que: «Impuesto Predial» y «Valúo Predial / Arbitrios» no se sirven —el porque esta en el
     * javadoc de {@code ResumenPredialController}—, y «Movimientos del Predio» ya esta publicada
     * como el historico de la ficha ({@code ?historico=true}), que es {@link #historial}.
     */
    @Transactional(readOnly = true)
    public Pagina<PredioDelResumen> resumenPredial(
            FiltroDeFichas filtro, LocalDate fecha, Paginacion paginacion) {

        Pagina<FichaEncontrada> pagina = sinResolver(filtro, fecha, paginacion);
        Map<Long, ResumenDeContribuyente> titulares = titularesDe(pagina);
        return pagina.mapear(
                fila -> {
                    ResumenDeContribuyente titular = titularDe(fila, titulares);
                    return new PredioDelResumen(
                            fila.conTitular(titular == null ? null : titular.nombre()),
                            titular == null ? null : titular.codigo());
                });
    }

    /**
     * Una fila de «Predios encontrados»: la ficha vigente a la fecha y quien la tiene.
     *
     * @param codigoTitular nulo cuando el predio no tiene titular vigente a la fecha consultada, o
     *     cuando el padron no devuelve al que la titularidad apunta. El predio sale igual en la
     *     grilla: es justo el que hay que revisar
     */
    public record PredioDelResumen(FichaEncontrada ficha, @Nullable String codigoTitular) {}

    /** La pagina tal como la devuelve la base, con el titular todavia como identificador. */
    private Pagina<FichaEncontrada> sinResolver(
            FiltroDeFichas filtro, LocalDate fecha, Paginacion paginacion) {

        List<Long> titulares =
                filtro.porContribuyente()
                        .map(texto -> padron.buscar(texto, TITULARES_MAXIMOS))
                        .map(hallados -> hallados.stream().map(ResumenDeContribuyente::id).toList())
                        .orElse(List.of());

        return fichas.consultar(filtro, titulares, fecha, paginacion);
    }

    private static @Nullable ResumenDeContribuyente titularDe(
            FichaEncontrada fila, Map<Long, ResumenDeContribuyente> titulares) {
        return fila.titularId() == null ? null : titulares.get(fila.titularId());
    }

    /**
     * El historico de una ficha, de la version mas reciente a la mas antigua.
     *
     * <p>Cada fila trae su observacion, su autor y cuando se escribio. Es lo que hace util al
     * versionado: sin el motivo, el historico es una lista de areas distintas y nadie puede decir
     * si el cambio fue una fiscalizacion o un error de tecleo.
     */
    @Transactional(readOnly = true)
    public List<VersionDeLaFicha> historial(long predioId, TipoFicha tipo) {
        return fichas.versionesDe(predioId, tipo);
    }

    /**
     * Los titulares de toda la pagina, con <b>una</b> consulta al padron.
     *
     * <p>Un predio sin titular vigente no aparece en el mapa y sigue en la grilla, sin nombre: es
     * justo el caso que catastro tiene que revisar, y ocultarlo lo esconderia.
     */
    private Map<Long, ResumenDeContribuyente> titularesDe(Pagina<FichaEncontrada> pagina) {
        Set<Long> ids = new LinkedHashSet<>();
        for (FichaEncontrada fila : pagina.contenido()) {
            if (fila.titularId() != null) {
                ids.add(fila.titularId());
            }
        }
        return ids.isEmpty() ? Map.of() : padron.porIds(ids);
    }
}
