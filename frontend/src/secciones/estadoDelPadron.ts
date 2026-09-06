/**
 * Lo que la seccion «Contribuyentes» recuerda, y **por que lo recuerda el lienzo y no ella**.
 *
 * El marco desmonta la seccion al cambiar de pestana. Si este estado viviera dentro, escribir
 * media alta, mirar el panel y volver dejaria el formulario en blanco **con el asterisco
 * puesto**: la pestana diria que hay cambios sin guardar y no habria ninguno que guardar, y el
 * dialogo de «Descartar y cerrar» preguntaria por algo que ya no existe.
 *
 * Vive donde vive el asterisco. Es la misma decision que F-3 tomo con la observacion del hueco.
 */
export interface EstadoDelPadron {
  /** Lo tecleado en el buscador. */
  readonly q: string;
  /** El chip activo: «Todos», «Con deuda», «En coactiva» u «Observado». */
  readonly chip: string;
  /** El orden: «Código», «Deuda» o «Nombre». */
  readonly orden: string;
  /** El codigo del contribuyente abierto, `NUEVO` para el alta, o `null`. */
  readonly elegido: string | null;
  /** La seccion del expediente que se ve, de las seis. */
  readonly paso: number;
  /** Lo tecleado en el formulario, por clave de campo. */
  readonly vals: Readonly<Record<string, string>>;
  /** Si ya se intento crear: es lo que enciende el realce rojo de los obligatorios vacios. */
  readonly intento: boolean;
}

/** El valor de `elegido` que abre el alta en vez de un expediente. */
export const NUEVO = 'nuevo';

/** Los tres ordenes del artboard. */
export const ORDENES: readonly string[] = ['Código', 'Deuda', 'Nombre'];

/** Los cuatro chips del artboard, en su orden. */
export const CHIPS: readonly string[] = ['Todos', 'Con deuda', 'En coactiva', 'Observado'];

/** Como empieza la seccion: sin filtro, con «Todos» y sin nadie abierto. */
export const PADRON_AL_EMPEZAR: EstadoDelPadron = {
  q: '',
  chip: 'Todos',
  orden: 'Código',
  elegido: null,
  paso: 0,
  vals: {},
  intento: false,
};
