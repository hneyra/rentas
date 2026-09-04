package kamayuk.rentas.tesoreria.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Una ventanilla tal como la ve quien tiene que <b>elegirla</b> (#618).
 *
 * <p>No es {@link Caja}: aquella es la fila de la tabla —con su identificador y su serie— y esta es
 * lo que el catalogo publica. La serie no sale, y no por descuido: quien elige una caja en la
 * pantalla de cobro, en la de cierre o en la del duplicado no teclea ninguna serie, y publicarla
 * seria inventarle una columna a la pantalla (RNF-080). El identificador tampoco: la API entera
 * nombra a una caja por su {@code codigo} —el cuerpo del cobro, el del cierre y el filtro del
 * listado de recibos—, y publicar un numero interno invitaria a mandarlo donde no vale.
 *
 * <p><b>El area viaja legible, no como numero.</b> La tabla guarda {@code area_id}, que fuera del
 * servidor no lo puede leer nadie; lo que se publica es el codigo y el nombre del area. Los dos son
 * nulos cuando la caja no cuelga de ninguna, que es el caso de la caja tributaria general.
 *
 * <p><b>{@code activa} viaja, y las cajas dadas de baja tambien salen.</b> El issue supuso que
 * {@code caja} no tenia columna de estado, y la tiene desde {@code V3}. Se publica —en vez de
 * callarla o de filtrar por ella— porque las dos pantallas que consumen este catalogo necesitan
 * cosas distintas: la caja de cobro y el cierre solo pueden ofrecer las que siguen abiertas, y el
 * filtro «Caja» del listado de recibos tiene que poder nombrar una ventanilla <b>cerrada</b>
 * —porque sus recibos siguen existiendo (RNF-051, regla 4) y dejarla fuera los volveria
 * inencontrables sin decirlo—. Devolver solo las activas seria la clase de defecto que #431 y #427
 * midieron: una lista recortada en silencio se lee como la lista entera.
 *
 * @param codigo como la nombra la municipalidad, y con lo que se la pide en toda la API
 * @param nombre el rotulo, que es la «descripcion» de la pantalla
 * @param areaCodigo el codigo del area a la que se imputa lo que recauda; nulo si no cuelga de una
 * @param areaNombre su rotulo; nulo por lo mismo
 * @param activa falso si la ventanilla se dio de baja: sirve para buscar sus recibos, no para
 *     cobrar
 */
public record CajaEnConsulta(
        String codigo,
        String nombre,
        @Nullable String areaCodigo,
        @Nullable String areaNombre,
        boolean activa) {

    public CajaEnConsulta {
        Objects.requireNonNull(codigo, "La caja necesita su codigo");
        Objects.requireNonNull(nombre, "La caja necesita su nombre");
    }
}
