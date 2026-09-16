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
import type {
  ExpedienteDeLaPapeleta,
  InternamientoEnDeposito,
  Paginado,
  VehiculoServido,
} from '../lecturas.ts';
import { SIN_PLACA, TRA_PAP, TRA_VEH } from './transito.ts';

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
    papeleta: '0041182',
    deposito: 'DEPOSITO MUNICIPAL 1',
    fechaDeIngreso: '2026-07-18',
    fechaDeSalida: null,
    dias: 54,
    calculadoA: '2026-09-10',
    estado: 'EN_DEPOSITO',
    // El CONCEPTO del TUPA, no una tarifa. Ver `conectores/transito.ts`.
    tasaDeCustodia: 'TUPA-2.14 CUSTODIA DIARIA',
    acta: 'ACTA-2026-0311',
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
      acuses: [],
    },
    {
      clase: 'RESOLUCION_GERENCIA',
      tipo: 'SANCIONADORA',
      numero: 'RG-2026-0884',
      fecha: '2026-07-24',
      documentoId: 41,
      observacion: 'Emitida por el area de transito',
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
      acuses: [],
    },
  ],
};

// ── Lo que el ARTBOARD dibuja, y que por tanto no puede salir de una respuesta ──────────────

const FILA_DEL_ARTBOARD = {
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
  return <PantallaDeRentas definicion={pantallaDe(clave)} datos={useDatosDeLaHoja(clave, sujeto)} />;
}

/** Sustituye `fetch` por un doble que contesta segun la RUTA, y dice a que se llamo. */
function contesta(porRuta: readonly (readonly [string, unknown])[]) {
  const pedidas: string[] = [];
  const doble = vi.fn<typeof fetch>((entrada) => {
    const url = String(entrada);
    pedidas.push(url);
    const casa = porRuta.find(([trozo]) => url.includes(trozo));
    return Promise.resolve(
      new Response(JSON.stringify(casa?.[1] ?? {}), {
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
  porRuta: readonly (readonly [string, unknown])[],
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
    // El acotado va primero: `includes('/transito/internamientos')` casa con los dos.
    ['/transito/internamientos?placa=', pagina(suyos, suyos.length)],
    ['/transito/internamientos', pagina(deposito, 188)],
    ['/rentas/vehiculos/', ficha],
  ] as const;
}

/** Lo que llevan dentro los campos que se escriben: un `<input>` no pone su valor en el DOM. */
function valoresEscritos(container: HTMLElement): readonly string[] {
  return [...container.querySelectorAll('input')].map((campo) => campo.value);
}

afterEach(() => {
  vi.unstubAllGlobals();
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

  it('LA ROTURA DEL AC3: «Estado» dice que no hay dato, y NO resume los acuses', () => {
    // El segundo acto lleva DOS acuses y el ultimo salio «ENTREGADO». Quedarse con el ultimo
    // daria una celda que afirma que el acto surtio efecto y esconderia que el primer intento no
    // encontro a nadie — y en una papeleta ese dato decide si todavia se puede cobrar. Lo prohibe
    // el propio backend en el javadoc de `ConsultaDeActosDeLaPapeleta`.
    const estado = filas[1]?.celdas[4];
    expect(estado).toEqual({ texto: null, nota: expect.stringContaining('acuses') });
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
    pagina([internado(), internado({ id: 2, placa: 'V1H-882', dias: 39 })], 188),
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
    expect(filas[1]?.celdas[5]).toBe('EN_DEPOSITO');
    // «Clase» y «Custodia S/»: sin dato, con su motivo.
    expect(filas[0]?.celdas[1]).toEqual({ texto: null, nota: expect.stringContaining('clase') });
    expect(filas[0]?.celdas[4]).toEqual({ texto: null, nota: expect.stringContaining('D-02b') });
  });

  it('y NO escribe su propio conteo: «188 registros» seria un castellano sin `t()`', () => {
    // El interprete cuenta las filas que recibe con la palabra que SU saco traduce. Escribir aqui
    // `totalElementos` diria ademas «188» sobre una tabla de dos filas.
    expect(reparto.tablas?.get('vehiculos-internados')?.conteo).toBeUndefined();
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
    const { container } = await pintar('tra-pap', comoTraPap(EXPEDIENTE_A));

    const sinDato = container.querySelectorAll('[data-celda-sin-dato]');
    // Una por fila: la columna «Estado» de los dos actos.
    expect(sinDato.length).toBe(2);
    expect(sinDato[0]?.getAttribute('title')).toContain('acuses');
  });
});
