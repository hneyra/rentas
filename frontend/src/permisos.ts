import type { Catalogo, ModuloDelCatalogo } from '@kamayuk/shell';

import type {
  AccesoDelSistema,
  ModuloDelSistema,
  PermisosDeLaSesion,
} from './datos/lecturas.ts';

/**
 * **Lo que la cuenta no puede abrir, no se ofrece** (#105).
 *
 * <h2>Que se cruza, y por que son tres operaciones y no una</h2>
 *
 *   · `GET /seguridad/modulos` dice **que modulos existen**, en que orden, con que rotulo y si
 *     estan activos. Es del clúster: publica los doce, no los diez de este sistema.
 *   · `GET /seguridad/accesos` dice **que se puede abrir dentro de cada modulo** —134 accesos con
 *     su `moduloId`—.
 *   · `GET /seguridad/sesion/permisos` dice **que puede esta cuenta**, como una matriz
 *     `codigo → privilegios[]`.
 *
 * Ninguna de las tres basta sola: la primera no sabe de cuentas, la segunda no sabe de permisos, y
 * la tercera es una bolsa de codigos planos que no dice a que modulo pertenece cada uno.
 *
 * <h2>Por que la resta de los dos modulos ajenos se hace AQUI</h2>
 *
 * `CATASTRO` y `TESORERIA` los publica el backend de `rentas`, **y es correcto**: el catalogo de
 * seguridad es del clúster, que los cuatro sistemas comparten. Lo que este marco no tiene es una
 * sola pantalla que abrirles — viven en otro repositorio, con otro despliegue y otra base
 * (ADR-0029). Restarlos en el backend seria mentir sobre el catalogo; restarlos aqui es decir que
 * esta interfaz no los sirve.
 *
 * <h2>El rotulo es el del BACKEND, no el del artboard</h2>
 *
 * Hoy son el mismo texto en los diez —medido, byte a byte—, y esa coincidencia es justo lo que
 * hace que la decision no se note. El dia que la municipalidad renombre un modulo, el arbol dira
 * el nombre nuevo **sin que nadie toque este repositorio**, que es la mitad util de haber
 * conectado el arbol.
 */

/** Los modulos que el backend publica y este sistema NO sirve, con su motivo. */
export const DE_OTRO_SISTEMA: ReadonlyMap<string, string> = new Map([
  ['CATASTRO', 'es de ../catastro (ADR-0029)'],
  ['TESORERIA', 'es de ../caja (ADR-0029)'],
]);

/** `Privilegio.LECTURA`. Lo minimo para ABRIR una pantalla. */
export const PRIVILEGIO_LECTURA = 'lectura';

/** `Privilegio.ESPECIAL`. El que pide un acto con privilegio propio, como fijar el ejercicio (#391). */
export const PRIVILEGIO_ESPECIAL = 'especial';

/**
 * **La opcion con que se fija el ejercicio de trabajo, y el privilegio que pide** (#391).
 *
 * Es lo que declara `PUT /seguridad/sesion/ejercicio` en `SesionController`:
 * `@RequiereAcceso(acceso = "cambiar_anio", privilegio = Privilegio.ESPECIAL)`. El `nombre` es el
 * que `SembradorDelCatalogo` siembra desde `docs/10-negocio/catalogo-de-opciones.md`, y es con el
 * que la encuentra quien administra los perfiles: por eso lo dice la barra a la cuenta que no lo
 * tiene. Que los tres sigan siendo esos lo vigila
 * `verificaciones/las-opciones-que-leen-el-catalogo-son-las-del-backend.test.ts`, igual que a las
 * dos opciones del catalogo de #311.
 */
export const CAMBIAR_EL_EJERCICIO = {
  codigo: 'cambiar_anio',
  privilegio: PRIVILEGIO_ESPECIAL,
  nombre: 'Cambiar el año de trabajo',
} as const;

/**
 * **Si la cuenta tiene `privilegio` sobre `codigo`**, leido de `GET /seguridad/sesion/permisos`.
 *
 * Es el unico sitio donde se lee esa matriz, y lo usan los dos que la necesitan: el catalogo, que
 * pregunta por `lectura` para saber que ofrecer, y el mando del ejercicio de la barra (#391), que
 * pregunta por `especial` sobre `cambiar_anio` para saber si ofrecerse. Con dos lecturas a mano,
 * una podria aceptar lo que la otra rechaza.
 *
 * `Array.isArray` y no un `as`: esto viene de la red, y lo que el contrato promete es «objeto».
 * Un valor que no sea lista aqui no puede tumbar el arbol entero, ni ofrecer un mando.
 */
export function tieneElPrivilegio(
  permisos: PermisosDeLaSesion,
  codigo: string,
  privilegio: string,
): boolean {
  const privilegios: unknown = permisos[codigo];
  return Array.isArray(privilegios) && privilegios.includes(privilegio);
}

/** Lo que se sabe del catalogo despues de componerlo. */
export interface CatalogoCompuesto {
  /** Lo que se ofrece, en el orden en que el backend publica sus modulos. */
  readonly catalogo: Catalogo;
  /** Lo que el backend publica y este sistema no tiene en su arbol. */
  readonly sinCatalogo: readonly string[];
  /** Lo que se ofreceria si la cuenta pudiera, y no puede. Es lo que el AC2 esconde. */
  readonly sinPermiso: readonly string[];
  /** Los dos ajenos, restados con su motivo. */
  readonly deOtroSistema: readonly string[];
}

/** Los codigos de acceso que la cuenta puede LEER. Ver `tieneElPrivilegio`. */
function loQuePuedeLeer(permisos: PermisosDeLaSesion): ReadonlySet<string> {
  return new Set(
    Object.keys(permisos).filter((codigo) => tieneElPrivilegio(permisos, codigo, PRIVILEGIO_LECTURA)),
  );
}

/**
 * El catalogo de este sistema, filtrado por lo que la cuenta puede abrir.
 *
 * El orden es el del BACKEND y no el del arbol: es quien decide en que orden se ensenan los
 * modulos, y hoy coinciden.
 */
export function componer(
  nuestro: Catalogo,
  modulos: readonly ModuloDelSistema[],
  accesos: readonly AccesoDelSistema[],
  permisos: PermisosDeLaSesion,
  codigoDe: (modulo: ModuloDelCatalogo) => string,
): CatalogoCompuesto {
  const legibles = loQuePuedeLeer(permisos);
  const porCodigo = new Map(nuestro.map((m) => [codigoDe(m), m]));

  const catalogo: ModuloDelCatalogo[] = [];
  const sinCatalogo: string[] = [];
  const sinPermiso: string[] = [];
  const deOtroSistema: string[] = [];

  for (const modulo of [...modulos].sort((a, b) => a.orden - b.orden)) {
    if (DE_OTRO_SISTEMA.has(modulo.codigo)) {
      deOtroSistema.push(modulo.codigo);
      continue;
    }
    if (!modulo.activo) continue;

    const nuestroModulo = porCodigo.get(modulo.codigo);
    if (nuestroModulo === undefined) {
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

    // El rotulo del backend pisa al del artboard. Ver el javadoc.
    catalogo.push({ ...nuestroModulo, rotulo: modulo.nombre });
  }

  return { catalogo, sinCatalogo, sinPermiso, deOtroSistema };
}
