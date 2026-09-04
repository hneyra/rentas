package kamayuk.rentas.tesoreria.infraestructura.web;

import kamayuk.rentas.tesoreria.dominio.CajaEnConsulta;
import org.jspecify.annotations.Nullable;

/**
 * Una ventanilla del catalogo, tal como sale por HTTP (#618, RF-080).
 *
 * <p>Es lo que llena el desplegable «Caja» de las cinco pantallas de Tesoreria que hoy piden el
 * codigo tecleado. El prototipo dibujaba «Todas · C-1 · C-2 · C-3 · C-4», y esas cuatro ventanillas
 * son del artboard: aqui salen las que la municipalidad tiene cargadas, ni una mas.
 *
 * @param codigo el codigo de la ventanilla, que es con lo que se la nombra en toda la API
 * @param nombre su rotulo
 * @param areaCodigo el area a la que se imputa lo que recauda; nulo si no cuelga de ninguna
 * @param areaNombre su rotulo; nulo por lo mismo
 * @param activa si sigue abierta. Ver {@link CajaEnConsulta}: las dadas de baja salen tambien,
 *     porque el filtro del listado de recibos tiene que poder nombrarlas
 */
public record CajaEnListaResource(
        String codigo,
        String nombre,
        @Nullable String areaCodigo,
        @Nullable String areaNombre,
        boolean activa) {

    public static CajaEnListaResource de(CajaEnConsulta caja) {
        return new CajaEnListaResource(
                caja.codigo(), caja.nombre(), caja.areaCodigo(), caja.areaNombre(), caja.activa());
    }
}
