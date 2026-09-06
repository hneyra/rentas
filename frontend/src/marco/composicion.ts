import type {
  AccesoDelSistema,
  ModuloDelSistema,
  PermisosDeLaSesion,
} from '../datos/lecturas.ts';
import { CATALOGO_POR_CODIGO, type Modulo } from './arbol.ts';

/**
 * **De donde sale el arbol que se dibuja** (I-3, AC1 y AC2).
 *
 * Es una funcion pura: entran las tres lecturas de seguridad, sale la lista de modulos que el
 * panel, el lanzador y la paleta ofrecen. Sin React, sin `fetch` y sin reloj, porque lo que
 * decide —que modulos hay y cuales se esconden— es justo lo que hay que poder probar caso a
 * caso sin montar nada.
 *
 * <h2>Es un EMPALME, y cada lado aporta lo que solo el tiene</h2>
 *
 * <table>
 *   <tr><td>`GET /seguridad/modulos`</td><td>que modulos hay, su codigo, su nombre y su orden</td></tr>
 *   <tr><td>`marco/arbol.ts`</td><td>el icono, la nota y los cuatro destinos de cada uno</td></tr>
 *   <tr><td>`/seguridad/accesos` + `/sesion/permisos`</td><td>cuales de ellos puede abrir esta cuenta</td></tr>
 * </table>
 *
 * Ninguno de los tres sobra y ninguno se puede deducir de otro. El backend **no publica ningun
 * icono y ningun submodulo** —medido: `ModuloResource` son cinco campos escalares, el esquema
 * no tiene `padre_id` y ninguna de las 181 operaciones publica una jerarquia—, asi que pintar
 * «solo lo que llega» daria diez filas sin nada debajo. Y el artboard no sabe que modulos tiene
 * la instalacion ni que puede hacer quien entro.
 *
 * <h2>Los cinco motivos por los que un modulo NO sale, en este orden</h2>
 *
 * <ol>
 *   <li><b>El backend no lo publica.</b> Es el AC1: si `GET /seguridad/modulos` deja de traer
 *       «Coactiva», Coactiva desaparece del panel aunque siga escrita en el catalogo. Este
 *       archivo no tiene ninguna lista de diez modulos: recorre lo que le dan.</li>
 *   <li><b>Es de otro sistema</b> (`CATASTRO`, `TESORERIA`). Ver `DE_OTRO_SISTEMA`.</li>
 *   <li><b>Viene marcado inactivo.</b> `activo` es un campo del contrato y la consulta no lo
 *       filtra —`SELECT … FROM modulo_sistema`, sin `WHERE`—, o sea que llega y decidir es de
 *       aqui. Un modulo dado de baja que siguiera en el panel abriria pantallas de algo que la
 *       municipalidad apago.</li>
 *   <li><b>El catalogo no lo conoce.</b> Un modulo cuyo `codigo` no esta en `arbol.ts` no se
 *       puede dibujar —no hay icono ni destinos que dibujarle— y **se devuelve nombrado** en
 *       `sinCatalogo`, para que quien mire la pantalla vea que falta algo en vez de no verlo.
 *       Tragarselo en silencio seria perder un modulo nuevo sin que nada lo dijera.</li>
 *   <li><b>La cuenta no puede abrir ninguna de sus opciones.</b> Es el AC2, y es lo unico que
 *       depende de quien entro.</li>
 * </ol>
 *
 * <h2>El AC2 se aplica al MODULO, y decir por que importa mas que hacerlo</h2>
 *
 * La granularidad que el backend permite es el modulo, y ni una mas. Los cuarenta destinos del
 * arbol —`fis-actas`, `coa-exp`, `tra-veh`— son agrupaciones de diseno del artboard y **no
 * tienen ningun contrapartida entre los 134 accesos**: no hay una operacion que diga que
 * `fis-actas` se abre con `fisc_predial`, y no la hay porque `fis-actas` no existe fuera de
 * este repositorio. Escribir aqui ese mapa seria inventar cuarenta decisiones de autorizacion
 * y ponerlas en la interfaz, que es el sitio donde una decision de autorizacion no vale nada:
 * quien se salte el menu llega igual, y el backend contestara lo que tenga que contestar.
 *
 * Lo que si es real y esta publicado es `acceso.moduloId`, la clave foranea `acceso_modulo_fk`.
 * Asi que la regla es: **un modulo se ofrece si la cuenta tiene `lectura` sobre al menos una de
 * sus opciones**. Se pide `lectura` y no «cualquier privilegio» porque abrir una pantalla es
 * leerla —una cuenta con solo `impresion` sobre `papeletas` no puede abrir papeletas, recibiria
 * un 403—, y ofrecerle la rama seria mandarla a una puerta cerrada.
 */

/** Los modulos que el backend publica y este sistema NO sirve, con su motivo. */
export const DE_OTRO_SISTEMA: ReadonlyMap<string, string> = new Map([
  // No es una decision de diseno ni una omision del artboard: es el reparto. Sus pantallas y
  // sus tablas viven en otro repositorio, con otro despliegue y otra base, y este marco no
  // tiene ninguna a la que abrirlas. Que el backend de `rentas` siga publicando los doce del
  // catalogo es correcto —el catalogo de seguridad es del cluster, que los cuatro sistemas
  // comparten— y por eso la resta se hace aqui y no alli.
  ['CATASTRO', 'es de ../catastro (ADR-0029)'],
  ['TESORERIA', 'es de ../caja (ADR-0029)'],
]);

/** El acceso que `PUT /seguridad/sesion/ejercicio` exige, y con que privilegio. */
export const ACCESO_DEL_EJERCICIO = 'cambiar_anio';
/** `Privilegio.ESPECIAL`, tal como la matriz de permisos lo nombra. */
export const PRIVILEGIO_ESPECIAL = 'especial';
/** `Privilegio.LECTURA`. Lo que hace falta para ABRIR una pantalla. */
export const PRIVILEGIO_LECTURA = 'lectura';

/** Lo que se sabe del arbol despues de componerlo. */
export interface ArbolCompuesto {
  /** Los modulos que se ofrecen, en el orden en que el backend los publica. */
  readonly modulos: readonly Modulo[];
  /**
   * Los codigos que el backend publica, no son de otro sistema, y el catalogo no sabe dibujar.
   *
   * Vacio hoy. Deja de estarlo el dia que `seguridad` de de alta un modulo, y entonces la
   * pantalla lo dice en vez de que el modulo simplemente no aparezca.
   */
  readonly sinCatalogo: readonly string[];
  /** Los que se ofrecerian si la cuenta pudiera, y no puede. Es lo que el AC2 esconde. */
  readonly sinPermiso: readonly string[];
}

/**
 * Los codigos de acceso sobre los que la cuenta puede leer.
 *
 * **El `Array.isArray` no es defensivo por costumbre, y se gano midiendo.** `PermisosDeLaSesion`
 * es un tipo, y un tipo describe lo que se espera de un JSON que llega por la red: no lo
 * garantiza. Con un valor que no sea una lista —el contrato declara esta operacion como
 * `"objeto"` y nada mas, asi que el generador no puede comprobarle la forma— un `.includes`
 * lanza `TypeError` **dentro de un `useMemo`**, y eso no deja una pantalla con un aviso: deja la
 * aplicacion entera en blanco, sin marco y sin mensaje. Saltarse la llave que no se entiende
 * deja el sistema en negacion por omision, que es lo que ADR-0013 pide.
 */
function loQuePuedeLeer(permisos: PermisosDeLaSesion): ReadonlySet<string> {
  return new Set(
    Object.entries(permisos)
      .filter(
        ([, privilegios]) =>
          Array.isArray(privilegios) && privilegios.includes(PRIVILEGIO_LECTURA),
      )
      .map(([codigo]) => codigo),
  );
}

/**
 * Compone el arbol que se dibuja. Ver el javadoc del archivo.
 *
 * @param modulos lo que contesta `GET /seguridad/modulos`
 * @param accesos lo que contesta `GET /seguridad/accesos`, por su `moduloId`
 * @param permisos la matriz de `GET /seguridad/sesion/permisos`
 */
export function componerArbol(
  modulos: readonly ModuloDelSistema[],
  accesos: readonly AccesoDelSistema[],
  permisos: PermisosDeLaSesion,
): ArbolCompuesto {
  const legibles = loQuePuedeLeer(permisos);
  const sinCatalogo: string[] = [];
  const sinPermiso: string[] = [];
  const ofrecidos: Modulo[] = [];

  for (const modulo of modulos) {
    if (DE_OTRO_SISTEMA.has(modulo.codigo) || !modulo.activo) {
      continue;
    }

    const delCatalogo = CATALOGO_POR_CODIGO.get(modulo.codigo);
    if (delCatalogo === undefined) {
      sinCatalogo.push(modulo.codigo);
      continue;
    }

    const puedeAbrirAlgo = accesos.some(
      (acceso) => acceso.moduloId === modulo.id && legibles.has(acceso.codigo),
    );
    if (!puedeAbrirAlgo) {
      sinPermiso.push(modulo.codigo);
      continue;
    }

    ofrecidos.push({
      ...delCatalogo,
      // El rotulo es el del BACKEND y no el del artboard. Hoy son el mismo texto en los diez
      // —medido, byte a byte—, y esa coincidencia es justo lo que hace que la decision no se
      // note: el dia que la municipalidad renombre un modulo, el panel dira el nombre nuevo sin
      // que nadie toque este repositorio, que es la mitad util de haber conectado el arbol.
      rotulo: modulo.nombre,
    });
  }

  return { modulos: ofrecidos, sinCatalogo, sinPermiso };
}

/**
 * Si la cuenta puede ejecutar el acto de cambiar el ejercicio (AC5).
 *
 * `PUT /seguridad/sesion/ejercicio` declara `@RequiereAcceso(acceso = "cambiar_anio", privilegio
 * = Privilegio.ESPECIAL)`. **Se comprueba `especial` y no «tiene la llave»**: la matriz publica
 * la llave `cambiar_anio` en cuanto la cuenta tiene CUALQUIER privilegio sobre ella —ADR-0013:
 * «solo las opciones sobre las que tiene algun privilegio»—, asi que una cuenta con `lectura`
 * sobre `cambiar_anio` y sin `especial` aparece en el objeto igual que una que puede. Mirar si
 * la llave existe ofreceria el mando a quien recibira un 403.
 */
export function puedeCambiarElEjercicio(permisos: PermisosDeLaSesion): boolean {
  const suyos = permisos[ACCESO_DEL_EJERCICIO];
  // Ver `loQuePuedeLeer`: esto viene de la red y el contrato solo promete «objeto».
  return Array.isArray(suyos) && suyos.includes(PRIVILEGIO_ESPECIAL);
}

/** Los destinos que el arbol compuesto ofrece. Es contra esto que se valida un hash. */
export function destinosOfrecidos(modulos: readonly Modulo[]): ReadonlySet<string> {
  return new Set(modulos.flatMap((modulo) => modulo.submodulos.map((hoja) => hoja.clave)));
}
