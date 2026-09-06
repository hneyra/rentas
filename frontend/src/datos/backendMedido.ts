import type {
  ContribuyenteDelPadron,
  CorridaDelPredial,
  FichaDelContribuyente,
  Paginado,
} from './lecturas.ts';

/**
 * Lo que la instalacion contesta a las cinco lecturas que I-4 enciende, **copiado de `curl`**.
 *
 * <h2>Para que existe, y por que no es una invencion mas</h2>
 *
 * Las pruebas de esta seccion tienen que ejercer el camino de verdad —`solicitar()`, el proxy
 * dejando pasar lo de `YA_SERVIDAS`, el envoltorio de paginacion leido tal cual— sin depender de
 * que haya un backend levantado. Eso pide un doble; lo que decide si el doble vale es **de donde
 * salen sus respuestas**. Estas salen de la instalacion, medidas el 2026-09-07 con la cuenta
 * `administrador` (municipalidad 9, la que tiene la escala):
 *
 * <pre>
 * curl -H "Authorization: Bearer $T" '…/rentas/contribuyentes?pagina=0&tamano=20'
 * curl -H "Authorization: Bearer $T" '…/rentas/contribuyentes?nombreRazonSocial=sulon%20vilchez'
 * curl -H "Authorization: Bearer $T" '…/coactiva/deudas'
 * </pre>
 *
 * De cada respuesta se guardan **las cinco primeras filas y el envoltorio entero**. El
 * envoltorio no se recorta porque es justo lo que se esta probando: `totalElementos: 10603` con
 * `contenido` de cinco es lo que obliga a que la pantalla no cuente las filas que tiene.
 *
 * <h2>La respuesta de la busqueda es la que hace la prueba</h2>
 *
 * `?nombreRazonSocial=sulon vilchez` —con una ele de menos— devuelve **74 elementos**, y los
 * tres primeros son «VILCHEZ SULLON-LUIS», «VILCHEZ SULLON-MILTON» y «VILCHEZ SULLON-JULIO».
 * **Ninguno contiene la cadena tecleada.** Esa es la propiedad, y no una eleccion de este
 * archivo: un filtro del cliente sobre lo que cupo en la pagina devolveria **cero** para esa
 * busqueda, asi que una pantalla que ensene esos tres nombres esta ensenando lo que contesto el
 * servidor y no lo que ella filtro. Vale para las dos direcciones a la vez.
 *
 * <h2>Solo lo importan las pruebas, y hay una guarda</h2>
 *
 * Como sus dos hermanos `marco/sesionMedida.ts` y `marco/seguridadMedida.ts`: `verificaciones/camino-a-la-api.test.ts` comprueba que ninguna
 * fuente de produccion lo importe. Sin esa guarda, esto acabaria siendo el respaldo que hace que
 * una pantalla ensene contribuyentes de Catacaos con el token de cualquier otra municipalidad.
 */

/** Cuantos contribuyentes tiene el padron de la municipalidad 9. */
export const TOTAL_DEL_PADRON = 10603;

/** El tamano de pagina con que se midio, que es el de omision del backend. */
export const TAMANO_MEDIDO = 20;

/** `?pagina=0&tamano=20`, las cinco primeras. */
export const PAGINA_0_FILAS: readonly ContribuyenteDelPadron[] = [
  { id: 17, codigo: '00000000008', tipoDocumento: 'DNI', numeroDocumento: '29614026', tipoPersona: 'NATURAL', nombreRazonSocial: 'SULLON VILCHEZ-JOSE RAUL', condicionEspecial: null, activo: true },
  { id: 18, codigo: '00000000023', tipoDocumento: 'DNI', numeroDocumento: '02716094', tipoPersona: 'NATURAL', nombreRazonSocial: 'YPANAQUE SULLON-MANUEL DE LOS REYES', condicionEspecial: null, activo: true },
  { id: 19, codigo: '00000000040', tipoDocumento: 'DNI', numeroDocumento: '02701021', tipoPersona: 'NATURAL', nombreRazonSocial: 'SANDOVAL MAYANGA-JOSE GUILLERMO', condicionEspecial: null, activo: true },
  { id: 20, codigo: '00000000050', tipoDocumento: 'DNI', numeroDocumento: '03625254', tipoPersona: 'NATURAL', nombreRazonSocial: 'ROMAN GARCIA-PABLO', condicionEspecial: null, activo: true },
  { id: 21, codigo: '00000000057', tipoDocumento: 'DNI', numeroDocumento: '02707368', tipoPersona: 'NATURAL', nombreRazonSocial: 'QUEZADA POICON-ERNESTO', condicionEspecial: null, activo: true },
];

/** `?pagina=1&tamano=20`, las cinco primeras. **Ninguna coincide con las de la pagina 0.** */
export const PAGINA_1_FILAS: readonly ContribuyenteDelPadron[] = [
  { id: 37, codigo: '00000000125', tipoDocumento: 'DNI', numeroDocumento: '02699922', tipoPersona: 'NATURAL', nombreRazonSocial: 'CORTEZ DE IPANAQUE-MARIA ANTONIETA', condicionEspecial: null, activo: true },
  { id: 38, codigo: '00000000130', tipoDocumento: 'DNI', numeroDocumento: '02819697', tipoPersona: 'NATURAL', nombreRazonSocial: 'YOVERA CHIROQUE-ADELAIDA', condicionEspecial: null, activo: true },
  { id: 39, codigo: '00000000134', tipoDocumento: 'DNI', numeroDocumento: '02704678', tipoPersona: 'NATURAL', nombreRazonSocial: 'SOSA ZAPATA-PEDRO PABLO', condicionEspecial: null, activo: true },
  { id: 40, codigo: '00000000148', tipoDocumento: 'DNI', numeroDocumento: '02698229', tipoPersona: 'NATURAL', nombreRazonSocial: 'INGA MENDOZA-JOSE MERCEDES', condicionEspecial: null, activo: true },
  { id: 41, codigo: '00000000153', tipoDocumento: 'DNI', numeroDocumento: '02703724', tipoPersona: 'NATURAL', nombreRazonSocial: 'OJEDA CRUZ-LILIAN IRENE', condicionEspecial: null, activo: true },
];

/** Lo tecleado en la busqueda que devuelve nombres que NO lo contienen. */
export const BUSQUEDA_APROXIMADA = 'sulon vilchez';

/** Cuantos devuelve esa busqueda, segun el backend. */
export const TOTAL_DE_LA_BUSQUEDA = 74;

/** `?nombreRazonSocial=sulon vilchez`, las cinco primeras. Ordenadas por parecido. */
export const POR_NOMBRE_APROXIMADO_FILAS: readonly ContribuyenteDelPadron[] = [
  { id: 2383, codigo: '00000008137', tipoDocumento: 'DNI', numeroDocumento: '02799621', tipoPersona: 'NATURAL', nombreRazonSocial: 'VILCHEZ SULLON-LUIS', condicionEspecial: null, activo: true },
  { id: 1202, codigo: '00000003918', tipoDocumento: 'DNI', numeroDocumento: '90234563', tipoPersona: 'NATURAL', nombreRazonSocial: 'VILCHEZ SULLON-MILTON', condicionEspecial: null, activo: true },
  { id: 3260, codigo: '00000011032', tipoDocumento: 'DNI', numeroDocumento: '02875116', tipoPersona: 'NATURAL', nombreRazonSocial: 'VILCHEZ SULLON-JULIO', condicionEspecial: null, activo: true },
  { id: 17, codigo: '00000000008', tipoDocumento: 'DNI', numeroDocumento: '29614026', tipoPersona: 'NATURAL', nombreRazonSocial: 'SULLON VILCHEZ-JOSE RAUL', condicionEspecial: null, activo: true },
  { id: 3570, codigo: '00000011971', tipoDocumento: 'DNI', numeroDocumento: '02862245', tipoPersona: 'NATURAL', nombreRazonSocial: 'SULLON VILCHEZ CESAR-ANTONIO', condicionEspecial: null, activo: true },
];

/** `?ordenarPor=nombreRazonSocial`, las cinco primeras. Alfabetico y no por codigo. */
export const ORDENADO_POR_NOMBRE_FILAS: readonly ContribuyenteDelPadron[] = [
  { id: 5859, codigo: '00000019447', tipoDocumento: 'DNI', numeroDocumento: '05643564', tipoPersona: 'NATURAL', nombreRazonSocial: '(APODERADA)ADRIANA ANTHON YCARLOS MARTINEZ ICANAQUE-MARLENY ICANAQUE TRELLES', condicionEspecial: null, activo: true },
  { id: 8619, codigo: '00000047704', tipoDocumento: 'RUC', numeroDocumento: '20602546391', tipoPersona: 'JURIDICA', nombreRazonSocial: '3D PHARMACEUTICAL SAC', condicionEspecial: null, activo: true },
  { id: 8728, codigo: '00000048697', tipoDocumento: 'DNI', numeroDocumento: '03123748', tipoPersona: 'NATURAL', nombreRazonSocial: 'ABAD JIMENEZ-DALIA FRESIA', condicionEspecial: null, activo: true },
  { id: 3994, codigo: '00000013032', tipoDocumento: 'DNI', numeroDocumento: '05640043', tipoPersona: 'NATURAL', nombreRazonSocial: 'ABAD MERINO-ISMAEL DAVID', condicionEspecial: null, activo: true },
  { id: 3414, codigo: '00000011377', tipoDocumento: 'DNI', numeroDocumento: '02771025', tipoPersona: 'NATURAL', nombreRazonSocial: 'ABAD QUINDE HOMERO', condicionEspecial: null, activo: true },
];

/**
 * La ficha de `SULLON VILCHEZ-JOSE RAUL`, tal cual la contesta `GET .../17/ficha`.
 *
 * **Tres de sus campos llegan nulos y el port tuvo que admitirlo**: `fechaNacimiento` y
 * `estadoCivil` —la tabla los tiene vacios en el origen— y `domicilioFiscal`, porque la tabla
 * `domicilio` esta **vacia** en el volcado de la marcha blanca. El contrato los declara con su
 * tipo, no con su presencia; quien lee tiene que admitir que no vengan.
 */
export const FICHA_MEDIDA: FichaDelContribuyente = {
  contribuyente: PAGINA_0_FILAS[0] ?? {
    id: 17,
    codigo: '00000000008',
    tipoDocumento: 'DNI',
    numeroDocumento: '29614026',
    tipoPersona: 'NATURAL',
    nombreRazonSocial: 'SULLON VILCHEZ-JOSE RAUL',
    condicionEspecial: null,
    activo: true,
  },
  datosPersonales: { fechaNacimiento: null, estadoCivil: null, conyugeId: null },
  aLaFecha: '2026-09-07',
  domicilioFiscal: null,
  contactos: [],
};

/**
 * La ultima corrida de la municipalidad 9, tal cual.
 *
 * Trae `sector`, `simulacion` y `conjunto`, que el contrato declara y el port de F-6 no leia.
 */
export const CORRIDA_MEDIDA: CorridaDelPredial = {
  id: 20,
  ejercicio: '2026',
  alcance: 'TODOS',
  sector: null,
  simulacion: true,
  conjunto: '',
  fechaCalculo: '2026-09-01',
  observados: 0,
  etapas: [
    { etapa: 'Padrón leído', registros: 0, monto: '', observados: 0, estado: 'OK' },
    { etapa: 'Simulados', registros: 0, monto: '0.00', observados: 0, estado: 'OK' },
  ],
};

/** Un envoltorio vacio, que es lo que contestan coactiva y los observados. Y no es un fallo. */
export function listaVacia<T>(): Paginado<T> {
  return { contenido: [], pagina: 0, tamano: TAMANO_MEDIDO, totalElementos: 0, totalPaginas: 0, hayMas: false };
}

/** Envuelve unas filas con el envoltorio que el backend puso a esa respuesta. */
export function envolver<T>(
  contenido: readonly T[],
  pagina: number,
  totalElementos: number,
): Paginado<T> {
  const totalPaginas = Math.ceil(totalElementos / TAMANO_MEDIDO);
  return {
    contenido,
    pagina,
    tamano: TAMANO_MEDIDO,
    totalElementos,
    totalPaginas,
    hayMas: pagina + 1 < totalPaginas,
  };
}

/**
 * Lo que la instalacion contestaria a esa URL, o `null` si no es una de las lecturas medidas.
 *
 * **Mira la cadena de consulta, y ahi esta lo que distingue esta prueba de una tramposa**: el
 * criterio, la pagina y el orden son parametros que el backend LEE, asi que un doble que los
 * ignorara dejaria pasar una pantalla que no los manda. Y lo que devuelve para cada uno es la
 * respuesta que se midio con ese mismo parametro puesto.
 */
export function contestaLaInstalacion(url: string): unknown | null {
  const donde = new URL(url, 'http://localhost');
  const ruta = donde.pathname.replace('/rentas/api/v1', '');
  const q = donde.searchParams;

  if (ruta === '/coactiva/deudas') return listaVacia();
  if (ruta === '/rentas/beneficios') return listaVacia();
  if (/^\/rentas\/predial\/corridas\/\d+\/observados$/.test(ruta)) return listaVacia();
  if (ruta === '/rentas/predial/corridas/ultima') return CORRIDA_MEDIDA;
  if (/^\/rentas\/contribuyentes\/\d+\/ficha$/.test(ruta)) return FICHA_MEDIDA;

  if (ruta !== '/rentas/contribuyentes') return null;

  const nombre = q.get('nombreRazonSocial');
  if (nombre !== null) {
    return envolver(POR_NOMBRE_APROXIMADO_FILAS, 0, TOTAL_DE_LA_BUSQUEDA);
  }
  const codigo = q.get('codigo');
  if (codigo !== null) {
    const suyo = [...PAGINA_0_FILAS, ...PAGINA_1_FILAS].filter((uno) => uno.codigo === codigo);
    return envolver(suyo, 0, suyo.length);
  }
  const documento = q.get('dNI') ?? q.get('rUC');
  if (documento !== null) {
    const suyo = [...PAGINA_0_FILAS, ...PAGINA_1_FILAS].filter(
      (uno) => uno.numeroDocumento === documento,
    );
    return envolver(suyo, 0, suyo.length);
  }
  if (q.get('ordenarPor') === 'nombreRazonSocial') {
    return envolver(ORDENADO_POR_NOMBRE_FILAS, 0, TOTAL_DEL_PADRON);
  }
  const pagina = Number(q.get('pagina') ?? '0');
  return envolver(pagina === 0 ? PAGINA_0_FILAS : PAGINA_1_FILAS, pagina, TOTAL_DEL_PADRON);
}
