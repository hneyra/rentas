import type { MunicipalidadDeLaSesion, SesionDeLaVentanilla } from '../datos/lecturas.ts';

/**
 * La sesion tal como la contesta la instalacion, **copiada de un `curl` y no inventada**.
 *
 * <h2>Para que existe</h2>
 *
 * Desde I-1 la barra dice quien esta dentro leyendolo de la sesion, y las pruebas que la montan
 * tienen que contestar esas dos lecturas. Con un literal suelto en cada una, el dia que
 * `GET /seguridad/sesion` cambie de forma habria tantos sitios que corregir como pruebas, y
 * ninguno que lo dijera.
 *
 * **Lo que impide que vuelva un «J. Cárdenas Vega» ya no es `MarcoProps`** (#356). Esa premisa
 * estuvo escrita aqui cuando ya era falsa: `MarcoProps` era de la V6, y #90 monto la barra con
 * `Armazon` y dos literales del artboard sin que nada lo parase. Hoy lo impiden dos guardas:
 * `verificaciones/la-cabecera-no-se-escribe-a-mano.test.ts`, que parsea `aplicacion.tsx` y
 * rechaza un literal en la entidad o en la cuenta, y
 * `verificaciones/la-cabecera-es-la-de-la-sesion.test.tsx`, que monta la aplicacion con una
 * municipalidad que NO es esta —con esta, Catacaos, la constante saldria verde—.
 *
 * <h2>Por que es una captura y no una invencion</h2>
 *
 * Estos son los bytes que devuelve la instalacion, medidos el 2026-09-06:
 *
 * <pre>
 * GET /rentas/api/v1/seguridad/sesion
 * {"usuarioId":2,"cuenta":"administrador","nombre":"Administrador del Sistema","ejercicioDeTrabajo":null}
 *
 * GET /rentas/api/v1/seguridad/sesion/municipalidad
 * {"id":9,"ubigeo":"200105","nombre":"Municipalidad Distrital de Catacaos","tipo":"DISTRITAL"}
 * </pre>
 *
 * Que `ejercicioDeTrabajo` valga `null` **no es una eleccion de este archivo**: es lo que
 * contesta el backend, y es el caso que el AC8 obliga a no mentir. Una muestra con un `2026`
 * dentro habria dejado ese caso sin ejercitar en las pruebas del marco, que es el sitio donde
 * mas barato sale ejercitarlo.
 *
 * <h2>No lo importa ningun modulo de produccion, y se comprueba</h2>
 *
 * `verificaciones/camino-a-la-api.test.ts` recorre `src/` y exige que solo lo importen archivos
 * de prueba. Sin esa guarda, esto acabaria siendo un respaldo: un `sesion ?? SESION_MEDIDA` en
 * cualquier sitio devolveria la cabecera constante que I-1 vino a quitar —y #356 otra vez—, y esta
 * vez con una constante que ademas parece medida. Fuera de `src/` lo importa tambien la siembra de
 * desarrollo de #114 —desde #356, para la barra de `yarn dev`—, que no viaja al paquete.
 */
export const SESION_MEDIDA: SesionDeLaVentanilla = {
  usuarioId: 2,
  cuenta: 'administrador',
  nombre: 'Administrador del Sistema',
  ejercicioDeTrabajo: null,
};

/** La municipalidad de esa misma sesion. Ver `SESION_MEDIDA`. */
export const MUNICIPALIDAD_MEDIDA: MunicipalidadDeLaSesion = {
  id: 9,
  ubigeo: '200105',
  nombre: 'Municipalidad Distrital de Catacaos',
  tipo: 'DISTRITAL',
};

/**
 * La misma sesion, con un ejercicio de trabajo fijado.
 *
 * Lo usan las pruebas que miden algo que **depende** de que haya ejercicio —el titulo de
 * «Valores», el subtitulo del panel, el selector de la barra—. El caso de `null` tiene sus
 * propias pruebas, y son las del AC8.
 */
export function conEjercicio(ejercicio: number): SesionDeLaVentanilla {
  return { ...SESION_MEDIDA, ejercicioDeTrabajo: ejercicio };
}
