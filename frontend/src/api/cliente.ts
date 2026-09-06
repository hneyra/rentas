/**
 * El unico sitio del frontend donde se llama a `fetch`.
 *
 * No es una preferencia de estilo: es lo que sostiene todo lo que vendra encima. El token
 * (ADR-0030 §3), la clave de idempotencia de las escrituras y el formato de error del
 * backend —`problem+json`— se enchufan en un sitio o en veinte. Un `fetch` suelto en una
 * pantalla no se salta una convencion: se salta las tres, y sobrevive a la integracion
 * como un caso aparte que nadie recuerda. Por eso la excepcion de la regla es este
 * directorio y solo este, y la prueba comprueba que es exactamente uno.
 *
 * F-1 deja el minimo: ruta, metodo y el error con su codigo. El token, la idempotencia y
 * el catalogo de errores son del issue del proxy de datos.
 */

/** Todo lo de este sistema cuelga de `rentas/` (ADR-0030 §2): la ruta dice quien responde. */
const PREFIJO = '/rentas/api/v1';

/** Lo que el backend contesta cuando algo va mal, en `problem+json` (RFC 9457). */
export class ErrorDeLaApi extends Error {
  readonly estado: number;

  constructor(estado: number, mensaje: string) {
    super(mensaje);
    this.name = 'ErrorDeLaApi';
    this.estado = estado;
  }
}

export interface OpcionesDeSolicitud {
  readonly metodo?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  readonly cuerpo?: unknown;
  readonly senal?: AbortSignal;
}

/**
 * Pide `ruta` al backend de rentas y devuelve su cuerpo ya interpretado.
 *
 * @param ruta relativa al prefijo del sistema, empezando por `/`
 */
export async function solicitar<T>(ruta: string, opciones: OpcionesDeSolicitud = {}): Promise<T> {
  const respuesta = await fetch(`${PREFIJO}${ruta}`, {
    method: opciones.metodo ?? 'GET',
    headers: {
      Accept: 'application/json',
      ...(opciones.cuerpo === undefined ? {} : { 'Content-Type': 'application/json' }),
    },
    ...(opciones.cuerpo === undefined ? {} : { body: JSON.stringify(opciones.cuerpo) }),
    ...(opciones.senal === undefined ? {} : { signal: opciones.senal }),
  });

  if (!respuesta.ok) {
    // El estado viaja en el error. Una interfaz que solo recibe «fallo» no puede
    // distinguir «no tienes permiso» de «el otro sistema esta caido», y acaba ensenando
    // la misma frase inutil para las dos.
    throw new ErrorDeLaApi(respuesta.status, `${opciones.metodo ?? 'GET'} ${ruta}`);
  }

  return (await respuesta.json()) as T;
}
