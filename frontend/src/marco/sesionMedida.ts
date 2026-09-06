import type { MunicipalidadDeLaSesion, SesionDeLaVentanilla } from '../datos/lecturas.ts';

/**
 * La sesion tal como la contesta la instalacion, **copiada de un `curl` y no inventada**.
 *
 * <h2>Para que existe</h2>
 *
 * Desde I-1 el marco no se puede montar sin decir quien esta dentro: `MarcoProps` exige `sesion`
 * y `municipalidad` sin respaldo, que es lo que impide que vuelva a colarse un «J. Cárdenas
 * Vega» por omision. Eso deja a las pruebas del marco —que son del MARCO y no de la identidad—
 * teniendo que decirlo cuarenta y cuatro veces. Con cuarenta y cuatro literales sueltos, el dia
 * que `GET /seguridad/sesion` cambie de forma habria cuarenta y cuatro sitios que corregir y
 * ninguno que lo dijera.
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
 * de prueba. Sin esa guarda, esto acabaria siendo el respaldo que `MarcoProps` existe para
 * prohibir: un `sesion ?? SESION_MEDIDA` en cualquier sitio devolveria la cabecera constante que
 * I-1 vino a quitar, y esta vez con una constante que ademas parece medida.
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
