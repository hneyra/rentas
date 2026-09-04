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
                    // GOB-05 §6.1 — el mas caro de los siete. `rentas` -> `catastro`.
                    //
                    // El cruce del padron de predios con las declaraciones juradas de un ejercicio,
                    // PAGINADO y contando lo filtrado. Componerlo en memoria ya se probo y fallo:
                    // #631 dejo la conciliacion contestando «722 paginas, 14 422 elementos» y cero
                    // filas en todas. La salida es una proyeccion local en `rentas`, alimentada por
                    // evento (ADR-0029 §Consecuencias).
                    //
                    // Le toca a: rentas (con catastro publicando el evento).
                    new CruceConsentido("DeteccionRepositoryJdbc", "predio", "PENDIENTE-CRUCE-01"),
                    new CruceConsentido("DeteccionRepositoryJdbc", "sector", "PENDIENTE-CRUCE-01"),
                    new CruceConsentido(
                            "DeteccionRepositoryJdbc", "ficha_catastral", "PENDIENTE-CRUCE-01"),

                    // ---------------------------------------------------------------------------
                    // GOB-05 §6.3 — la mitad numerica de ADR-0015. `rentas` -> `catastro`.
                    //
                    // El RECUENTO de la conciliacion (#564), sobre la MISMA poblacion que la grilla
                    // de §6.1 —lo dice su propio javadoc, letra por letra—. No es un cruce
                    // distinto:
                    // es el mismo padron proyectado, contado en vez de paginado, y por eso va con
                    // el
                    // mismo issue. Resolverlos con dos proyecciones distintas dejaria a la grilla y
                    // a su recuento diciendo cifras distintas del mismo dia, que es exactamente el
                    // defecto que #564 midio.
                    //
                    // Le toca a: rentas.
                    new CruceConsentido(
                            "ConciliacionRepositoryJdbc", "ficha_catastral", "PENDIENTE-CRUCE-01"),
                    new CruceConsentido(
                            "ConciliacionRepositoryJdbc", "predio", "PENDIENTE-CRUCE-01"),

                    // ---------------------------------------------------------------------------
                    // GOB-05 §6.4 y §6.5 — CERRADOS EN P5B.
                    //
                    // `PENDIENTE-CRUCE-02` (valores unitarios y depreciacion, catastro ->
                    // normativa)
                    // y `PENDIENTE-CRUCE-03` (valores referenciales, rentas -> normativa) ya no
                    // cruzan nada: las tres tablas se fueron a `normativa` con `V2` y lo que
                    // `ValuacionRepositoryJdbc` y `ValorReferencialRepositoryJdbc` leen ahora es la
                    // CACHE LOCAL —`normativa_valor_unitario`, `normativa_depreciacion`,
                    // `normativa_valor_referencial` (`V3`)—, que es de este sistema.
                    //
                    // Se retiran de la lista, y esa retirada no es un tramite:
                    // `ningunCruceConsentido
                    // Sobra` vuelve a escanear SIN la lista y exige que cada entrada siga eximiendo
                    // un cruce de verdad, asi que dejarlas puestas habria puesto la prueba en rojo.
                    // Es la lista de trabajo pendiente encogiendose por haberse hecho el trabajo.
                    // ---------------------------------------------------------------------------

                    // ---------------------------------------------------------------------------
                    // GOB-05 §6.6 — `rentas` -> `catastro`. El puerto YA EXISTE.
                    //
                    // «¿A quien se le cobra el arbitrio de este predio en esta fecha?» Es una
                    // lectura de UNA fila por un identificador, sin JOIN y sin paginacion:
                    // exactamente lo contrario de §6.1. `catastro` ya publica TitularesDelPredio
                    // (#366) y ADR-0027 §1 mete `titulares[]` dentro del hecho sellado, con la
                    // condicion y el porcentaje resueltos A LA FECHA DE CORTE.
                    //
                    // CUIDADO con el criterio de desempate al cerrarlo, que es propio de esta
                    // consulta y no del puerto: el titular de mayor porcentaje, y a igualdad el de
                    // menor id. Si la llamada devuelve la lista de cuotas, el desempate se lleva
                    // tal
                    // cual a `rentas`; si lo resuelve `catastro`, esa regla de arbitrios se muda a
                    // catastro, que es lo que ADR-0024 §2 evita.
                    //
                    // Le toca a: rentas.
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
