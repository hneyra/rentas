import type { Tono } from '../dominio/valores.ts';

/**
 * El tono con que se pinta cada estado, y **por que vive en la pantalla y no en el dato**.
 *
 * El artboard declara el par entero —`['Observado', 'bad', …]`, `{ estado: 'Al día', tono:
 * 'ok' }`— porque alli el dato y el dibujo son el mismo archivo. Aqui no: el estado llega del
 * backend, que publica `estado`, `frente` u `operacion` como texto y **no publica ningun tono**.
 * Comprobado sobre las 181 formas de `docs/50-api/formas-de-la-api.json`.
 *
 * Y esta bien que no lo publique. Un color no es un dato de negocio: es como esta pantalla
 * decide ensenarlo, y la de al lado puede decidir otra cosa sin que cambie la deuda de nadie.
 * Lo que si es dato —el texto del estado— viaja siempre, y por eso `Insignia` exige el texto
 * dentro: un estado que solo se comunica por color no se comunica a quien no distingue ese
 * color.
 *
 * La tabla es la del artboard, par a par, y `verificaciones/secciones-del-artboard.test.ts` la
 * compara contra el `.dc.html`: si alli cambia el tono de un estado, aqui sale rojo.
 */
const TONOS: Readonly<Record<string, Tono>> = {
  // De `PREDIOS`, el padron.
  'Al día': 'ok',
  'Con deuda': 'atencion',
  'En coactiva': 'mal',
  Observado: 'mal',
  'Sin conciliar': 'atencion',
  // De `bandeja`, la cola de trabajo del panel.
  'En trámite': 'atencion',
  // De `actividad`, la bitacora del panel.
  Determinado: 'ok',
  Alta: 'info',
  Baja: 'atencion',
  // Los dos que salen de `activo`, que es lo que el padron SI publica de cada contribuyente.
  Activo: 'ok',
  'De baja': 'atencion',
};

/**
 * El tono de ese estado, y `info` para el que no este en la tabla.
 *
 * **No revienta con un estado desconocido, y es deliberado.** El backend puede publicar manana
 * un estado que esta tabla no tenga —«En fraccionamiento», «Prescrito»—, y la respuesta correcta
 * es ensenarlo en gris con su texto, no dejar la lista en blanco. El texto es el dato; el color,
 * un realce.
 */
export function tonoDelEstado(estado: string): Tono {
  return TONOS[estado] ?? 'info';
}

/** Los estados que la tabla conoce. La prueba los compara con los del artboard. */
export const ESTADOS_CON_TONO = Object.keys(TONOS);
