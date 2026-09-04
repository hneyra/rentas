package kamayuk.rentas.verificaciones;

import java.util.List;
import kamayuk.comun.verificaciones.ConfiguracionDeLasVerificaciones.CruceConsentido;

/**
 * Los cruces de SQL que hoy atraviesan una frontera de sistema y todavia no se pueden cerrar.
 *
 * <p><b>Esta lista es la lista de trabajo pendiente del corte.</b> No es una lista de excepciones
 * toleradas: es lo que queda por hacer, escrito donde se pone rojo cuando alguien lo hace. En la
 * etapa P5E tiene que llegar a cero, y que llegue a cero es el criterio de que la separacion
 * termino.
 *
 * <p>Cada entrada nombra su issue. Una excepcion sin issue no se acepta —{@code CruceConsentido} la
 * rechaza al construirla— porque una entrada sin dueño no es una excepcion sino un olvido con
 * permiso, y en P5E no habria a quien preguntarle.
 *
 * <p>Y ninguna puede sobrar: {@code FronteraDeSistemaTest} comprueba que cada entrada sigue
 * eximiendo un cruce de verdad. Una que ya no aplique se queda dentro para siempre y la lista deja
 * de decir cuanto falta.
 *
 * <h2>Los identificadores</h2>
 *
 * <p>Los repositorios nuevos no tienen issues abiertos todavia y {@code gh} no pudo crearlos desde
 * esta sesion, asi que se usan identificadores {@code PENDIENTE-CRUCE-nn} <b>que se distinguen a
 * simple vista de un numero de GitHub</b>: inventar un {@code #642} que parezca real seria peor que
 * no poner nada. Cada uno dice ademas a que repositorio le toca. Al abrirse el issue de verdad, se
 * sustituye el identificador y esta prueba sigue diciendo lo mismo.
 */
final class CrucesConsentidosDelSgtm {

    private CrucesConsentidosDelSgtm() {}

    static final List<CruceConsentido> LISTA =
            List.of(
                    // ---------------------------------------------------------------------------
                    // PENDIENTE-CRUCE-01 — CERRADO EN P5C.
                    //
                    // `DeteccionRepositoryJdbc` (los omisos) y `ConciliacionRepositoryJdbc` (su
                    // recuento) leian `predio`, `sector` y `ficha_catastral`, tres tablas de
                    // `catastro`, en la MISMA consulta que pagina y cuenta lo filtrado. Iban
                    // juntas a proposito: es el mismo padron, paginado en un caso y contado en el
                    // otro, y dos proyecciones distintas darian dos cifras del mismo dia (#564).
                    //
                    // Hoy las dos leen `predio_ref` y `ficha_ref`, la proyeccion local que crea
                    // `V4` — y `sector` ya no se lee de ninguna forma, porque la proyeccion lleva
                    // el CODIGO del sector, que es lo que los filtros teclean.
                    //
                    // Retirarlas de esta lista no es un tramite: `ningunCruceConsentidoSobra`
                    // vuelve a escanear SIN la lista y exige que cada entrada siga eximiendo un
                    // cruce de verdad, asi que dejarlas puestas la habria puesto en rojo. Es la
                    // lista de trabajo pendiente encogiendose por haberse hecho el trabajo.

                    // ---------------------------------------------------------------------------
                    // GOB-05 §6.6 — `rentas` -> `catastro`. Sigue abierto.
                    //
                    new CruceConsentido(
                            "TitularPrincipalRepositoryJdbc", "titularidad", "PENDIENTE-CRUCE-04"),

                    // ---------------------------------------------------------------------------
                    // GOB-05 §6.7 — `rentas` -> `catastro`. El mas barato de los siete.
                    //
                    // SOLO cuando el usuario filtra por codigo predial: un JOIN para traducir un
                    // codigo a un identificador. Se resuelve con una llamada previa PORQUE el
                    // filtro
                    // devuelve como mucho un predio; lo que no se puede hacer es lo mismo en §6.1,
                    // donde el JOIN es sobre el padron entero.
                    //
                    // Le toca a: rentas.
                    new CruceConsentido(
                            "CuotaDeArbitrioRepositoryJdbc", "predio", "PENDIENTE-CRUCE-05"),

                    // ---------------------------------------------------------------------------
                    // GOB-05 §6.8 — `caja` -> `rentas`. El que D-17 tiene abierto.
                    //
                    // Filtrar recibos por contribuyente traduciendo el codigo del padron al
                    // identificador. Mismo caso que §6.7 y una decision de negocio encima: el dia
                    // que la caja cobre un puesto de mercado, el pagador puede no estar en
                    // `contribuyente`. Los dos caminos que D-17 plantea —registro compartido, o
                    // pagador propio de `caja` que solo enlaza cuando lo hay— cambian esta consulta
                    // de forma distinta, asi que hasta que se decida va por puerto HTTP.
                    //
                    // Le toca a: caja.
                    new CruceConsentido(
                            "ReciboRepositoryJdbc", "contribuyente", "PENDIENTE-CRUCE-06"));
}
