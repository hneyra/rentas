/**
 * Las operaciones que el backend YA sirve en el entorno donde corre la aplicacion.
 *
 * <h2>Seis: las dos primeras de I-1 y las cuatro de I-3</h2>
 *
 * La integracion no es un salto. El backend publica 181 operaciones y el proxy simula
 * dieciocho: encenderlas todas a la vez seria cambiar 181 respuestas en una sola tarde sin poder
 * decir cual de ellas rompio la pantalla. Con esta lista se enciende una, se mira, y se enciende
 * la siguiente — el proxy deja pasar lo que este declarado aqui y sigue contestando lo demas.
 *
 * <h2>Por que estas dos y no otras</h2>
 *
 * Porque son las que **demuestran que el camino existe**, y ninguna otra lo demuestra tan
 * barato. No dibujan una tabla ni un importe: contestan quien esta trabajando y de que
 * municipalidad, que es lo unico que hace falta para saber que el token viajo, que Vite lo
 * encamino, que Traefik lo enruto por `PathPrefix(/rentas)`, que la cadena de identidad lo
 * acepto y que `SET LOCAL` fijo el inquilino. Si algo de esa cadena falla, fallan estas dos y no
 * hay ninguna pantalla a medio pintar de por medio.
 *
 * Y son las dos que hacian **mentir a la barra global**: hasta I-1 el nombre de la entidad y el
 * del usuario eran constantes del artboard —«Municipalidad Distrital de Catacaos» y «J. Cárdenas
 * Vega»—, o sea que la cabecera de todas las pantallas afirmaba de quien son unas cifras sin
 * haberselo preguntado a nadie.
 *
 * <h2>Lo que hizo falta antes, en el orden en que se dijo</h2>
 *
 * El javadoc anterior lo dejo escrito: «token en `solicitar()`, `server.proxy` hacia el backend,
 * y entonces una entrada aqui». Los tres estan, y los dos primeros eran de verdad bloqueantes:
 *
 * <ol>
 *   <li><b>El token.</b> `solicitar()` manda `Authorization: Bearer` desde `api/identidad.ts`,
 *       que lo consigue con codigo de autorizacion y PKCE S256. Sin el, encender una ruta no
 *       traeria datos: traeria un <b>401</b> en `problem+json`, medido con `curl`.</li>
 *   <li><b>El camino.</b> `vite.config.ts` declara `server.proxy` hacia el backend. Sin el,
 *       `/rentas/api/v1/...` lo atendia el propio servidor de Vite y devolvia el `index.html`
 *       de la aplicacion: un <b>200 con HTML</b> donde la pantalla espera JSON, que es peor que
 *       un error porque no parece uno.</li>
 * </ol>
 *
 * <h2>Una ruta declarada aqui tiene que existir en el contrato, y se comprueba</h2>
 *
 * `verificaciones/camino-a-la-api.test.ts` exige que cada entrada de esta lista sea una clave de
 * `docs/50-api/formas-de-la-api.json` —el archivo que genera `FormasDeLaApiTest` del tipo de
 * retorno de cada controlador—. Es una comprobacion **estatica** y es la que sostiene la lista:
 * declarar aqui una ruta que el backend no publica sale rojo antes de que nadie levante nada.
 *
 * Sustituye a una heuristica que estaba mal y que I-1 quito con su medida: el proxy convertia en
 * un 502 ruidoso **cualquier 404** de una ruta declarada, dando por hecho que un 404 significaba
 * «esa ruta no esta publicada». No lo significa. El cuarto peldano de la escalera de identidad
 * —el token identifica a alguien que no es usuario de esta municipalidad— es un <b>404 legitimo
 * de una ruta que si existe</b>, y confundirlos se lo tragaba entero. Ver `api/proxy.ts`.
 */

/** Una operacion que el backend ya sirve. Se compara por verbo y por ruta, con sus `{...}`. */
export interface OperacionServida {
  readonly metodo: string;
  /** Ruta bajo la raiz del sistema, con sus parametros entre llaves. */
  readonly ruta: string;
}

/**
 * Las seis de seguridad. Todo lo demas lo sigue contestando el proxy.
 *
 * El tipo es `readonly OperacionServida[]` y no una tupla: lo que cambia el dia que se encienda
 * la septima es esta lista, y nada mas.
 *
 * <h2>Las cuatro que enciende I-3, en el orden en que se encendieron</h2>
 *
 * <ol>
 *   <li><b>`GET /seguridad/modulos`.</b> Al encenderla llegan <b>12</b> modulos en el
 *       envoltorio paginado —`totalElementos: 12`, `tamano: 20`, una pagina—, y sus nombres
 *       resultaron ser <b>los rotulos del artboard byte a byte</b> para los diez que este
 *       sistema sirve. Eso no se sabia antes de pedirla: era la premisa que hacia posible
 *       empalmar el catalogo con el catalogo del backend sin traducir nada.</li>
 *   <li><b>`GET /seguridad/accesos`.</b> Llegan <b>134</b> con su `moduloId`, y **es la unica
 *       razon por la que esta lectura esta en la lista**: sin ella, la matriz de permisos es
 *       una bolsa de 134 codigos planos sin ninguna forma de saber a que rama pertenece
 *       ninguno. Se pide con `?tamano=200` porque el tamano por omision es 20 (ver `RUTAS`).</li>
 *   <li><b>`GET /seguridad/sesion/permisos`.</b> Llegan <b>134</b> llaves, una por acceso, cada
 *       una con sus siete privilegios. Y al pedirla con las <b>dos</b> cuentas de la
 *       instalacion salieron <b>identicas</b>, llave a llave: ninguna de las dos ejercita el
 *       filtro. Eso cambio como se prueba el AC2 — ver `marco/seguridadMedida.ts`.</li>
 *   <li><b>`PUT /seguridad/sesion/ejercicio`.</b> La primera <b>escritura</b> de esta interfaz.
 *       Con observacion contesta 200 y la sesion con su ejercicio dentro; sin ella contesta
 *       <b>500</b>, que es el defecto #30 y no se arregla aqui — se rodea no mandando nunca una
 *       vacia—. Con una observacion corta o un ejercicio fuera de 1990-2100 contesta
 *       <b>422 `VALIDACION`</b> con su frase, y esa frase es la que la pantalla ensena.</li>
 * </ol>
 *
 * <h2>Dos de las cuatro piden un permiso de ADMINISTRACION, y hay que decirlo</h2>
 *
 * `GET /seguridad/modulos` declara `@RequiereAcceso(acceso = "modulos", …)` y
 * `GET /seguridad/accesos` declara `acceso = "accesos"` — o sea «Modulos del sistema» y
 * «Accesos y politicas», las dos opciones con las que se administra el catalogo. **Asi que la
 * navegacion de esta aplicacion se compone hoy de dos operaciones que una cuenta de ventanilla
 * no tiene por que poder llamar**, y a la que no las tenga le contestaran 403 `SIN_PRIVILEGIO`:
 * no se quedaria sin un modulo, se quedaria sin arbol. Es exactamente el caso del AC7, y por
 * eso la pantalla lo explica y ofrece reintentar en vez de dibujar un marco vacio. Cerrarlo de
 * verdad no es de este lado: es publicar el menu de la sesion —el catalogo filtrado por quien
 * pregunta— y eso es del dueno de `seguridad`.
 */
export const YA_SERVIDAS: readonly OperacionServida[] = [
  { metodo: 'GET', ruta: '/seguridad/sesion' },
  { metodo: 'GET', ruta: '/seguridad/sesion/municipalidad' },
  { metodo: 'GET', ruta: '/seguridad/modulos' },
  { metodo: 'GET', ruta: '/seguridad/accesos' },
  { metodo: 'GET', ruta: '/seguridad/sesion/permisos' },
  { metodo: 'PUT', ruta: '/seguridad/sesion/ejercicio' },
];

/** `/rentas/vehiculos/{placa}` → `^/rentas/vehiculos/[^/]+$`. */
function compilar(ruta: string): RegExp {
  const escapado = ruta
    .split(/(\{\w+\})/)
    .map((trozo) =>
      /^\{\w+\}$/.test(trozo) ? '[^/]+' : trozo.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
    )
    .join('');
  return new RegExp(`^${escapado}$`);
}

/**
 * Si esa peticion la atiende el backend de verdad.
 *
 * @param servidas la lista que rige en este entorno; el proxy pasa `YA_SERVIDAS` salvo que se
 *   le diga otra cosa
 * @param metodo verbo HTTP, en cualquier caja
 * @param rutaRelativa la ruta ya sin la raiz del sistema, empezando por `/`
 */
export function laSirveElBackend(
  servidas: readonly OperacionServida[],
  metodo: string,
  rutaRelativa: string,
): boolean {
  const buscado = metodo.toUpperCase();
  return servidas.some(
    (o) => o.metodo.toUpperCase() === buscado && compilar(o.ruta).test(rutaRelativa),
  );
}
