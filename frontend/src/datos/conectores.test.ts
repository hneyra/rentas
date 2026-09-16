import { describe, expect, it } from 'vitest';

import { PANTALLAS } from '../pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import { coordenada } from '@kamayuk/ui';
import { CONECTORES, NO_PUBLICADO } from './conectores.ts';
import { CONSTANCIA_NEGADA, FICHA, SIN_CAMPANIA } from './conectores/consultasDeMuestra.ts';
import type {
  CorridaDelPredial,
  DeudaEnCoactiva,
  LiquidacionDeCostas,
  Paginado,
  PrescripcionDeclarada,
  ProcesoDelExpediente, IndicadorDeRecaudacion, TrabajoParado } from './lecturas.ts';

/**
 * **Lo que cada pantalla conectada saca de su respuesta** (#97).
 *
 * Lo detallado de las dos hojas de Consultas esta en `conectores/consultas.test.ts`, al lado de su
 * conector; aqui quedan el centinela del registro y el recorrido que vale para las once.
 *
 * Lo que se comprueba no es que el mapeo «funcione»: es que **ningun campo se quede sin decidir**.
 * Un campo de solo lectura de una pantalla conectada tiene que estar en uno de los dos sitios —con
 * dato, o declarado «no publicado»—, y **nunca en ninguno**: un campo olvidado se dibuja con el
 * motivo de la PANTALLA, que en una pantalla conectada dice que si esta conectada. Seria un hueco
 * mintiendo sobre su propia causa.
 */

/** La respuesta que la instalacion da de verdad, recortada a lo que el conector usa. */
const CORRIDA: CorridaDelPredial = {
  id: 1,
  ejercicio: '2026',
  alcance: 'PADRON',
  sector: null,
  simulacion: false,
  conjunto: 'V3',
  fechaCalculo: '28/01/2026 02:14',
  observados: 534,
  etapas: [
    { etapa: 'Lectura del padron', registros: 62418, monto: '—', observados: 0, estado: 'Conforme' },
    { etapa: 'Generacion de cuponeras', registros: 61350, monto: '—', observados: 534, estado: 'Observado' },
  ],
};

const PAGINA: Paginado<DeudaEnCoactiva> = {
  contenido: [],
  pagina: 0,
  tamano: 20,
  totalElementos: 388,
  totalPaginas: 20,
  hayMas: true,
};

/**
 * Lo que `coa-exp` recibe: el proceso de un expediente. Recortado a lo que el conector usa.
 *
 * La forma sale de `docs/50-api/formas-de-la-api.json`; el detalle campo a campo lo prueba
 * `conectores/coactiva.test.ts`.
 */
const PROCESO = {
  expediente: {
    numero: '2026-0418',
    codContribuyente: '00000000008',
    fechaDeApertura: '2026-08-04',
    deudaMateriaDeCobranza: '9412.15',
    costas: '96.00',
    deudaAlDia: '2026-09-06',
  },
  actuaciones: [
    { numero: '1', titulo: 'RESOLUCION DE EJECUCION COACTIVA', fecha: '2026-08-04', medida: null },
  ],
} as unknown as ProcesoDelExpediente;

/** Lo que `coa-cost` recibe: su liquidacion y la prescripcion del mismo tributo. */
const COSTAS = {
  liquidacion: {
    expedCoact: '2026-0418',
    tributo: 'PREDIAL',
    totalS: '96.00',
    fecha: '2026-09-06',
    costas: [
      {
        acto: 'REC1',
        descripcion: 'Resolucion de ejecucion coactiva',
        montoS: '18.00',
        arancelFuente: 'ARANCEL_COSTA:REC1',
      },
    ],
  },
  prescripcion: { plazo: '4 ANIOS' },
} as unknown as {
  readonly liquidacion: LiquidacionDeCostas;
  readonly prescripcion: PrescripcionDeclarada;
};

/**
 * La respuesta con que se ejercita cada conector. **Una por conector, y sin excepcion.**
 *
 * Es lo que hace total la guarda de mas abajo: un conector nuevo sin muestra no se salta la
 * comprobacion en silencio, sale rojo pidiendola.
 */
/**
 * Las dos paginas que Licencias pide, con la forma que `formas-de-la-api.json` publica (#168).
 *
 * Llegan aqui al mezclarse #170, que anadio la guarda de «cada conector tiene su muestra». Sin
 * ellas esa guarda salia roja nombrando las dos hojas: es el modo de fallo que fue escrita para
 * cazar —un registro que crece y una comprobacion que se calla sobre lo que no reconoce—, y lo
 * cazo en la primera mezcla que lo puso a prueba.
 */
const CIIU = {
  contenido: [
    {
      codigo: 'A-0111-01',
      descripcion: 'Cultivo de cereales',
      seccion: 'Agricultura',
      riesgoItse: 'Bajo',
    },
  ],
  pagina: 0,
  tamano: 20,
  totalElementos: 1842,
  totalPaginas: 93,
  hayMas: true,
};

const PADRON = {
  contenido: [
    {
      nroLicencia: 'LF-2026-0001',
      contribuyente: 'Comercial del Norte S.A.C.',
      denominacionComercial: 'Bodega El Sol',
      giros: [{ codigo: 'G-5211-01', descripcion: 'Venta al por menor', principal: true, activo: true }],
      estado: 'VIGENTE',
    },
  ],
  pagina: 0,
  tamano: 20,
  totalElementos: 1,
  totalPaginas: 1,
  hayMas: false,
};

const RECAUDACION_MEDIDA: IndicadorDeRecaudacion = {
  ejercicio: 2026,
  fechaCalculo: '2026-09-16',
  calculadoEn: '2026-09-16T10:00:00Z',
  cargado: { importe: '23725394.80', actualizadoA: '2026-09-16' },
  kpis: [
    {
      label: 'Recaudado 2026',
      value: 'S/ 18,424,251.20',
      note: '',
      importe: { importe: '18424251.20', actualizadoA: '2026-09-16' },
    },
    { label: 'Avance de cobranza', value: '77 %', note: '', importe: null },
  ],
  paneles: [
    {
      title: 'Recaudacion por tributo',
      note: '',
      rows: [
        {
          label: 'Impuesto predial',
          sub: '',
          value: 'S/ 8,420,118.40',
          pct: 89,
          avanceConocido: true,
          importe: { importe: '8420118.40', actualizadoA: '2026-09-16' },
          cargado: { importe: '9418204.60', actualizadoA: '2026-09-16' },
          pendiente: { importe: '998086.20', actualizadoA: '2026-09-16' },
        },
      ],
    },
  ],
};

const PARADO_MEDIDO: TrabajoParado = {
  ejercicio: 2026,
  fechaCalculo: '2026-09-16',
  calculadoEn: '2026-09-16T10:00:00Z',
  frentes: [
    {
      frente: 'TRANSITO',
      modulo: 'Transito',
      queEstaParado: 'papeletas sin resolucion de multa emitida',
      porQueCuestaDinero: 'sin emitir no se pueden notificar ni cobrar, y prescriben',
      cuantos: 1842,
      importe: null,
    },
  ],
};

const MUESTRAS: Readonly<Partial<Record<ClaveDeHoja, unknown>>> = {
  panel: CORRIDA,
  'coa-panel': PAGINA,
  'coa-exp': PROCESO,
  'coa-cost': COSTAS,
  'aut-cat': CIIU,
  'aut-tram': PADRON,
  'ini-panel': [RECAUDACION_MEDIDA, CORRIDA],
  'ini-flujo': RECAUDACION_MEDIDA,
  'ini-parado': PARADO_MEDIDO,
  'con-panel': [FICHA, SIN_CAMPANIA],
  'con-doc': CONSTANCIA_NEGADA,
};

/** Los campos de solo lectura de una pantalla, por su coordenada. */
function soloLecturaDe(clave: ClaveDeHoja): readonly string[] {
  return PANTALLAS[clave].bloques.flatMap((bloque, b) =>
    bloque.campos.flatMap((campo, c) => (campo.tipo.startsWith('r') ? [coordenada(b, c)] : [])),
  );
}

describe('los conectores', () => {
  it('EL CENTINELA: estan los once que estan, y no cero ni cuarenta', () => {
    // Cero dejaria todo lo de abajo sin sujeto. Cuarenta significaria que alguien conecto
    // pantallas cuyas operaciones no publican lo que ensenan, que es lo que este archivo evita.
    // La lista se escribe a mano y crece de una en una: conectar una pantalla es una decision, y
    // una decision se revisa leyendo su diff. Las dos de licencias llegan con #168, y su medida
    // —que publica cada operacion y que no— esta en `conectores/licencias.ts`. Las dos de
    // Consultas llegan con #169, y son las primeras que EXIGEN SUJETO: sin un codigo de
    // contribuyente en la ruta no piden nada, y la pantalla lo dice en vez de pedir el padron.
    expect(Object.keys(CONECTORES).sort()).toEqual(
      [
        'aut-cat', 'aut-tram', 'coa-cost', 'coa-exp', 'coa-panel',
        'con-doc', 'con-panel',
        'ini-flujo', 'ini-panel', 'ini-parado', 'panel',
      ].sort(),
    );
  });
  it('y cada uno tiene su muestra: sin ella, la guarda de abajo se lo saltaria', () => {
    // Una comprobacion que recorre un registro y se calla sobre lo que no reconoce deja de ser
    // una comprobacion el dia que alguien anade la quinta hoja.
    const sinMuestra = Object.keys(CONECTORES).filter(
      (clave) => MUESTRAS[clave as ClaveDeHoja] === undefined,
    );
    expect(sinMuestra, 'anade su respuesta a `MUESTRAS`').toEqual([]);
  });

  it('NINGUN campo de una pantalla conectada se queda sin decidir', () => {
    const olvidados: string[] = [];
    for (const [clave, conector] of Object.entries(CONECTORES)) {
      if (conector === undefined) continue;
      const respuesta = MUESTRAS[clave as ClaveDeHoja];
      const reparto = conector.repartir(respuesta as never);
      for (const coord of soloLecturaDe(clave as ClaveDeHoja)) {
        const decidido =
          reparto.valores.has(coord as never) || reparto.noPublicados.has(coord as never);
        if (!decidido) olvidados.push(`  ${clave} · ${coord}`);
      }
    }
    expect(
      olvidados,
      'Hay campos de una pantalla CONECTADA que no salen de ningun sitio ni se declaran «no\n' +
        'publicado». Se dibujarian con el motivo de la pantalla, que en una conectada dice que SI\n' +
        `esta conectada — un hueco mintiendo sobre su propia causa:\n${olvidados.join('\n')}`,
    ).toEqual([]);
  });
});

describe('`panel` — la ultima corrida', () => {
  const conector = CONECTORES.panel;
  if (conector === undefined) throw new Error('falta el conector de `panel`');
  const reparto = conector.repartir(CORRIDA as never);

  it('la fecha y los observados salen de la respuesta', () => {
    expect(reparto.valores.get(coordenada(0, 1))).toBe('28/01/2026 02:14');
    expect(reparto.valores.get(coordenada(0, 3))).toBe('534');
  });

  it('la tabla sale de `etapas`, con sus cinco columnas en orden', () => {
    const filas = reparto.filas.get(0);
    expect(filas).toHaveLength(2);
    expect(filas?.[1]).toEqual(['Generacion de cuponeras', '61350', '—', '534', 'Observado']);
    // Cinco celdas por fila, que son las cinco columnas que la definicion declara.
    expect(PANTALLAS.panel.bloques[0]?.tabla?.columnas).toHaveLength(5);
    expect(filas?.[0]).toHaveLength(5);
  });

  it('NO deduce «cuentas emitidas» de la ultima etapa, aunque el numero coincida', () => {
    // La ultima etapa trae 61 350 registros y el artboard ensena 61 350 cuentas emitidas. **Que
    // coincidan no las hace lo mismo**: una es «cuantas cuponeras se generaron» y la otra «cuantas
    // cuentas quedaron emitidas», y el dia que difieran nadie sabria que el numero era deducido.
    expect(reparto.valores.has(coordenada(0, 2))).toBe(false);
    expect(reparto.noPublicados.get(coordenada(0, 2))).toBe(NO_PUBLICADO);
    // Y por si alguien lo dedujera igualmente: el valor de la etapa no puede aparecer como valor.
    expect([...reparto.valores.values()]).not.toContain('61350');
  });
});
