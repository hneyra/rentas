import type {
  ContribuyenteDelPadron,
  DeudaEnCoactiva,
  ImporteConFecha,
  ObservadoDeLaCorrida,
  Paginado,
} from '../datos/lecturas.ts';

/**
 * La fila del padron, y **la consulta con que se le pide al backend** (I-4).
 *
 * <h2>El hallazgo que obliga a componerla, medido en F-5 y confirmado contra la instalacion</h2>
 *
 * El artboard construye cada fila de la lista sobre cinco cosas: nombre, documento, codigo,
 * **estado de cobranza** e **importe**. `GET /rentas/contribuyentes` publica ocho campos —`id`,
 * `codigo`, `tipoDocumento`, `numeroDocumento`, `tipoPersona`, `nombreRazonSocial`,
 * `condicionEspecial`, `activo`— y **ni el estado de cobranza ni el importe estan entre ellos**.
 * No es una carencia del proxy: es lo que declara `docs/50-api/formas-de-la-api.json`, generado
 * del tipo de retorno del controlador, y es lo que contesta la instalacion.
 *
 * Recorridas **las 181 operaciones** del contrato, ninguna publica, por contribuyente y en una
 * lista, el estado de cobranza con su deuda. Lo que si publican, y de ahi salen dos de los
 * cuatro chips del artboard:
 *
 *   · `GET /coactiva/deudas` — quien tiene expediente coactivo abierto, con su total y **la
 *     fecha a la que esta ese total** (regla 9). De ahi sale «En coactiva».
 *   · `GET /rentas/predial/corridas/{corridaId}/observados` — quien quedo fuera de la emision.
 *     De ahi sale «Observado».
 *
 * **«Con deuda» no lo contesta nadie**, y esta pantalla no se lo inventa.
 *
 * <h2>Lo que cambia con diez mil filas: la busqueda y el orden son del backend (AC3)</h2>
 *
 * Con las cinco filas del artboard, filtrar y ordenar aqui daba el mismo resultado que hacerlo
 * alla. Con **10 603 contribuyentes** medidos y `tamano=20`, no: filtrar sobre lo que cupo en la
 * pagina devuelve «ningun contribuyente coincide» para alguien que si existe — plausible,
 * incompleto y mudo, que es exactamente la forma de fallo que F-5 rechazo para el chip «Con
 * deuda». Asi que `filtrar` y `ordenar` ya no existen: lo que hay es {@link rutaDelPadron}, que
 * compone la consulta, y el backend la resuelve.
 */

/** El envoltorio de una lista servida, o `null` si todavia no llego. */
export type Ventana<T> = Paginado<T> | null;

export interface FilaDelPadron {
  readonly contribuyente: ContribuyenteDelPadron;
  /** El estado que se ensena en la insignia. Nunca vacio. */
  readonly estado: string;
  /** El importe con su fecha, cuando alguna operacion lo publica. */
  readonly importe: ImporteConFecha | null;
  /** El expediente coactivo, cuando lo hay. */
  readonly expediente: string | null;
  /** Por que quedo fuera de la emision, cuando lo esta. */
  readonly motivo: string | null;
}

/** Lo compuesto, con lo que se sabe de su completitud. */
export interface PadronCompuesto {
  readonly filas: readonly FilaDelPadron[];
  /**
   * Si las dos listas de estado llegaron ENTERAS.
   *
   * **Es la misma leccion que la busqueda, aplicada a la insignia.** El estado de cobranza sale
   * de dos operaciones que tambien vienen paginadas: si una trae 20 de 400, no aparecer en ella
   * NO significa no estar en coactiva — significa no estar en la primera pagina. Con eso, una
   * fila que si tiene expediente coactivo se dibujaria «Activo», que es una afirmacion falsa
   * sobre una persona hecha por omision. Cuando esto es `false` la pantalla lo dice, en vez de
   * ensenar la insignia como si fuera segura.
   *
   * Medido contra la instalacion el 2026-09-07: las dos contestan **200 con lista vacia** en las
   * dos municipalidades, asi que hoy es `true` y las insignias son exactas. Que lo sean por el
   * dato y no por suerte es lo que esta bandera existe para distinguir.
   */
  readonly estadoCompleto: boolean;
}

/** El estado de quien no aparece ni en coactiva ni entre los observados. */
function estadoDelPadron(contribuyente: ContribuyenteDelPadron): string {
  return contribuyente.activo ? 'Activo' : 'De baja';
}

/** Si esa ventana trae la lista entera. Sin ventana no se puede afirmar nada. */
function estaEntera<T>(ventana: Ventana<T>): boolean {
  return ventana !== null && ventana.contenido.length >= ventana.totalElementos;
}

/** Une las tres respuestas en una fila por contribuyente, en el orden en que llego el padron. */
export function componerPadron(
  padron: readonly ContribuyenteDelPadron[],
  coactiva: Ventana<DeudaEnCoactiva>,
  observados: Ventana<ObservadoDeLaCorrida>,
): PadronCompuesto {
  const enCobranza = coactiva?.contenido ?? [];
  const fueraDeLaEmision = observados?.contenido ?? [];

  const filas = padron.map((contribuyente) => {
    const suExpediente = enCobranza.find((uno) => uno.codContribuyente === contribuyente.codigo);
    const observado = fueraDeLaEmision.find(
      (uno) => uno.codContribuyente === contribuyente.codigo,
    );

    if (suExpediente !== undefined) {
      return {
        contribuyente,
        estado: suExpediente.estado,
        importe: { importe: suExpediente.totalS, actualizadoA: suExpediente.aLaFecha },
        expediente: suExpediente.expediente,
        motivo: observado?.motivo ?? null,
      };
    }

    return {
      contribuyente,
      estado: observado === undefined ? estadoDelPadron(contribuyente) : 'Observado',
      importe: null,
      expediente: null,
      motivo: observado?.motivo ?? null,
    };
  });

  return { filas, estadoCompleto: estaEntera(coactiva) && estaEntera(observados) };
}

// ── La consulta que el backend resuelve ─────────────────────────────────────────────────────

/**
 * Un criterio que `GET /rentas/contribuyentes` **admite**, con el parametro que lo lleva.
 *
 * Los cuatro son los que declara `ContribuyenteController.buscar`: `codigo`,
 * `nombreRazonSocial`, `dNI` y `rUC`. Los dos ultimos van con la mayuscula corrida, y no es un
 * descuido de este archivo: es el nombre que el backend lee, y escribirlo «bien» aqui haria que
 * la busqueda por documento no filtrara nada y devolviera el padron entero — diez mil filas
 * presentadas como el resultado de buscar un DNI.
 */
export interface CriterioDelPadron {
  /** Lo que se lee en el selector. */
  readonly rotulo: string;
  /** El parametro de consulta, tal como lo lee el backend. */
  readonly parametro: string;
  /** Lo que se lee dentro de la caja mientras esta vacia. */
  readonly ayuda: string;
  /**
   * Si el backend compara por igualdad. `false` es aproximacion por trigramas.
   *
   * Medido: `?codigo=000000000` sobre un padron cuyos codigos empiezan todos por ceros devuelve
   * **0**, porque el SQL es `codigo_contribuyente = :codigo`. Y `?nombreRazonSocial=sulon
   * vilchez` —con una ele de menos— devuelve **74**, porque es `similarity(...) >= umbral`.
   */
  readonly exacto: boolean;
}

/**
 * Los cuatro criterios que la operacion admite, y ninguno mas (AC3).
 *
 * <h2>Por que hay un selector y no una sola caja</h2>
 *
 * Porque los cuatro parametros **se combinan con Y** (`CriterioDeBusqueda`, en el dominio del
 * backend): mandar lo tecleado a los cuatro a la vez pediria un contribuyente cuyo codigo, cuyo
 * nombre, cuyo DNI y cuyo RUC fueran todos la misma cadena, y eso no existe. Y adivinar cual es
 * por la pinta de lo tecleado **tampoco se puede**: un codigo de contribuyente tiene once
 * digitos —«00000000008»— y un RUC tambien —«20525118447»—, asi que once digitos son ambiguos y
 * elegir por el usuario mandaria parte de las busquedas al parametro equivocado, devolviendo
 * cero sin decir por que.
 *
 * <h2>Lo que la caja del artboard promete y la operacion NO admite</h2>
 *
 * El artboard rotula «Nombre, DNI, RUC o código», y el port de F-5 buscaba ademas por **tipo de
 * persona** y por **tipo de documento** sobre las filas cargadas. Ninguno de los dos es un
 * criterio de esta operacion, asi que **no se ofrecen**. Y el **carne de extranjeria** tampoco:
 * el controlador solo publica `dNI` y `rUC`, de modo que un carne no se puede buscar por aqui —
 * es el mismo hueco que la compuerta del alta declara en `Expediente.tsx`.
 */
export const CRITERIOS: readonly CriterioDelPadron[] = [
  {
    rotulo: 'Nombre',
    parametro: 'nombreRazonSocial',
    ayuda: 'Apellidos y nombres, o razón social',
    exacto: false,
  },
  { rotulo: 'Código', parametro: 'codigo', ayuda: 'Código completo, con sus ceros', exacto: true },
  { rotulo: 'DNI', parametro: 'dNI', ayuda: 'Los 8 dígitos del DNI', exacto: true },
  { rotulo: 'RUC', parametro: 'rUC', ayuda: 'Los 11 dígitos del RUC', exacto: true },
];

/**
 * Los dos ordenes que el backend admite para esta lista, con el campo que los pide.
 *
 * **«Deuda» ya no esta, y no es una simplificacion: es que no se puede pedir.** La lista blanca
 * de `ContribuyenteRepositoryJdbc` son `codigo_contribuyente`, `nombre_razon_social`,
 * `numero_documento` e `id`, y ninguno es la deuda — porque esta operacion no publica la deuda.
 * Medido: `?ordenarPor=deuda` contesta **422 `ORDEN_NO_ADMITIDO`**, «Campo pedido: deuda».
 * Ordenar aqui las veinte filas de la pagina por un importe que solo tienen las que salen en
 * coactiva pondria delante «al que mas debe» **de esta pagina**, que sobre 5 302 paginas no
 * quiere decir nada.
 */
export const ORDENES_DEL_PADRON: readonly { readonly rotulo: string; readonly campo: string }[] = [
  { rotulo: 'Código', campo: 'codigoContribuyente' },
  { rotulo: 'Nombre', campo: 'nombreRazonSocial' },
];

/** Cuantas filas se piden por pagina. Es el tamano por omision del backend. */
export const TAMANO_DE_PAGINA = 20;

/** Lo que la pantalla quiere del padron. */
export interface ConsultaDelPadron {
  /** El rotulo del criterio elegido, de {@link CRITERIOS}. */
  readonly criterio: string;
  /** Lo tecleado. Vacio pide el padron entero. */
  readonly texto: string;
  /** El rotulo del orden, de {@link ORDENES_DEL_PADRON}. */
  readonly orden: string;
  /** Contada desde 0, como la cuenta el backend. */
  readonly pagina: number;
}

/** Un parametro de consulta ya codificado, o nada. */
function parametro(nombre: string, valor: string): readonly string[] {
  return valor === '' ? [] : [`${encodeURIComponent(nombre)}=${encodeURIComponent(valor)}`];
}

/** El criterio de ese rotulo. El primero si el rotulo no es de ninguno. */
export function criterioDe(rotulo: string): CriterioDelPadron {
  const criterio = CRITERIOS.find((uno) => uno.rotulo === rotulo) ?? CRITERIOS[0];
  if (criterio === undefined) {
    throw new Error('El padrón no declara ningún criterio de búsqueda.');
  }
  return criterio;
}

/**
 * La ruta con la que se le pide al backend esa consulta.
 *
 * **Es una funcion pura y por eso se prueba sin montar nada**: lo que decide si la busqueda la
 * resuelve el servidor es que el criterio viaje en la URL, y eso se ve aqui. Que la pantalla la
 * use lo comprueba `Contribuyentes.test.tsx` mirando lo que salio por el cable.
 *
 * El orden **no se manda cuando se busca por nombre**: el backend ordena por parecido en ese
 * caso (`CriterioDeBusqueda.ordenaPorParecido()`), y pedirle ademas un `ordenarPor` seria
 * pedirle que deshaga el unico orden que hace util una busqueda aproximada — el mejor parecido
 * dejaria de salir primero.
 */
export function rutaDelPadron(base: string, consulta: ConsultaDelPadron): string {
  const criterio = criterioDe(consulta.criterio);
  const texto = consulta.texto.trim();
  const orden = ORDENES_DEL_PADRON.find((uno) => uno.rotulo === consulta.orden);
  const porParecido = texto !== '' && !criterio.exacto;

  const partes = [
    ...parametro(criterio.parametro, texto),
    ...parametro('pagina', String(consulta.pagina)),
    ...parametro('tamano', String(TAMANO_DE_PAGINA)),
    ...(orden === undefined || porParecido ? [] : parametro('ordenarPor', orden.campo)),
  ];
  return `${base}?${partes.join('&')}`;
}

/**
 * La ruta que pregunta si ese documento ya esta en el padron, o `null` si no se puede preguntar.
 *
 * Devuelve `null` para el carne de extranjeria **a proposito**: la operacion publica `dNI` y
 * `rUC` y ningun parametro para los demas tipos, asi que la unica manera de «comprobarlo» seria
 * traerse el padron y mirar — sobre 10 603 filas, mirar la primera pagina y decir «libre».
 */
export function rutaDelDocumento(base: string, tipo: string, numero: string): string | null {
  const criterio = CRITERIOS.find((uno) => uno.rotulo === tipo);
  return criterio === undefined || numero === ''
    ? null
    : `${base}?${parametro(criterio.parametro, numero).join('&')}`;
}
