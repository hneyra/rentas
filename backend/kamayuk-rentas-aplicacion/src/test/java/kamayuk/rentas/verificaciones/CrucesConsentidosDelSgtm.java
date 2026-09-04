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
                    // PENDIENTE-CRUCE-01, -04 y -05 — CERRADOS EN P5C.
                    //
                    // `V6` retiro de esta base las quince tablas de `catastro`, asi que ninguna de
                    // las cuatro clases que las leian puede seguir haciendolo. Cada una se cerro
                    // por el camino que su propia nota anticipaba:
                    //
                    //  - `DeteccionRepositoryJdbc` y `ConciliacionRepositoryJdbc` (-01) leen
                    //    `predio_ref` y `ficha_ref`, la proyeccion local de `V4`. Iban juntas a
                    //    proposito —es el mismo padron, paginado en un caso y contado en el otro
                    //    (#564)— y por eso se cierran juntas.
                    //  - `TitularPrincipalRepositoryJdbc` (-04) desaparecio: lo sustituye
                    //    `TitularPrincipalPorElPuerto`, que pregunta por `TitularesDelPredio`.
                    //    Su nota avisaba del desempate, y ese matiz quedo escrito en la clase
                    //    nueva: el puerto no publica el id de la fila y el desempate entre dos
                    //    copropietarios EMPATADOS cambia.
                    //  - `CuotaDeArbitrioRepositoryJdbc` (-05) traduce el codigo predial contra
                    //    `predio_ref`. Su nota decia «puerto HTTP: el filtro devuelve como mucho
                    //    un predio», y la proyeccion lo resuelve sin salir de la base, que es
                    //    mejor: el filtro entra en el MISMO `WHERE` que el conteo.
                    //
                    // Retirarlas no es un tramite: `ningunCruceConsentidoSobra` vuelve a escanear
                    // SIN la lista y exige que cada entrada siga eximiendo un cruce de verdad, asi
                    // que dejar cualquiera de las siete la habria puesto en rojo.

                    // ---------------------------------------------------------------------------
                    // GOB-05 §6.8 — `caja` -> `rentas`. El unico que queda, y lo tiene abierto
                    // D-17.
                    //
                    new CruceConsentido(
                            "ReciboRepositoryJdbc", "contribuyente", "PENDIENTE-CRUCE-06"));
}
