/**
 * Las operaciones que el backend YA sirve en el entorno donde corre la aplicacion.
 *
 * <h2>Esta vacia, y no es un olvido</h2>
 *
 * La integracion no va a ser un salto. El backend publica 181 operaciones y el proxy simula
 * trece: el dia que la interfaz apunte a un Spring Boot de verdad, encenderlas todas a la vez
 * seria cambiar 181 respuestas en una sola tarde sin poder decir cual de ellas rompio la
 * pantalla. Con esta lista se enciende una, se mira, y se enciende la siguiente — el proxy
 * deja pasar lo que este declarado aqui y sigue contestando lo demas.
 *
 * <h2>Por que hoy no hay ninguna encendida</h2>
 *
 * Dos motivos, los dos comprobables y ninguno de ellos «todavia no lo hemos hecho»:
 *
 * <ol>
 *   <li><b>La aplicacion no consigue un token, y todas las operaciones lo exigen.</b> F-1 dejo
 *       `solicitar()` sin cabecera `Authorization` a proposito —el token es de este issue en
 *       adelante (ADR-0030 §3)—, y la cadena de identidad del backend responde <b>401 en
 *       `problem+json`</b> a cualquier peticion sin token: lo afirma
 *       `ArranqueDeLaAplicacionTest`, «y la cadena de seguridad esta montada: sin token, 401 en
 *       problem+json». Encender una ruta hoy no traeria datos, traeria un 401 — y el proxy,
 *       que solo repliega ante 404 y 501, lo dejaria pasar tal cual a la pantalla.</li>
 *   <li><b>No hay a donde mandar la peticion.</b> `vite.config.ts` no declara `server.proxy`,
 *       asi que en desarrollo `/rentas/api/v1/...` lo atiende el propio servidor de Vite y
 *       devuelve el `index.html` de la aplicacion: un `200` con HTML donde la pantalla espera
 *       JSON, que es peor que un error porque no parece uno.</li>
 * </ol>
 *
 * Encender la primera ruta es, en orden: token en `solicitar()`, `server.proxy` hacia el
 * backend, y entonces una entrada aqui.
 *
 * <h2>Y el mecanismo si esta, y se prueba</h2>
 *
 * Que la lista este vacia es un DATO, no una funcion que falte: `laSirveElBackend()` existe,
 * el proxy la consulta en cada peticion y `proxy.test.ts` la ejerce pasandole su propia lista.
 * Un mecanismo que solo se escribiera el dia que hace falta se escribiria mal ese dia.
 */

/** Una operacion que el backend ya sirve. Se compara por verbo y por ruta, con sus `{...}`. */
export interface OperacionServida {
  readonly metodo: string;
  /** Ruta bajo la raiz del sistema, con sus parametros entre llaves. */
  readonly ruta: string;
}

/**
 * Ninguna, hoy. Los dos motivos, arriba.
 *
 * El tipo es `readonly OperacionServida[]` y no `never[]`: lo que cambia el dia que se encienda
 * la primera es esta linea, y nada mas.
 */
export const YA_SERVIDAS: readonly OperacionServida[] = [];

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
