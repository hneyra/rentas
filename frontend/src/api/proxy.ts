/**
 * El proxy de datos: la API de `rentas`, simulada en el navegador.
 *
 * <h2>Por que intercepta el TRANSPORTE y no la aplicacion</h2>
 *
 * Las pantallas necesitan datos y el backend todavia no se conecta. La salida facil habria
 * sido que cada pantalla leyera los suyos de una constante importada; la trampa de esa salida
 * es que el dia que el backend existiera habria que reescribir cada pantalla para que pida por
 * HTTP — y hasta ese dia, ninguna habria ejercido una sola vez el camino que va a usar en
 * produccion.
 *
 * Este proxy lo evita interceptando **en la frontera del transporte**: sustituye
 * `globalThis.fetch`. La aplicacion llama a `solicitar()` de `src/api/cliente.ts` con la ruta
 * real del contrato —`GET /rentas/api/v1/rentas/contribuyentes`— y recibe un `Response` con
 * su JSON, sus cabeceras y su codigo de estado de verdad. Todo el camino se ejerce: la URL se
 * compone, los parametros de consulta viajan, el error se convierte en `ErrorDeLaApi`. **La
 * aplicacion no sabe quien contesta, y esa ignorancia es el objetivo.**
 *
 * <h2>Como se apaga</h2>
 *
 * No llamando a `instalarProxyDeDatos()`, que es lo que pasa por omision: `arranque.ts` solo
 * lo importa cuando `VITE_KAMAYUK_PROXY_DE_DATOS` vale `'true'`, y con la bandera apagada el
 * `import()` dinamico se cae del bundle entero — datos capturados incluidos. Un `yarn build`
 * de produccion no lleva ni una cifra del artboard dentro.
 *
 * <h2>Y se apaga tambien operacion por operacion</h2>
 *
 * `datos/servidas.ts` lista las rutas que el backend ya sirve, y esas el proxy las deja pasar
 * al `fetch` de verdad. Desde I-1 hay **dos** —las dos lecturas de sesion—, y ahi se explica por
 * que son esas; el mecanismo existia antes de tener a quien aplicarselo, porque un mecanismo
 * escrito el dia que hace falta se escribe mal ese dia.
 *
 * <h2>Lo que deliberadamente NO simula</h2>
 *
 * No filtra, no ordena, no pagina, no valida y no persiste. Un proxy que fingiera la semantica
 * de `?uso=Comercio` estaria inventando un comportamiento que el backend aun no ha decidido, y
 * la interfaz se acabaria construyendo contra esa invencion. Filtrar es del servidor: aqui la
 * peticion se hace de verdad y la respuesta es siempre el juego de datos del prototipo. Lo
 * mismo con las escrituras: un `POST` responde 201 con la forma que el backend publica, sin
 * guardar nada — simular persistencia sin reglas de negocio produce un sistema que acepta lo
 * que el backend rechazara.
 */

import { OPERACIONES, type Operacion } from '../datos/operaciones.ts';
import { YA_SERVIDAS, laSirveElBackend, type OperacionServida } from '../datos/servidas.ts';

/**
 * Raiz de todas las operaciones de este sistema.
 *
 * Es `/rentas/api/v1` y no `/api/v1`: ADR-0030 §2 pone el sistema delante de la ruta porque el
 * primer segmento enruta sin mirar mas, y el mismo Traefik sirve las cuatro interfaces. Tiene
 * que coincidir con `Api.RAIZ` del backend y con el `PREFIJO` de `cliente.ts`; que coincida lo
 * comprueba `proxy.test.ts`.
 */
export const RAIZ = '/rentas/api/v1';

/** Latencia simulada, para que los estados de carga se vean en desarrollo. */
const LATENCIA_MINIMA_MS = 120;
const LATENCIA_MAXIMA_MS = 320;

interface RutaCompilada {
  readonly metodo: string;
  readonly patron: RegExp;
  readonly operacion: Operacion;
}

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

const TABLA: readonly RutaCompilada[] = OPERACIONES.map((operacion) => ({
  metodo: operacion.metodo,
  patron: compilar(operacion.ruta),
  operacion,
}));

/** La operacion que atiende esa peticion, o `null`. */
function operacionDe(metodo: string, rutaRelativa: string): Operacion | null {
  const buscado = metodo.toUpperCase();
  return (
    TABLA.find((r) => r.metodo === buscado && r.patron.test(rutaRelativa))?.operacion ?? null
  );
}

/** Los verbos que esa ruta si admite. Vacio si la ruta no existe en ninguno. */
function verbosDe(rutaRelativa: string): readonly string[] {
  return TABLA.filter((r) => r.patron.test(rutaRelativa)).map((r) => r.metodo);
}

const esperar = (ms: number) => new Promise((listo) => setTimeout(listo, ms));

/**
 * Un cuerpo `application/problem+json` con la forma que publica el backend.
 *
 * Los seis miembros son los de `ManejadorDeErrores.cuerpoDe`: los cuatro de RFC 9457 —`type`,
 * `title`, `status`, `detail`— mas las dos extensiones del contrato, `codigo` y `mensaje`. La
 * interfaz reacciona al **codigo**, que es estable, y no al texto en castellano, que se
 * reescribe en cuanto alguien lo lee en voz alta: si el proxy publicara otra forma, cada
 * pantalla aprenderia a leer un error que el backend no manda.
 */
function problema(codigo: string, estado: number, titulo: string, detalle: string): Response {
  return new Response(
    JSON.stringify({
      type: `https://sgtm.gob.pe/errores/${codigo.toLowerCase()}`,
      title: titulo,
      status: estado,
      detail: detalle,
      codigo,
      mensaje: detalle,
    }),
    { status: estado, headers: { 'content-type': 'application/problem+json' } },
  );
}

/** La ruta no la atiende ningun controlador: 404, como `ManejadorDeErrores.rutaNoEncontrada`. */
function noEncontrada(metodo: string, rutaRelativa: string): Response {
  return problema(
    'NO_ENCONTRADO',
    404,
    'No se encontro lo solicitado',
    `El proxy de datos no simula «${metodo} ${rutaRelativa}». Simula ${OPERACIONES.length} de ` +
      'las operaciones del contrato, las que alimenta el artboard RentasV6; las demas las ' +
      'sirve el backend. Si la necesitas, se anade en src/datos/operaciones.ts con su forma ' +
      'de docs/50-api/formas-de-la-api.json.',
  );
}

/**
 * La ruta existe y el verbo no: 405 con su cabecera `Allow`.
 *
 * Es un codigo propio y no un 404 porque las dos respuestas se arreglan de maneras distintas
 * —«esa operacion no esta publicada» y «esta publicada, y la pides con el verbo equivocado»—,
 * y el backend ya las distingue (`CodigoDeError.METODO_NO_ADMITIDO`, #556). Un proxy que las
 * juntara ensenaria a la interfaz un contrato de errores mas pobre que el de verdad.
 */
function verboNoAdmitido(metodo: string, rutaRelativa: string, verbos: readonly string[]): Response {
  const respuesta = problema(
    'METODO_NO_ADMITIDO',
    405,
    'El verbo HTTP no se admite en esta ruta',
    `El verbo '${metodo}' no se admite en «${rutaRelativa}». ` +
      `Admitidos: ${[...verbos].sort().join(', ')}`,
  );
  const cabeceras = new Headers(respuesta.headers);
  cabeceras.set('allow', [...verbos].sort().join(', '));
  return new Response(respuesta.body, { status: 405, headers: cabeceras });
}

/**
 * La ruta esta declarada como servida y el backend dice que no la implementa.
 *
 * **Solo el 501, y el 404 ya no. Lo cambio I-1 con su medida.** Hasta entonces esto se
 * disparaba tambien con un 404, dando por hecho que un 404 de una ruta declarada significaba
 * «esa ruta no esta publicada». **No lo significa**: el cuarto peldano de la escalera de
 * identidad —«El token identifica a 'X', que no es un usuario de esta municipalidad»— es un
 * 404 legitimo de `GET /seguridad/sesion`, que si existe. Con la conversion puesta, ese
 * peldano no llegaba nunca a la pantalla: se convertia en un 502 que acusa a la lista de
 * `servidas.ts` de un desajuste que no hay, y mandaba a mirar el archivo equivocado.
 *
 * Y los dos 404 son **indistinguibles en el cable**, medido con `curl`: el de la ruta que no
 * existe y el del usuario que no es de esta municipalidad traen los dos `codigo:
 * "NO_ENCONTRADO"`. Asi que no era cuestion de afinar la condicion — la premisa era falsa.
 *
 * Lo que protegia sigue protegido, y mejor: `verificaciones/camino-a-la-api.test.ts` exige que
 * cada ruta de `YA_SERVIDAS` sea una clave del contrato. Es estatico, se pone rojo en `yarn
 * verificar` y no necesita que nadie levante un backend para decirlo.
 */
function declaradaYNoServida(metodo: string, rutaRelativa: string, estado: number): Response {
  return problema(
    'ERROR_INTERNO',
    502,
    'La operacion esta declarada como servida y el backend no la implementa',
    `«${metodo} ${rutaRelativa}» esta en la lista de operaciones que el backend ya sirve, y el ` +
      `backend respondio ${estado}. Quita la ruta de src/datos/servidas.ts o implementa la ` +
      'operacion: caer al proxy en silencio esconderia justo el desajuste que se quiere ver.',
  );
}

/** El `fetch` que habia antes de instalar. Se devuelve tal cual al desinstalar. */
let original: typeof fetch | null = null;

export interface OpcionesDelProxy {
  /**
   * Latencia simulada. Encendida en desarrollo, para que los estados de carga se vean; apagada
   * por omision, porque en las pruebas medio segundo por peticion no prueba nada.
   */
  readonly latencia?: boolean;
  /**
   * Las que ya sirve el backend. Por omision, las de `servidas.ts`, que hoy son ninguna.
   *
   * Se puede pasar otra lista, y es lo que hace `proxy.test.ts`: probar el mecanismo —que la
   * lista deja pasar, que un desajuste suena— sin depender de que la lista real tenga algo.
   */
  readonly yaServidas?: readonly OperacionServida[];
}

/**
 * Sustituye `globalThis.fetch` por el proxy. Devuelve la funcion que lo desinstala.
 *
 * Solo intercepta lo que cuelga de `/rentas/api/v1`; cualquier otra peticion —una fuente
 * tipografica, una imagen, el propio `index.html`— sigue su camino sin tocarse. Y lo que
 * cuelga de `/catastro/api/v1` o de `/caja/api/v1` tampoco es suyo: son otros sistemas y otros
 * repositorios, y fingir sus respuestas seria inventar contratos ajenos.
 */
export function instalarProxyDeDatos({
  latencia = false,
  yaServidas = YA_SERVIDAS,
}: OpcionesDelProxy = {}): () => void {
  if (original !== null) return desinstalarProxyDeDatos;
  original = globalThis.fetch;
  // Para delegar hace falta ligarlo; para restaurar, no: devolver el envoltorio ligado en vez
  // de la funcion original dejaria una capa pegada en cada ciclo de instalar y desinstalar.
  const anterior = original.bind(globalThis);

  globalThis.fetch = async (
    entrada: RequestInfo | URL,
    opciones?: RequestInit,
  ): Promise<Response> => {
    const url = new URL(
      typeof entrada === 'string' ? entrada : entrada instanceof URL ? entrada.href : entrada.url,
      globalThis.location?.origin ?? 'http://localhost',
    );
    if (!url.pathname.startsWith(RAIZ)) return anterior(entrada, opciones);

    const metodo = (
      opciones?.method ??
      (typeof entrada === 'object' && 'method' in entrada ? entrada.method : 'GET')
    ).toUpperCase();
    const rutaRelativa = url.pathname.slice(RAIZ.length);

    // Lo que el backend ya sirve, sale de verdad. Si contesta que no la conoce, se dice en voz
    // alta: caer al proxy en silencio esconderia justo lo que se quiere ver —que la ruta de la
    // lista y la del backend no cuadran—.
    if (laSirveElBackend(yaServidas, metodo, rutaRelativa)) {
      const respuesta = await anterior(entrada, opciones);
      // El 404 NO se convierte: es una respuesta legitima de una ruta que existe —el cuarto
      // peldano de la escalera de identidad—, y tragarselo era esconder el unico mensaje que
      // dice que cuenta esta entrando. Ver `declaradaYNoServida`.
      return respuesta.status === 501
        ? declaradaYNoServida(metodo, rutaRelativa, respuesta.status)
        : respuesta;
    }

    if (latencia) {
      await esperar(LATENCIA_MINIMA_MS + Math.random() * (LATENCIA_MAXIMA_MS - LATENCIA_MINIMA_MS));
    }

    const operacion = operacionDe(metodo, rutaRelativa);
    if (operacion === null) {
      const verbos = verbosDe(rutaRelativa);
      return verbos.length === 0
        ? noEncontrada(metodo, rutaRelativa)
        : verboNoAdmitido(metodo, rutaRelativa, verbos);
    }

    // Ni la cadena de consulta ni el cuerpo llegan al constructor: no se le pasan. Una
    // escritura responde 201 con la forma que el backend publica y no guarda nada.
    return new Response(JSON.stringify(operacion.cuerpo()), {
      status: metodo === 'GET' ? 200 : 201,
      headers: { 'content-type': 'application/json' },
    });
  };

  return desinstalarProxyDeDatos;
}

export function desinstalarProxyDeDatos(): void {
  if (original === null) return;
  globalThis.fetch = original;
  original = null;
}

export function proxyDeDatosInstalado(): boolean {
  return original !== null;
}
