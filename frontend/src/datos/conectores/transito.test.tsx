import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { coordenada } from '@kamayuk/ui';
import { PANTALLAS, pantallaDe } from '../../pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../../pantallas/arbol.ts';
import { PantallaDeRentas } from '../../pantallas/PantallaDeRentas.tsx';
import { useDatosDeLaHoja } from '../useDatosDeLaHoja.ts';
import { NO_PUBLICADO } from '../conectores.ts';
import { FUERA_DEL_DEPOSITO } from '../palabrasDeHueco.ts';
import type {
  ExpedienteDeLaPapeleta,
  InternamientoEnDeposito,
  Paginado,
  VehiculoServido,
  ResumenDePapeletas,
} from '../lecturas.ts';
import { TONO_SIN_RECONOCER, tonoDe } from '../../pantallas/tono.ts';
import { SIN_PLACA, TRA_PANEL, TRA_PAP, TRA_VEH } from './transito.ts';

/**
 * **Que `tra-pap` y `tra-veh` ensenen lo que LLEGO, y que lo que no llego lo DIGAN** (#180).
 *
 * <h2>Por que la mitad de este archivo monta la pantalla en vez de llamar a `repartir`</h2>
 *
 * Por lo mismo que en Licencias (#168): `repartir` se puede comprobar entero y la pantalla puede
 * seguir **pareciendo** conectada. La unica forma de distinguir «conectada» de «parece conectada»
 * es cambiar la respuesta del doble y mirar el DOM — con dos respuestas que no comparten una celda,
 * si se lee lo mismo es que no esta conectada.
 *
 * <h2>Y lo que este archivo comprueba y ninguno anterior podia</h2>
 *
 * Que una celda **sin dato lo diga**, y no con una raya muda: desde `kamayuk-lib`#87 el interprete
 * la marca con `data-celda-sin-dato` y le pone el motivo en el `title`. Antes de ese camino, una
 * columna que nadie publica y una columna rota se dibujaban exactamente igual.
 */

/** Una pagina del backend, con el envoltorio entero: lo que `pedirPagina` devuelve. */
function pagina<T>(contenido: readonly T[], totalElementos: number): Paginado<T> {
  return {
    contenido,
    pagina: 0,
    tamano: 20,
    totalElementos,
    totalPaginas: Math.ceil(totalElementos / 20) || 1,
    hayMas: totalElementos > contenido.length,
  };
}

/** Una fila del deposito, con los once campos que el contrato declara. */
function internado(campos: Partial<InternamientoEnDeposito> = {}): InternamientoEnDeposito {
  return {
    id: 1,
    placa: 'T2G-418',
    clase: 'AUTOMOVIL',
    papeleta: '0041182',
    deposito: 'DEPOSITO MUNICIPAL 1',
    fechaDeIngreso: '2026-07-18',
    fechaDeSalida: null,
    dias: 54,
    calculadoA: '2026-09-10',
    // Un estado que el backend PRODUCE: `EstadoDeInternamiento` es `INTERNADO`, `LIBERADO` o
    // `EN_ABANDONO`. Hasta #387 esto decia `EN_DEPOSITO`, que no existe — y con un estado que no
    // existe no hay forma de sembrar el caso que distingue, el vehiculo ya liberado.
    estado: 'INTERNADO',
    // El CONCEPTO del TUPA, no una tarifa. Ver `conectores/transito.ts`.
    tasaDeCustodia: 'TUPA-2.14 CUSTODIA DIARIA',
    acta: 'ACTA-2026-0311',
    ...campos,
  };
}

/** Una linea de resumen, con los catorce campos que el contrato declara. */
function lineaDelResumen(
  campos: Partial<ResumenDePapeletas['lineas'][number]> = {},
): ResumenDePapeletas['lineas'][number] {
  return {
    clave: '2026',
    descripcion: null,
    ano: 2026,
    cantidad: 8412,
    importe: '1542880.00',
    pagadas: 2118,
    importeDeLasPagadas: '388440.00',
    pendientes: 6294,
    importeDeLasPendientes: '1154440.00',
    enCoactiva: 388,
    importeEnCoactiva: '71148.00',
    conResolucionNotificada: 5884,
    conResolucionDeMulta: 388,
    actualizadoA: '2026-09-17',
    ...campos,
  };
}

/**
 * Un resumen de papeletas, con los siete campos que el contrato declara.
 *
 * Por omision **una sola linea**, que es lo que devuelve la peticion que el conector hace:
 * `?agrupadoPor=ANO` sin rango, o sea un ano natural agrupado por ano.
 */
function resumen(campos: Partial<ResumenDePapeletas> = {}): ResumenDePapeletas {
  return {
    agrupadoPor: 'ANO',
    desde: '2026-01-01',
    hasta: '2026-12-31',
    papeletas: 8412,
    importeTotal: '1542880.00',
    actualizadoA: '2026-09-17',
    lineas: [lineaDelResumen()],
    ...campos,
  };
}

/** Un vehiculo, con los doce campos que la ficha publica. */
function vehiculo(campos: Partial<VehiculoServido> = {}): VehiculoServido {
  return {
    id: 7,
    placa: 'T2G-418',
    contribuyenteId: 25673,
    marca: 'TOYOTA',
    modelo: 'YARIS',
    categoria: 'M1',
    anioFabricacion: 2014,
    anioInscripcion: 2015,
    numeroMotor: '2NZ-1188412',
    numeroSerie: 'JTDBT923771118841',
    estado: 'ACTIVO',
    historialDePlacas: [],
    ...campos,
  };
}

// ── Los dos expedientes de `tra-pap`, que no comparten una sola celda ────────────────────────

const EXPEDIENTE_A: ExpedienteDeLaPapeleta = {
  papeleta: '0041182',
  familia: 'TRANSITO',
  estado: 'NOTIFICADA',
  descargos: [],
  actos: [
    {
      clase: 'ACTA_INTERNAMIENTO',
      tipo: 'INGRESO',
      numero: 'ACTA-2026-0311',
      fecha: '2026-07-18',
      documentoId: 40,
      observacion: 'Vehiculo internado en el acto',
      estado: 'SIN_NOTIFICACION',
      acuses: [],
    },
    {
      clase: 'RESOLUCION_GERENCIA',
      tipo: 'SANCIONADORA',
      numero: 'RG-2026-0884',
      fecha: '2026-07-24',
      documentoId: 41,
      observacion: 'Emitida por el area de transito',
      estado: 'NOTIFICADO',
      acuses: [
        {
          intento: 1,
          fecha: '2026-07-26',
          modalidad: 'DOMICILIO',
          resultado: 'NO_ENCONTRADO',
          recibidoPor: null,
          acuse: null,
          exigibleDesde: null,
        },
        {
          intento: 2,
          fecha: '2026-07-30',
          modalidad: 'DOMICILIO',
          resultado: 'ENTREGADO',
          recibidoPor: 'QUIROGA RAMOS-ELEODORO',
          acuse: 'AC-2026-1188',
          exigibleDesde: '2026-07-31',
        },
      ],
    },
  ],
};

const EXPEDIENTE_B: ExpedienteDeLaPapeleta = {
  papeleta: '0055901',
  familia: 'TRANSITO',
  estado: 'CADUCADA',
  descargos: [],
  actos: [
    {
      clase: 'RESOLUCION_GERENCIA',
      tipo: 'ORDINARIA',
      numero: 'RG-2026-1902',
      fecha: '2026-08-02',
      documentoId: 77,
      observacion: 'Resuelve el descargo presentado',
      estado: 'SIN_DILIGENCIAR',
      acuses: [],
    },
  ],
};

// ── Lo que el ARTBOARD dibuja, y que por tanto no puede salir de una respuesta ──────────────

const FILA_DEL_ARTBOARD = {
  // Las cinco cifras que `RentasV8.dc.html` dibuja en `tra-panel`. Ninguna puede llegar al DOM
  // desde un resumen que no las trae: si aparecen, es que la pantalla las lleva dentro.
  'tra-panel': ['8,412', '5,884', '2,118', '1,842', '388'],
  'tra-pap': ['Levantamiento', 'Cedula 2026-0884', 'Conforme', 'Por vencer'],
  'tra-veh': ['V1H-882', 'M4J-118', 'Camioneta', 'Motocicleta'],
} as const;

function arnes() {
  // Un cliente por prueba: compartido, la respuesta de la primera se quedaria en la cache de la
  // segunda y las dos leerian lo mismo — que es justo lo que este archivo intenta distinguir.
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
}

/** La pantalla tal como la monta la aplicacion: su definicion, y lo que se sepa de sus datos. */
function PantallaConectada({
  clave,
  sujeto = null,
}: {
  readonly clave: ClaveDeHoja;
  readonly sujeto?: string | null;
}) {
  return <PantallaDeRentas definicion={pantallaDe(clave)} datos={useDatosDeLaHoja(clave, { sujeto, parametros: {} })} />;
}

/**
 * Lo que el doble contesta a una ruta: un cuerpo fijo, o uno que **depende de la peticion**.
 *
 * El segundo existe por #387: un doble que contesta lo mismo pida lo que pida no puede distinguir
 * «el primero» de «el vigente», porque para el `?sentido=` no significa nada.
 */
type Contestacion = unknown;
type ContestaSegunLaPeticion = (url: string) => unknown;

/** Sustituye `fetch` por un doble que contesta segun la RUTA, y dice a que se llamo. */
function contesta(porRuta: readonly (readonly [string, Contestacion | ContestaSegunLaPeticion])[]) {
  const pedidas: string[] = [];
  const doble = vi.fn<typeof fetch>((entrada) => {
    const url = String(entrada);
    pedidas.push(url);
    const casa = porRuta.find(([trozo]) => url.includes(trozo));
    const respuesta = casa?.[1];
    const cuerpo: unknown =
      typeof respuesta === 'function' ? (respuesta as ContestaSegunLaPeticion)(url) : respuesta;
    return Promise.resolve(
      new Response(JSON.stringify(cuerpo ?? {}), {
        status: casa === undefined ? 404 : 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
  });
  vi.stubGlobal('fetch', doble);
  return pedidas;
}

/** Monta la hoja con esas respuestas y espera a que deje de pedir. */
async function pintar(
  clave: ClaveDeHoja,
  porRuta: readonly (readonly [string, Contestacion | ContestaSegunLaPeticion])[],
  sujeto: string | null = null,
) {
  const pedidas = contesta(porRuta);
  const { container } = render(<PantallaConectada clave={clave} sujeto={sujeto} />, {
    wrapper: arnes(),
  });
  await waitFor(() => {
    expect(screen.queryByText(/pidiendo/i)).toBeNull();
  });
  return { container, pedidas };
}

/** Lo que `tra-panel` necesita del doble: una sola operacion. */
function comoTraPanel(cuerpo: ResumenDePapeletas) {
  return [['/transito/reportes/resumen-papeletas', cuerpo]] as const;
}

/** Lo que `tra-pap` necesita del doble: la relacion y el expediente de la primera. */
function comoTraPap(expediente: ExpedienteDeLaPapeleta) {
  return [
    ['/transito/papeletas?', pagina([{ numero: expediente.papeleta }], 1842)],
    ['/actos', expediente],
  ] as const;
}

/** Y lo que necesita `tra-veh`: la ficha, el deposito y los internamientos de la placa. */
function comoTraVeh(
  ficha: VehiculoServido,
  deposito: readonly InternamientoEnDeposito[],
  suyos: readonly InternamientoEnDeposito[],
) {
  return [
    // El acotado va primero: `includes('/transito/internamientos')` casa con los dos. Y contesta
    // como el backend —ordenado y cortado segun lo que se pidio—, no una lista fija (#387).
    ['/transito/internamientos?placa=', losDeLaPlaca(suyos)],
    ['/transito/internamientos', pagina(deposito, 188)],
    ['/rentas/vehiculos/', ficha],
  ] as const;
}

/**
 * **El doble de `GET /transito/internamientos?placa=` que respeta el orden que se le pide** (#387).
 *
 * Hace lo que hace el backend y nada mas: ordena por `ordenarPor` —el unico que esta lectura usa,
 * `fechaIngreso`, que es ademas el `ORDEN_POR_OMISION` de `InternamientosController`— en el
 * `sentido` pedido, **ascendente si no se pide ninguno** (`ParametrosDePaginacion`), y corta en
 * `tamano`. Con una lista fija, «el primero» y «el vigente» son la misma fila por construccion y
 * ninguna prueba puede separarlos: era el caso de todas las muestras hasta #387, que sembraban UN
 * internamiento por placa.
 *
 * Da igual en que orden lleguen `internamientos`: los ordena el doble, igual que la base.
 */
function losDeLaPlaca(internamientos: readonly InternamientoEnDeposito[]): ContestaSegunLaPeticion {
  return (url) => {
    const pedido = new URL(url, 'http://doble.invalid').searchParams;
    const campo = pedido.get('ordenarPor') ?? 'fechaIngreso';
    if (campo !== 'fechaIngreso') {
      throw new Error(`El doble solo sabe ordenar por fechaIngreso, y se pidio «${campo}»`);
    }
    const signo = (pedido.get('sentido') ?? 'ASCENDENTE') === 'DESCENDENTE' ? -1 : 1;
    const ordenados = [...internamientos].sort(
      (a, b) => signo * a.fechaDeIngreso.localeCompare(b.fechaDeIngreso),
    );
    const tamano = Number(pedido.get('tamano') ?? '20');
    return pagina(ordenados.slice(0, tamano), internamientos.length);
  };
}

/** Lo que llevan dentro los campos que se escriben: un `<input>` no pone su valor en el DOM. */
function valoresEscritos(container: HTMLElement): readonly string[] {
  return [...container.querySelectorAll('input')].map((campo) => campo.value);
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('`tra-panel` — los cinco recuentos del ejercicio (#184, #222, #243)', () => {
  const reparto = TRA_PANEL.repartir(resumen() as never);

  it('el EJERCICIO sale de `desde` y no de la primera opcion del desplegable', () => {
    // Es el rango que la respuesta DICE haber contado (regla 9, RNF-075). Dejarlo en «2026»
    // porque es lo que el artboard escribio primero seria afirmarlo sin saberlo: con la misma
    // pantalla contra un backend puesto en 2025, la cifra seria de 2025 y el rotulo diria 2026.
    expect(reparto.valores.get(coordenada(0, 0))).toBe('2026');
    const otro = TRA_PANEL.repartir(
      resumen({ desde: '2024-01-01', hasta: '2024-12-31' }) as never,
    );
    expect(otro.valores.get(coordenada(0, 0))).toBe('2024');
  });

  it('los TRES recuentos que la operacion publica como HECHO salen de ella, uno a uno', () => {
    // «Levantadas» es el total del RESUMEN —calculado en el servidor—, y los otros dos son de la
    // linea del ejercicio. **Eran cuatro hasta #243**: «Canceladas» dibujaba `pagadas`, que cuenta
    // `p.estado = 'PAGADA'` y que nadie escribe.
    expect(reparto.valores.get(coordenada(0, 1))).toBe('8412');
    expect(reparto.valores.get(coordenada(0, 2))).toBe('5884');
    expect(reparto.valores.get(coordenada(0, 5))).toBe('388');
  });

  it('«Con resolucion de multa» es `conResolucionDeMulta`, y NO `enCoactiva` (#243)', () => {
    // El defecto que #243 mide: `EstadoDePapeleta.COACTIVA` no lo escribe nadie —el unico
    // `UPDATE papeleta` de `src/main` es `SET numero`—, asi que `enCoactiva` es cero para siempre.
    // Lo que consta es que la resolucion de multa este emitida. Los dos campos llegan con valores
    // DISTINTOS a proposito: con los dos en 388 esta prueba no distinguiria cual se leyo.
    const otro = TRA_PANEL.repartir(
      resumen({
        lineas: [lineaDelResumen({ enCoactiva: 7, conResolucionDeMulta: 441 })],
      }) as never,
    );
    expect(otro.valores.get(coordenada(0, 5))).toBe('441');
    expect([...otro.valores.values()], 'se leyo el estado que nadie escribe').not.toContain('7');
  });

  it('«Canceladas» dice «no publicado» y NO el `pagadas` que sigue llegando (#243)', () => {
    // La cifra del artboard es 2 118 y el campo llega con ella, asi que la tentacion cabe entera.
    // Es cero para siempre en una instalacion nueva —nadie escribe `PAGADA`— y no se deriva de
    // nada: el libro no tiene por donde cruzar a una papeleta. Se publica el campo y NO se dibuja.
    expect(reparto.noPublicados.get(coordenada(0, 3))).toBe(NO_PUBLICADO);
    expect(reparto.valores.has(coordenada(0, 3))).toBe(false);
    expect([...reparto.valores.values()]).not.toContain('2118');
  });

  it('«Con multa notificada» es `conResolucionNotificada`, y NO el estado de la papeleta', () => {
    // El defecto que #222 midio: `EstadoDePapeleta.NOTIFICADA` no lo escribe nadie —el unico
    // `UPDATE papeleta` de `src/main` es `SET numero`—, asi que un recuento sobre ese estado seria
    // cero para siempre. Lo que llega a esta celda es la diligencia de la RESOLUCION, y el rotulo
    // del artboard cambio para decirlo. Con otro valor en el campo, la celda cambia con el: si
    // alguien la recompusiera de `papeletas` y `pendientes`, esto seguiria diciendo 5 884.
    const otro = TRA_PANEL.repartir(
      resumen({ lineas: [lineaDelResumen({ conResolucionNotificada: 17 })] }) as never,
    );
    expect(otro.valores.get(coordenada(0, 2))).toBe('17');
  });

  it('«Levantadas» es `papeletas` y NO la suma de las lineas hecha aqui', () => {
    // El total va calculado en el servidor: recomponerlo en el cliente es como se acaba
    // mostrando una cifra que no coincide con el papel exportado. Con un `papeletas` que no
    // cuadre con las lineas, lo que se dibuja es el del servidor.
    const raro = TRA_PANEL.repartir(
      resumen({ papeletas: 9999, lineas: [lineaDelResumen({ cantidad: 1 })] }) as never,
    );
    expect(raro.valores.get(coordenada(0, 1))).toBe('9999');
  });

  it('los DOS que nadie publica lo dicen, y no con un cero', () => {
    // «Caducadas sin notificar» ni siquiera es un estado: necesita el acto de la notificacion y
    // ademas un PLAZO contra el que juzgarla, que es valor normativo. #222 conto las nueve filas
    // `PLAZO` del corpus una por una y ninguna es esa —tres de prescripcion, dos de su inicio,
    // una del REC-1 y tres del plazo de RECLAMACION, que corre DESDE la notificacion—. Regla 5:
    // esta mitad esta bloqueada, y decirlo es la respuesta.
    expect(reparto.noPublicados.get(coordenada(0, 4))).toBe(NO_PUBLICADO);
    expect(reparto.valores.has(coordenada(0, 4))).toBe(false);
    // Y el segundo hueco, desde #243: «Canceladas».
    expect(reparto.noPublicados.get(coordenada(0, 3))).toBe(NO_PUBLICADO);
    // Y «Notificadas» ya NO esta aqui: desde #222 el rotulo dice «Con multa notificada» y trae
    // cifra. Que siga en `noPublicados` seria un hueco que ya no existe.
    expect(reparto.noPublicados.has(coordenada(0, 2))).toBe(false);
  });

  it('«Caducadas sin notificar» NO se rellena con `pendientes`, que es el numero que encajaria', () => {
    // 6 294 cabe en ese hueco sin que nada chirrie, y es la cifra equivocada: una papeleta
    // pendiente SE PUEDE cobrar y una caducada es justo la que ya no. Debajo de una instruccion
    // que manda atenderlas primero, ese numero manda a atender lo que no hace falta.
    expect(JSON.stringify([...reparto.valores])).not.toContain('6294');
  });

  it('con VARIAS lineas, los dos de la linea dicen «no publicado» en vez de leer la primera', () => {
    // La peticion es de un ano natural agrupado por ano, o sea UNA linea. Si algun dia llegaran
    // dos, leer la primera pondria las cuentas de un TROZO del periodo bajo unos rotulos que
    // hablan del ejercicio entero: exacta y equivocada. «Levantadas» sigue saliendo, porque el
    // total del resumen si cubre todo lo que llego.
    const dosAnos = TRA_PANEL.repartir(
      resumen({
        desde: '2025-01-01',
        papeletas: 8500,
        lineas: [
          lineaDelResumen({
            clave: '2025',
            ano: 2025,
            cantidad: 88,
            conResolucionNotificada: 11,
            conResolucionDeMulta: 3,
          }),
          lineaDelResumen(),
        ],
      }) as never,
    );
    // Primero el dano y luego la forma: las 11 de 2025 NO pueden acabar bajo un rotulo del
    // ejercicio entero en un panel que se titula «Papeletas del ejercicio».
    expect(
      [...dosAnos.valores.values()],
      'Se escribio la cuenta de UNA linea bajo un rotulo del ejercicio entero',
    ).not.toContain('11');
    expect(dosAnos.noPublicados.get(coordenada(0, 2))).toBe(NO_PUBLICADO);
    expect(dosAnos.noPublicados.get(coordenada(0, 5))).toBe(NO_PUBLICADO);
    expect(dosAnos.valores.get(coordenada(0, 1))).toBe('8500');
  });

  it('y sin ninguna linea tampoco se deduce un cero', () => {
    // Un resumen vacio dice que no hubo papeletas, y de ahi se PODRIA deducir que no hay ninguna
    // pagada. Deducir es lo que este archivo no hace: lo que no llego, no se escribe.
    const vacio = TRA_PANEL.repartir(resumen({ papeletas: 0, lineas: [] }) as never);
    expect(vacio.noPublicados.get(coordenada(0, 2))).toBe(NO_PUBLICADO);
    expect(vacio.noPublicados.get(coordenada(0, 5))).toBe(NO_PUBLICADO);
    expect(vacio.valores.get(coordenada(0, 1))).toBe('0');
  });

  it('y un ejercicio FUERA de las opciones del desplegable deja el control en BLANCO', async () => {
    // Medido, y queda escrito porque es una limitacion real de esta hoja: el desplegable lleva
    // las opciones del artboard —«2026» y «2025»—, y un valor servido que no sea una de ellas no
    // se dibuja. El reparto **si** lo lleva: lo que se pierde es la casilla, no el dato.
    //
    // Se deja asi a proposito. La alternativa era no escribir el ejercicio nunca, y entonces el
    // desplegable se quedaria en su primera opcion —«2026»— afirmando un ano que nadie dijo: en
    // 2027 diria «2026» sobre cifras de 2027, que es peor que decir nada. En blanco no se afirma
    // nada, y las cinco cifras de debajo siguen siendo las que llegaron. El dia que el
    // desplegable se llene con los ejercicios que existan en vez de con dos literales del
    // artboard, esto deja de poder pasar.
    const reparto2024 = TRA_PANEL.repartir(
      resumen({ desde: '2024-01-01', hasta: '2024-12-31' }) as never,
    );
    expect(reparto2024.valores.get(coordenada(0, 0))).toBe('2024');

    const { container } = await pintar(
      'tra-panel',
      comoTraPanel(resumen({ desde: '2024-01-01', hasta: '2024-12-31' })),
    );
    expect(container.textContent).not.toContain('Ejercicio2024');
    // Y lo que si llega es todo lo demas: la casilla en blanco no se lleva la pantalla por
    // delante.
    expect(container.textContent).toContain('Levantadas8412');
  });

  it('esta hoja no tiene tabla: su bloque son seis campos', () => {
    // El tipo de la definicion ya lo dice —el bloque de `tra-panel` no declara `tabla`, y por eso
    // esta linea no compilaria si se escribiera `.tabla`—, asi que lo que se afirma aqui es que
    // el reparto no intente llenar ninguna por ninguno de los dos caminos.
    expect(PANTALLAS['tra-panel'].bloques[0]?.campos).toHaveLength(6);
    expect(reparto.filas.size).toBe(0);
    expect(reparto.tablas).toBeUndefined();
  });
});

describe('`tra-pap` — los actos de una papeleta', () => {
  const reparto = TRA_PAP.repartir(EXPEDIENTE_A as never);
  const filas = reparto.tablas?.get('actos-de-la-papeleta')?.filas ?? [];

  it('no decide ningun campo: los doce del bloque se escriben', () => {
    // No es un olvido y no puede serlo: si alguna vez la definicion gana un campo `r`, la guarda
    // de `conectores.test.ts` lo reclama. Lo que se afirma aqui es la premisa.
    expect(PANTALLAS['tra-pap'].bloques[0]?.campos.filter((c) => c.tipo.startsWith('r'))).toEqual(
      [],
    );
    expect(reparto.valores.size).toBe(0);
    expect(reparto.noPublicados.size).toBe(0);
  });

  it('las filas van por la CLAVE de la tabla, no por el indice del bloque', () => {
    // El camino de la clave es el unico cuyas celdas pueden decir que no hay dato
    // (`kamayuk-lib`#87). Si alguien lo devolviera al indice, «Estado» volveria a ser una raya
    // muda y esta prueba lo dice antes.
    expect(PANTALLAS['tra-pap'].bloques[0]?.tabla?.clave).toBe('actos-de-la-papeleta');
    expect(reparto.filas.size).toBe(0);
    expect(filas).toHaveLength(2);
  });

  it('las cuatro columnas que la operacion publica salen de ella, una a una', () => {
    expect(filas[1]?.celdas.slice(0, 4)).toEqual([
      'RG-2026-0884',
      'SANCIONADORA',
      '24/07/2026',
      'RESOLUCION_GERENCIA',
    ]);
  });

  it('«Nº» es el numero del DOCUMENTO y no el ordinal de la fila', () => {
    // El artboard escribe «1», «2», «3», «4»: el ordinal. No es un dato de la respuesta, y
    // contarlo aqui seria escribir en una celda algo que nadie publico.
    expect(filas.map((f) => f.celdas[0])).toEqual(['ACTA-2026-0311', 'RG-2026-0884']);
    expect(filas.map((f) => f.celdas[0])).not.toEqual(['1', '2']);
  });

  it('LA ROTURA DEL AC3: «Estado» sale de `estado`, y NUNCA de la ultima diligencia', () => {
    // El segundo acto lleva DOS acuses y el ultimo salio «ENTREGADO». Quedarse con el ultimo
    // daria una celda que afirma que el acto surtio efecto y esconderia que el primer intento no
    // encontro a nadie — y en una papeleta ese dato decide si todavia se puede cobrar. Lo prohibe
    // el propio backend en el javadoc de `ConsultaDeActosDeLaPapeleta`, y desde #185 lo cierra
    // publicando el estado ya DERIVADO de todos los acuses. Esta celda escribe ese campo y nada
    // mas: el resultado de una diligencia no puede aparecer aqui por ningun camino.
    expect(filas[1]?.celdas[4]).toBe('NOTIFICADO');
    expect(filas[0]?.celdas[4]).toBe('SIN_NOTIFICACION');
    for (const fila of filas) {
      expect(JSON.stringify(fila.celdas)).not.toContain('ENTREGADO');
      expect(JSON.stringify(fila.celdas)).not.toContain('NO_ENCONTRADO');
    }
  });

  it('y ninguna celda sin dato se dibuja en blanco ni con un cero', () => {
    for (const fila of filas) {
      for (const celda of fila.celdas) {
        if (typeof celda === 'string') continue;
        expect(celda.texto).toBeNull();
        // La nota es lo que distingue «nadie lo publica» de «esto esta roto».
        expect(celda.nota, 'una celda sin dato sin motivo es peor que la celda').toBeTruthy();
      }
    }
  });
});

describe('`tra-veh` — el deposito y el vehiculo de la direccion', () => {
  const reparto = TRA_VEH.repartir([
    vehiculo(),
    pagina([internado(), internado({ id: 2, placa: 'V1H-882', dias: 39, clase: 'MOTOCICLETA' })], 188),
    pagina([internado()], 1),
  ] as never);

  it('los cuatro campos que se pueden escribir salen de las respuestas', () => {
    expect(reparto.valores.get(coordenada(0, 0))).toBe('T2G-418');
    expect(reparto.valores.get(coordenada(0, 1))).toBe('0041182');
    expect(reparto.valores.get(coordenada(0, 2))).toBe('18/07/2026');
    expect(reparto.valores.get(coordenada(0, 5))).toBe('TOYOTA YARIS');
  });

  it('«Dias de custodia» lleva su fecha dentro (regla 9)', () => {
    // Los dias en deposito de hoy no son los de manana: el backend los cuenta a `calculadoA` y la
    // publica al lado justamente para que no se lean como un numero fijo.
    expect(reparto.valores.get(coordenada(0, 6))).toBe('54 · 10/09/2026');
  });

  it('LA ROTURA DEL AC4: no multiplica los dias por la tasa, y los dos campos lo dicen', () => {
    // 54 x 18.00 = 972.00, que es justo lo que el artboard dibuja. Y `tasaDeCustodia` **no es una
    // tarifa**: es el concepto del TUPA. La tarifa de verdad espera a D-02b, y el backend se niega
    // a componer el importe con una inventada — «el administrado pagaria lo que la pantalla diga».
    // Lo primero que se mira es el DANO, y no la forma: una cifra de custodia escrita aqui es
    // deuda que alguien paga. Si esta prueba se pone roja, lo primero que se lee es eso.
    const escrito = [...reparto.valores.values()].join(' ');
    expect(
      escrito,
      'Alguien compuso el importe de la custodia en la pantalla. La tarifa la fija una ordenanza\n' +
        'que todavia no esta (D-02b) y el backend se niega a publicarla por eso mismo: «el\n' +
        'administrado pagaria lo que la pantalla diga».',
    ).not.toContain('972');
    // Y el concepto del TUPA no se cuela en el hueco de la tarifa: es un codigo, no soles.
    expect(escrito, '`tasaDeCustodia` es el concepto del TUPA, no una tarifa').not.toContain('TUPA');
    expect(reparto.noPublicados.get(coordenada(0, 7))).toBe(NO_PUBLICADO);
    expect(reparto.noPublicados.get(coordenada(0, 8))).toBe(NO_PUBLICADO);
    expect(reparto.valores.has(coordenada(0, 7))).toBe(false);
    expect(reparto.valores.has(coordenada(0, 8))).toBe(false);
  });

  it('«Depositos», «Clase de vehiculo» y «Grua» no se rellenan, y por eso no estan', () => {
    // Los dos primeros son desplegables de lista cerrada y lo que llega es texto libre: un valor
    // servido que no sea una de las opciones deja el control EN BLANCO, o sea que ponerlo perderia
    // el dato. El tercero no lo publica nadie. Ninguno es de solo lectura, asi que no deja hueco.
    for (const campo of [3, 4, 9]) {
      expect(reparto.valores.has(coordenada(0, campo))).toBe(false);
      expect(reparto.noPublicados.has(coordenada(0, campo))).toBe(false);
    }
  });

  it('sin ningun internamiento de esa placa, «Dias de custodia» dice «no publicado»', () => {
    const sinInternar = TRA_VEH.repartir([
      vehiculo({ placa: 'X9Z-001' }),
      pagina([internado()], 188),
      pagina([], 0),
    ] as never);

    expect(sinInternar.noPublicados.get(coordenada(0, 6))).toBe(NO_PUBLICADO);
    expect(sinInternar.valores.has(coordenada(0, 6))).toBe(false);
    // Y aun asi la placa se escribe: la pantalla dice de quien es lo que ensena.
    expect(sinInternar.valores.get(coordenada(0, 0))).toBe('X9Z-001');
  });

  it('la tabla es el DEPOSITO entero, y sus dos columnas sin dato lo dicen', () => {
    const filas = reparto.tablas?.get('vehiculos-internados')?.filas ?? [];

    expect(filas).toHaveLength(2);
    expect(filas[1]?.celdas[0]).toBe('V1H-882');
    expect(filas[1]?.celdas[2]).toBe('18/07/2026');
    expect(filas[1]?.celdas[3]).toBe('39');
    expect(filas[1]?.celdas[5]).toBe('INTERNADO');
    // «Clase» ← `clase`, desde #185. Y cada fila la SUYA: una sola clase repetida diria que el
    // deposito entero es de una.
    expect(filas[0]?.celdas[1]).toBe('AUTOMOVIL');
    expect(filas[1]?.celdas[1]).toBe('MOTOCICLETA');
    // «Custodia S/» sigue siendo un hueco, y NO es un olvido: es D-02b.
    expect(filas[0]?.celdas[4]).toEqual({ texto: null, nota: expect.stringContaining('D-02b') });
  });

  it('entrega el TOTAL que la operacion publica, y no una cuenta de las filas (#172)', () => {
    // Lo que viaja es un NUMERO y no una frase: el «de» de «2 de 188» lo pone `useDatosDeLaHoja`
    // con `t()`, porque escrito aqui seria castellano que nunca podria traducirse (#103).
    //
    // Y es el de la OPERACION. La muestra trae dos filas y `totalElementos: 188`: si alguien
    // cambiara esto por `contenido.length`, este caso diria 2 y la pantalla afirmaria que el
    // deposito entero son dos vehiculos.
    expect(reparto.tablas?.get('vehiculos-internados')?.totalElementos).toBe(188);
    expect(reparto.tablas?.get('vehiculos-internados')?.filas).toHaveLength(2);
  });

  it('y el `hayMas` y las paginas los dice el SERVIDOR, no la cuenta de las filas (#187)', () => {
    // Con el tope alcanzado exacto, contar las filas recibidas diria que no hay pagina siguiente
    // justo cuando la hay. Por eso los dos salen del envoltorio.
    expect(reparto.nombrados?.get('vehiculos-internados.hayMas')).toBe(true);
    expect(reparto.nombrados?.get('vehiculos-internados.paginas')).toBe('10');
  });
});

describe('#387 — la ficha del vehiculo es la del internamiento VIGENTE, no la del mas antiguo', () => {
  // El escenario del issue, tal cual. `T2G-418` entro el 03/03/2025 con la papeleta 0039001 y
  // salio el 10/03/2025: 7 dias. Volvio a entrar el 20/09/2026 con la 0041182 y sigue dentro.
  // Hoy es 23/09/2026. Las dos filas NO comparten ni fecha, ni papeleta, ni dias: si la pantalla
  // ensena una sola cifra de la de 2025, es que se quedo con la que no era.
  const DE_2025 = internado({
    id: 11,
    papeleta: '0039001',
    fechaDeIngreso: '2025-03-03',
    fechaDeSalida: '2025-03-10',
    dias: 7,
    calculadoA: '2026-09-23',
    estado: 'LIBERADO',
  });
  const DE_2026 = internado({
    id: 12,
    papeleta: '0041182',
    fechaDeIngreso: '2026-09-20',
    fechaDeSalida: null,
    dias: 3,
    calculadoA: '2026-09-23',
    estado: 'INTERNADO',
  });
  // El deposito es OTRO vehiculo con otras fechas: la tabla no puede prestarle a los campos una
  // cifra que los haga pasar.
  const DEPOSITO = [
    internado({ id: 3, placa: 'AAA-111', papeleta: '0040001', fechaDeIngreso: '2026-08-01', dias: 53 }),
  ];

  it('con dos internamientos de la placa, ensena el de 2026 y ni una cifra del de 2025', async () => {
    // El doble ORDENA como el backend: da igual en que orden se le pasen.
    const { container, pedidas } = await pintar(
      'tra-veh',
      comoTraVeh(vehiculo(), DEPOSITO, [DE_2025, DE_2026]),
      'T2G-418',
    );

    expect(
      pedidas.some((url) => url.includes('/transito/internamientos?placa=T2G-418')),
      'no se pidieron los internamientos de la placa',
    ).toBe(true);
    // «Fecha de internamiento» es un disparador de calendario y su valor SI esta en el texto.
    expect(
      container.textContent,
      'la fecha es la del internamiento de 2025: se pidio el primero de la placa y no el vigente',
    ).not.toContain('03/03/2025');
    expect(container.textContent).toContain('20/09/2026');
    // «Dias de custodia»: los 3 que lleva, con su fecha. Los 7 de 2025 «a hoy» son el defecto.
    expect(container.textContent).not.toContain('7 · 23/09/2026');
    expect(container.textContent).toContain('3 · 23/09/2026');
    // «Nº de papeleta» es un `<input>`: su valor no esta en `textContent`.
    expect(valoresEscritos(container)).not.toContain('0039001');
    expect(valoresEscritos(container)).toContain('0041182');
  });

  it('con un unico internamiento YA LIBERADO, sus datos no se presentan como de ahora', async () => {
    const { container } = await pintar(
      'tra-veh',
      comoTraVeh(vehiculo(), DEPOSITO, [DE_2025]),
      'T2G-418',
    );

    expect(container.textContent).not.toContain('03/03/2025');
    expect(container.textContent).not.toContain('7 · 23/09/2026');
    expect(valoresEscritos(container)).not.toContain('0039001');
    // Y el campo de solo lectura lo DICE con su palabra, en vez de quedarse en blanco o de decir
    // «no publicado», que culparia al backend de algo que si publico.
    expect(container.textContent).toContain(`Días de custodia${FUERA_DEL_DEPOSITO}`);
  });

  it('el reparto: liberado es no escribir 0|1, 0|2 ni 0|6, y 0|6 dice por que', () => {
    const liberado = TRA_VEH.repartir([vehiculo(), pagina(DEPOSITO, 1), pagina([DE_2025], 1)] as never);

    for (const campo of [1, 2, 6]) {
      expect(liberado.valores.has(coordenada(0, campo)), `0|${String(campo)} se escribio`).toBe(false);
    }
    expect(liberado.noPublicados.get(coordenada(0, 6))).toBe(FUERA_DEL_DEPOSITO);
    // La placa y la ficha siguen: el vehiculo existe, lo que no hay es internamiento vigente.
    expect(liberado.valores.get(coordenada(0, 0))).toBe('T2G-418');
    expect(liberado.valores.get(coordenada(0, 5))).toBe('TOYOTA YARIS');
  });

  it('basta la FECHA DE SALIDA para saber que salio, aunque el estado no lo diga', () => {
    // Las dos salen de la misma liberacion en el backend; se miran las dos para que un estado
    // que no se reconozca no deje pasar una salida que si consta.
    const conSalida = TRA_VEH.repartir([
      vehiculo(),
      pagina(DEPOSITO, 1),
      pagina([internado({ ...DE_2025, estado: 'OTRO' })], 1),
    ] as never);

    expect(conSalida.valores.has(coordenada(0, 6))).toBe(false);
    expect(conSalida.noPublicados.get(coordenada(0, 6))).toBe(FUERA_DEL_DEPOSITO);
  });

  it('EN_ABANDONO sigue en el deposito: sus dias se ensenan', () => {
    // El abandono no saca al vehiculo: sigue ocupando el deposito y su custodia sigue corriendo.
    const abandonado = TRA_VEH.repartir([
      vehiculo(),
      pagina(DEPOSITO, 1),
      pagina([internado({ ...DE_2026, estado: 'EN_ABANDONO' })], 1),
    ] as never);

    expect(abandonado.valores.get(coordenada(0, 2))).toBe('20/09/2026');
    expect(abandonado.valores.get(coordenada(0, 6))).toBe('3 · 23/09/2026');
    expect(abandonado.noPublicados.has(coordenada(0, 6))).toBe(false);
  });
});

describe('la placa viaja en la RUTA, y sin ella no se pide nada (AC2)', () => {
  it('la placa de la direccion llega hasta la peticion de la ficha', async () => {
    const { pedidas } = await pintar('tra-veh', comoTraVeh(vehiculo(), [internado()], [internado()]), 'T2G-418');

    expect(
      pedidas.some((url) => url.includes('/rentas/vehiculos/T2G-418')),
      'la peticion no llevo la placa de la direccion',
    ).toBe(true);
    // Y la tabla del deposito se pide SIN la placa: es el deposito, no el historial de un coche.
    expect(pedidas.some((url) => /\/transito\/internamientos\?tamano=/.test(url))).toBe(true);
    expect(pedidas.some((url) => url.includes('/transito/internamientos?placa=T2G-418'))).toBe(true);
  });

  it('y la MISMA hoja sin placa no pide NADA, y lo dice nombrando la placa', async () => {
    const { container } = await pintar('tra-veh', comoTraVeh(vehiculo(), [internado()], [internado()]));

    // La frase es de la hoja y no la de Consultas: pedir «el codigo del contribuyente» para abrir
    // la ficha de un vehiculo manda a buscar el dato equivocado.
    expect(SIN_PLACA.enElCampo).toBe('falta la placa');
    expect(SIN_PLACA.explicacion).not.toContain('contribuyente');
    await waitFor(() => {
      expect(container.textContent).toContain('falta la placa');
    });
  });

  it('sin placa no sale ni una peticion a transito ni a vehiculos', async () => {
    const { pedidas } = await pintar('tra-veh', comoTraVeh(vehiculo(), [internado()], [internado()]));

    expect(pedidas.filter((url) => /\/transito\/|\/rentas\/vehiculos/.test(url))).toEqual([]);
  });
});

describe('LA ROTURA DEL AC3, en el DOM: con otra respuesta, la pantalla ensena otra cosa', () => {
  it('`tra-pap` dibuja lo que llego, y nunca la fila del artboard', async () => {
    const { container } = await pintar('tra-pap', comoTraPap(EXPEDIENTE_A));
    expect(container.textContent).toContain('RG-2026-0884');
    expect(container.textContent).toContain('ACTA-2026-0311');

    const otra = await pintar('tra-pap', comoTraPap(EXPEDIENTE_B));
    expect(otra.container.textContent).toContain('RG-2026-1902');
    expect(otra.container.textContent).not.toContain('RG-2026-0884');

    for (const celda of FILA_DEL_ARTBOARD['tra-pap']) {
      expect(otra.container.textContent, `«${celda}» es del artboard`).not.toContain(celda);
    }
  });

  it('`tra-panel` dibuja las cifras que llegaron, y ninguna de las cinco del artboard', async () => {
    // Los seis campos de esta hoja son de solo lectura o un desplegable: el interprete los
    // escribe en el texto y no en un `<input>`, al contrario que los de `tra-veh`.
    const { container } = await pintar('tra-panel', comoTraPanel(resumen()));
    expect(container.textContent).toContain('Levantadas8412');
    expect(container.textContent).toContain('Con multa notificada5884');

    const otro = await pintar(
      'tra-panel',
      comoTraPanel(
        resumen({
          desde: '2025-01-01',
          hasta: '2025-12-31',
          papeletas: 311,
          lineas: [
            lineaDelResumen({
              clave: '2025',
              ano: 2025,
              conResolucionNotificada: 77,
              conResolucionDeMulta: 4,
            }),
          ],
        }),
      ),
    );
    expect(otro.container.textContent).toContain('Ejercicio2025');
    expect(otro.container.textContent).toContain('Levantadas311');
    expect(otro.container.textContent).toContain('Con multa notificada77');
    expect(otro.container.textContent).toContain('Con resolución de multa4');
    expect(otro.container.textContent).not.toContain('8412');

    for (const celda of FILA_DEL_ARTBOARD['tra-panel']) {
      expect(otro.container.textContent, `«${celda}» es del artboard`).not.toContain(celda);
    }
    // Y los dos huecos llegan al DOM diciendolo, no en blanco. Desde #243 son dos: «Canceladas»
    // se sumo a «Caducadas sin notificar», y la cifra del artboard —2 118— NO puede aparecer
    // aunque el campo `pagadas` siga llegando con ella.
    expect(container.textContent).toContain('Canceladasno publicado');
    expect(container.textContent).not.toContain('2118');
    expect(otro.container.textContent).toContain(NO_PUBLICADO);
  });

  it('`tra-veh` tambien, y su tabla sale del deposito que contesto', async () => {
    const { container } = await pintar(
      'tra-veh',
      comoTraVeh(vehiculo(), [internado({ placa: 'AAA-111' })], [internado()]),
      'T2G-418',
    );
    expect(container.textContent).toContain('AAA-111');
    expect(valoresEscritos(container)).toContain('TOYOTA YARIS');

    const otra = await pintar(
      'tra-veh',
      comoTraVeh(
        vehiculo({ placa: 'B2B-222', marca: 'NISSAN', modelo: 'FRONTIER' }),
        [internado({ placa: 'CCC-333' })],
        [internado({ dias: 7, calculadoA: '2026-09-11' })],
      ),
      'B2B-222',
    );
    expect(otra.container.textContent).toContain('CCC-333');
    expect(otra.container.textContent).not.toContain('AAA-111');
    // Los campos que se escriben son `<input>`: su valor NO esta en `textContent`, esta en
    // `value`. Leerlo de otra forma diria que la pantalla no los pinta, y si los pinta.
    expect(valoresEscritos(otra.container)).toContain('NISSAN FRONTIER');
    expect(valoresEscritos(otra.container)).toContain('B2B-222');

    for (const celda of FILA_DEL_ARTBOARD['tra-veh']) {
      expect(otra.container.textContent, `«${celda}» es del artboard`).not.toContain(celda);
    }
  });

  it('y la celda sin dato llega al DOM MARCADA y con su motivo, no como una raya muda', async () => {
    // Desde #185 la unica celda sin dato de estas dos hojas es «Custodia S/», y no es un olvido:
    // es D-02b. «Estado» y «Clase» las publica ya el backend y se pintan con lo que llego.
    const { container } = await pintar(
      'tra-veh',
      comoTraVeh(vehiculo(), [internado()], [internado()]),
      'T2G-418',
    );

    const sinDato = container.querySelectorAll('[data-celda-sin-dato]');
    // Una por fila del deposito: la columna «Custodia S/».
    expect(sinDato.length).toBe(1);
    expect(sinDato[0]?.getAttribute('title')).toContain('D-02b');
  });

  it('#185 — «Estado» sale de la respuesta, y NO se traduce a las palabras del artboard', async () => {
    const { container } = await pintar('tra-pap', comoTraPap(EXPEDIENTE_A));

    // Lo que llego, tal cual: el vocabulario que el dominio deriva de TODOS los acuses.
    expect(container.textContent).toContain('NOTIFICADO');
    expect(container.textContent).toContain('SIN_NOTIFICACION');
    // Y ninguna celda sin dato: la columna que #180 dejo abierta ya tiene que pintar.
    expect(container.querySelectorAll('[data-celda-sin-dato]').length).toBe(0);
    // «Conforme» es un estado DEL PLAZO, y el plazo no lo publica nadie: escribirlo aqui
    // afirmaria que la papeleta todavia se puede cobrar.
    for (const delPlazo of ['Conforme', 'Por vencer', 'Pendiente']) {
      expect(container.textContent, `«${delPlazo}» es del artboard, no de la respuesta`).not.toContain(
        delPlazo,
      );
    }
  });

  it('#185 — un acto que nadie pudo notificar NO se pinta con el tono de conforme', async () => {
    // El defecto de #175 en la columna nueva: `NO_NOTIFICADO` no lo reconoce ninguna de las tres
    // listas de `tono.ts`, asi que cae en el tono de «no se» — y no en el verde por omision.
    expect(tonoDe('NO_NOTIFICADO')).toBe(TONO_SIN_RECONOCER);
    expect(tonoDe('NOTIFICADO')).toBe(TONO_SIN_RECONOCER);
    expect(tonoDe('SIN_DILIGENCIAR')).toBe(TONO_SIN_RECONOCER);
    expect(tonoDe('SIN_NOTIFICACION')).toBe(TONO_SIN_RECONOCER);
  });
});
