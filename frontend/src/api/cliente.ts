/**
 * El unico sitio del frontend donde se llama a `fetch`.
 *
 * No es una preferencia de estilo: es lo que sostiene todo lo que viene encima. El token
 * (ADR-0030 §3), la clave de idempotencia de las escrituras y el formato de error del
 * backend —`problem+json`— se enchufan en un sitio o en veinte. Un `fetch` suelto en una
 * pantalla no se salta una convencion: se salta las tres, y sobrevive a la integracion
 * como un caso aparte que nadie recuerda. Por eso la excepcion de la regla es este
 * directorio y solo este, y la prueba comprueba que es exactamente uno.
 *
 * F-1 dejo el minimo: ruta, metodo y el error con su estado. I-1 enchufa las dos piezas que
 * faltaban para salir a la red de verdad — el **token** y el **cuerpo del error**—; la clave de
 * idempotencia sigue pendiente, y sigue teniendo aqui su sitio reservado.
 *
 * <h2>El `municipalidadId` no se manda, y no se puede mandar (regla 2, ADR-0005)</h2>
 *
 * Esta funcion compone **la ruta que se le da y nada mas**: no anade parametros de consulta, no
 * anade cabeceras propias mas alla de las tres de abajo, y el cuerpo es el que le pasan. El
 * inquilino sale del token y lo fija el backend con `SET LOCAL`. Lo vigilan tres cosas a la vez:
 * la prohibicion `municipalidad-en-el-cliente` de ESLint, que ni siquiera deja escribir el
 * identificador; una prueba que espia lo que sale por el cable; y esta propiedad de que aqui no
 * se compone nada.
 */

import { token } from './identidad.ts';

/** Todo lo de este sistema cuelga de `rentas/` (ADR-0030 §2): la ruta dice quien responde. */
const PREFIJO = '/rentas/api/v1';

/**
 * Los miembros del `problem+json` que el backend publica, tal como los publica.
 *
 * Son los de `ManejadorDeErrores.cuerpoDe`: los cuatro de RFC 9457 —`type`, `title`, `status`,
 * `detail`— mas las dos extensiones del contrato, `codigo` y `mensaje`. **Y llegan de a pocos:**
 * medido contra la instalacion, el 401 de la cadena de identidad trae CUATRO —`status`, `title`,
 * `codigo`, `mensaje`— y ni `type` ni `detail`, mientras que el 404 de una ruta que no existe
 * trae los seis mas `instance`. Por eso todos son opcionales aqui: dar por hecho que viene
 * `detail` dejaria la explicacion de la pantalla en `undefined` justo en el peldano mas comun.
 */
interface CuerpoDeProblema {
  readonly type?: string;
  readonly title?: string;
  readonly status?: number;
  readonly detail?: string;
  readonly instance?: string;
  readonly codigo?: string;
  readonly mensaje?: string;
}

/**
 * Lo que el backend contesta cuando algo va mal, en `problem+json` (RFC 9457).
 *
 * <h2>Por que el `codigo` es un campo y no una linea de texto</h2>
 *
 * Hasta I-1 esta clase guardaba **solo el estado y «VERBO /ruta»**, y tiraba `codigo` y
 * `mensaje`, que ya llegaban. Con eso, los tres primeros peldanos de la escalera de identidad
 * —401 `NO_AUTENTICADO`, 403 `SIN_MUNICIPALIDAD`, 403 `SIN_PRIVILEGIO`— eran **indistinguibles**
 * entre si desde la pantalla, y ninguno se podia explicar a quien atiende. Y son tres
 * situaciones con tres remedios distintos: volver a identificarse, pedirle al administrador que
 * asigne la municipalidad, y pedir el permiso que falta.
 *
 * La interfaz reacciona al **codigo**, que es estable, y no al texto en castellano, que se
 * reescribe en cuanto alguien lo lee en voz alta.
 */
export class ErrorDeLaApi extends Error {
  readonly estado: number;
  /** La extension `codigo` del contrato —`NO_AUTENTICADO`, `SIN_MUNICIPALIDAD`…—, o `null`. */
  readonly codigo: string | null;
  /** La extension `mensaje`: lo que el backend dice que paso, en castellano. */
  readonly mensaje: string | null;
  /** El `title` de RFC 9457. */
  readonly titulo: string | null;
  /** El `detail` de RFC 9457. Puede no venir: la cadena de identidad no lo manda. */
  readonly detalle: string | null;
  /** `VERBO /ruta`, lo que se pidio. Es lo que se ensena cuando el cuerpo no dice nada. */
  readonly operacion: string;

  constructor(estado: number, operacion: string, cuerpo: CuerpoDeProblema = {}) {
    // El `message` de `Error` es lo que acaba en pantalla por el camino corto, asi que lleva lo
    // mas util que haya llegado: lo que el backend dijo, y si no dijo nada, que se pidio.
    super(cuerpo.mensaje ?? cuerpo.detail ?? cuerpo.title ?? operacion);
    this.name = 'ErrorDeLaApi';
    this.estado = estado;
    this.codigo = cuerpo.codigo ?? null;
    this.mensaje = cuerpo.mensaje ?? null;
    this.titulo = cuerpo.title ?? null;
    this.detalle = cuerpo.detail ?? null;
    this.operacion = operacion;
  }
}

export interface OpcionesDeSolicitud {
  readonly metodo?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  readonly cuerpo?: unknown;
  readonly senal?: AbortSignal;
}

/**
 * Lee el `problem+json` de una respuesta fallida, sin dejar que su lectura tape el fallo.
 *
 * Un `await respuesta.json()` sobre un cuerpo vacio —o sobre el HTML de un proxy mal
 * configurado— lanza, y esa excepcion sustituiria al `ErrorDeLaApi` que se estaba construyendo:
 * la pantalla acabaria ensenando «Unexpected token < in JSON» en lugar de «no tienes permiso».
 */
async function problemaDe(respuesta: Response): Promise<CuerpoDeProblema> {
  try {
    const cuerpo: unknown = await respuesta.json();
    return typeof cuerpo === 'object' && cuerpo !== null ? (cuerpo as CuerpoDeProblema) : {};
  } catch {
    return {};
  }
}

/**
 * Pide `ruta` al backend de rentas y devuelve su cuerpo ya interpretado.
 *
 * @param ruta relativa al prefijo del sistema, empezando por `/`
 */
export async function solicitar<T>(ruta: string, opciones: OpcionesDeSolicitud = {}): Promise<T> {
  const metodo = opciones.metodo ?? 'GET';
  const credencial = token();

  const respuesta = await fetch(`${PREFIJO}${ruta}`, {
    method: metodo,
    headers: {
      Accept: 'application/json',
      // Sin token no se manda la cabecera. Un «Bearer null» es un token invalido y el backend
      // contesta 401 igual, pero el 401 diria «el token no vale» donde la verdad es «no hay
      // token»: dos peldanos distintos de la escalera confundidos en el unico sitio donde se
      // pueden separar sin adivinar.
      ...(credencial === null ? {} : { Authorization: `Bearer ${credencial}` }),
      ...(opciones.cuerpo === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(opciones.cuerpo === undefined ? {} : { body: JSON.stringify(opciones.cuerpo) }),
    ...(opciones.senal === undefined ? {} : { signal: opciones.senal }),
  });

  if (!respuesta.ok) {
    // El estado y el codigo viajan en el error. Una interfaz que solo recibe «fallo» no puede
    // distinguir «no tienes permiso» de «el otro sistema esta caido», y acaba ensenando la
    // misma frase inutil para las dos.
    throw new ErrorDeLaApi(respuesta.status, `${metodo} ${ruta}`, await problemaDe(respuesta));
  }

  return (await respuesta.json()) as T;
}
