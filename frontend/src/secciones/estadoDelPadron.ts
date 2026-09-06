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
  /**
   * Con que criterio se busca lo tecleado: «Nombre», «Código», «DNI» o «RUC».
   *
   * Existe desde I-4 porque la busqueda la resuelve el backend, y el backend admite **cuatro
   * parametros que se combinan con Y**: una sola caja sin criterio no tiene a donde mandar lo
   * tecleado. El razonamiento entero, con lo que se descarto, esta en `padron.ts`.
   */
  readonly criterio: string;
  /** El chip activo: «Todos», «Con deuda», «En coactiva» u «Observado». */
  readonly chip: string;
  /** El orden: «Código» o «Nombre». */
  readonly orden: string;
  /**
   * La pagina del padron que se esta mirando, contada desde 0 como la cuenta el backend.
   *
   * Vive aqui y no dentro de la seccion por el mismo motivo que lo demas: irse al panel desde la
   * pagina 27 y volver a la 1 sin haber pedido nada es perder el sitio sin avisar.
   */
  readonly pagina: number;
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

/** Los cuatro chips del artboard, en su orden. */
export const CHIPS: readonly string[] = ['Todos', 'Con deuda', 'En coactiva', 'Observado'];

/**
 * Como empieza la seccion: sin filtro, buscando por nombre, con «Todos» y sin nadie abierto.
 *
 * El criterio por omision es **«Nombre»** y no «Código», que es el orden por omision: quien
 * atiende en ventanilla llega con un nombre mal deletreado mucho mas a menudo que con un codigo
 * de once digitos, y ese es justo el criterio que el backend resuelve por aproximacion.
 */
export const PADRON_AL_EMPEZAR: EstadoDelPadron = {
  q: '',
  criterio: 'Nombre',
  chip: 'Todos',
  orden: 'Código',
  pagina: 0,
  elegido: null,
  paso: 0,
  vals: {},
  intento: false,
};
