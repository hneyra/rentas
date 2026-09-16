/**
 * **Las cuarenta pantallas sin levantar la plataforma entera** (#114).
 *
 * <h2>El hueco que esto cierra</h2>
 *
 * Las cuarenta definiciones estan en el codigo y dibujan sus propias cifras —atadas al artboard
 * campo a campo—, asi que **no les falta ningun dato: les falta el camino**. El arbol de modulos
 * llega de la red (`useCatalogoPermitido` pide `GET /seguridad/{modulos,accesos}` y
 * `/seguridad/sesion/permisos`), y sin las tres no hay ni un destino que abrir: lo unico que se
 * lee es «No se pudo saber que modulos puede abrir esta cuenta…». Ese mensaje es correcto y la
 * decision tambien; lo que costaba era mirar la interfaz — PostgreSQL, Keycloak, Traefik y las
 * cinco aplicaciones para comprobar el color de una cabecera.
 *
 * <h2>Se siembra EL CATALOGO, y nada mas</h2>
 *
 * Lo que se pone en la cache son las tres respuestas de seguridad, o sea **que pantallas
 * existen**. No se siembra ni un dato de pantalla: las dos que piden de verdad —`panel` y
 * `coa-panel`— salen a la red, no encuentran a nadie y **ensenan su estado de error**, que es la
 * verdad y es un estado que hay que poder mirar. Contestarles algo inventado seria devolver el
 * proxy de datos que #90 saco del arbol, y ese se fue con su motivo: se quedo sin nada que
 * contestar porque las cifras ya viven en las definiciones.
 *
 * <h2>Y lo que se siembra son BYTES MEDIDOS</h2>
 *
 * `seguridadMedida.ts` son respuestas de un `curl` a la instalacion de verdad —doce modulos, 134
 * accesos y la matriz entera, capturados el 2026-09-07—. Inventarlas aqui haria que lo que se
 * mira fuese una fantasia, y la primera vez que el backend cambiara de forma esto seguiria
 * ensenando el arbol de siempre.
 *
 * <h2>Por que vive FUERA de `src/`, que es donde vive el codigo de la interfaz</h2>
 *
 * Porque `verificaciones/camino-a-la-api.test.ts` prohibe que un archivo de produccion de `src/`
 * importe las capturas, y esa prohibicion es de las que sostienen algo: un `arbol ??
 * ARBOL_MEDIDO` en cualquier gancho devolveria la navegacion constante que I-3 vino a quitar, y
 * esta vez con una constante que ademas **parece un dato medido**. Meter esto en `src/` obligaba
 * a tallarle una excepcion a esa guarda; dejandolo aqui la guarda no se toca y sigue diciendo lo
 * mismo para todo `src/`. Es donde ya vive `e2e/instalacion.ts`, que importa estas mismas
 * capturas por el mismo motivo y con el mismo riesgo.
 *
 * **Lo unico que lo alcanza es un `import()` dinamico detras de dos condiciones constantes al
 * construir** (`src/arranque.ts`). Ahi esta escrito por que, y que es lo que se mide.
 */

import { CONSULTAS } from '../src/aplicacion.tsx';
import type { Paginado } from '../src/datos/lecturas.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
} from '../src/datos/seguridadMedida.ts';
import { LLAVES } from '../src/datos/useCatalogoPermitido.ts';

/**
 * El envoltorio de paginacion, como lo manda el backend.
 *
 * Las dos listas van PAGINADAS y no peladas: `pedirPagina` devuelve el envoltorio entero y
 * `pedirLista` desenvuelve `contenido`. Con un arreglo suelto la composicion revienta con
 * «Cannot read properties of undefined (reading 'length')», un rojo que habla de `length` y no
 * de la forma de la respuesta.
 */
function comoPagina<T>(contenido: readonly T[]): Paginado<T> {
  return {
    contenido,
    pagina: 0,
    // El mismo que pide `RUTAS.accesos`: los 134 accesos no caben en los veinte por omision.
    tamano: 200,
    totalElementos: contenido.length,
    totalPaginas: 1,
    hayMas: false,
  };
}

/**
 * Pone las tres respuestas de seguridad en la cache, ya contestadas.
 *
 * <h2>Por que hace falta `staleTime` y no basta con `setQueryData`</h2>
 *
 * Porque una consulta sembrada **sigue teniendo su `queryFn`**, y con el `staleTime` por omision
 * —cero— el dato nace rancio: React Query lo ensena y sale a refrescarlo al montar. Sin backend
 * ese refresco falla, y una consulta que falla pasa a `status: 'error'` **aunque conserve el
 * dato**; `useCatalogoPermitido` mira `isError` antes que nada, asi que la pantalla acabaria
 * ensenando el mismo «No se pudo saber que modulos puede abrir esta cuenta» que esto viene a
 * quitar — despues de haber dibujado el arbol un instante.
 *
 * Con el dato fresco para siempre, la `queryFn` no llega a correr: no hay ninguna peticion de
 * seguridad, que es justo lo que «sin backend» significa.
 *
 * Se acota a la rama `seguridad` y no se toca el cliente entero: `panel` y `coa-panel` tienen
 * que seguir pidiendo y fallando de verdad.
 */
export function sembrarElCatalogo(): void {
  CONSULTAS.setQueryDefaults(LLAVES.rama, { staleTime: Infinity, gcTime: Infinity });
  CONSULTAS.setQueryData(LLAVES.modulos, MODULOS_MEDIDOS);
  CONSULTAS.setQueryData(LLAVES.accesos, comoPagina(ACCESOS_MEDIDOS));
  CONSULTAS.setQueryData(LLAVES.permisos, PERMISOS_MEDIDOS);

  // Y se dice, porque una interfaz que se ve entera sin que nada este levantado es exactamente lo
  // que alguien puede confundir con «el backend contesto». Va por `warn` y no por `log`: la
  // consola de desarrollo tiene ruido, y esto tiene que leerse.
  console.warn(
    'rentas-web: EL CATALOGO ESTA SEMBRADO, no pedido (VITE_KAMAYUK_SIN_PLATAFORMA=true).\n' +
      'Los doce modulos, los 134 accesos y la matriz de permisos salen de la captura de\n' +
      '`seguridadMedida.ts`, y no se fue a Keycloak. Las pantallas que piden datos —las que\n' +
      'tienen conector en `datos/conectores.ts`— van a fallar, que es la verdad cuando no hay\n' +
      'backend.\n' +
      'Para trabajar contra la plataforma levantada: `yarn dev:con-plataforma`.',
  );
}
