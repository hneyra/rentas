import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { coordenada } from '@kamayuk/ui';

import { NO_PUBLICADO } from '../conectores.ts';
import type {
  DeudaEnCoactiva,
  LiquidacionDeCostas,
  Paginado,
  PrescripcionDeclarada,
  ProcesoDelExpediente,
} from '../lecturas.ts';
import { useDatosDeLaHoja } from '../useDatosDeLaHoja.ts';
import type { ClaveDeHoja } from '../../pantallas/arbol.ts';
import { pantallaDe } from '../../pantallas/definiciones/index.ts';
import { PantallaDeRentas } from '../../pantallas/PantallaDeRentas.tsx';
import { COA_COST, COA_EXP, COA_PANEL, SIN_DATO } from './coactiva.ts';

/**
 * **Lo que las hojas de Coactiva ensenan es lo que llego, y no lo de su definicion** (#170, AC3).
 *
 * <h2>Dos capas, y hacen falta las dos</h2>
 *
 * Abajo, el reparto: campo a campo, que cada coordenada saque el dato que le toca y que la que no
 * tiene dato **diga «no publicado»** en vez de quedarse muda. Eso se prueba sobre el objeto, que es
 * donde el rojo nombra la coordenada.
 *
 * Arriba, la pantalla montada con `fetch` sustituido: se dibuja, **se cambia la respuesta del
 * doble y se vuelve a dibujar**. Si lo que se ve cambia con ella, no puede salir de la definicion —
 * que es lo unico que esta prueba tiene que demostrar y lo unico que el reparto por si solo no
 * demuestra—. Y se cambia **una respuesta cada vez**, para que cada operacion responda por lo suyo:
 * con cuatro lecturas encendidas, un solo cambio no diria cual de ellas mueve que campo.
 */

// ── Las respuestas, con la forma que el contrato publica ─────────────────────────────────────

const EXPEDIENTE = {
  numero: '2026-0418',
  ejercicio: 2026,
  correlativo: 418,
  codContribuyente: '00000000008',
  ejecutor: 'AYCA GONZALES, ALBERTO',
  auxiliar: 'RIOS MENDOZA, MARIA',
  fechaDeApertura: '2026-08-04',
  asunto: 'Cobranza de impuesto predial',
  direccionReferencial: 'CALLE LIMA 418',
  estado: 'REC-1 emitida',
  estadoCodigo: 'REC1_EMITIDA',
  valores: 3,
  insoluto: '7800.00',
  reajuste: '0.00',
  interes: '1612.15',
  gastos: '0.00',
  deudaMateriaDeCobranza: '9412.15',
  costas: '96.00',
  totalExigible: '9508.15',
  deudaAlDia: '2026-09-06',
  valoresImportados: [],
  historial: [],
};

const PROCESO: ProcesoDelExpediente = {
  expediente: EXPEDIENTE,
  actuaciones: [
    {
      actoId: 11,
      tipo: 'REC1',
      titulo: 'RESOLUCION DE EJECUCION COACTIVA',
      numero: '1',
      fecha: '2026-08-04',
      descripcion: 'Inicio del procedimiento',
      medida: null,
      exigibleDesde: '2026-08-11',
      usuario: 'jperez',
      observaciones: 'Se inicia la cobranza',
      diligencias: [],
    },
    {
      actoId: 12,
      tipo: 'REC2',
      titulo: 'RESOLUCION DE MEDIDA CAUTELAR (REC 2)',
      numero: '2',
      fecha: '2026-08-28',
      descripcion: 'Se ordena la medida',
      medida: 'RETENCION BANCARIA',
      exigibleDesde: null,
      usuario: 'jperez',
      observaciones: 'Vencido el plazo del art. 14.1',
      diligencias: [],
    },
  ],
};

const LIQUIDACION: LiquidacionDeCostas = {
  nroLiquidacion: 'LQ-2026-0091',
  expedCoact: '2026-0418',
  ejercicio: 2026,
  fecha: '2026-09-06',
  tributo: 'PREDIAL',
  totalS: '96.00',
  pendienteS: '96.00',
  aLaFecha: '2026-09-16',
  estado: 'ACTIVA',
  conjuntoDeParametros: 3,
  observacion: 'Liquidacion de las costas del expediente',
  usuarioRegistro: 'jperez',
  costas: [
    {
      actoId: 11,
      acto: 'REC1',
      descripcion: 'Resolucion de ejecucion coactiva',
      montoS: '18.00',
      arancelFuente: 'ARANCEL_COSTA:REC1 (Ord. 012-2025)',
    },
    {
      actoId: 12,
      acto: 'REC2',
      descripcion: 'Resolucion de medida cautelar',
      montoS: '78.00',
      arancelFuente: 'ARANCEL_COSTA:REC2 (Ord. 012-2025)',
    },
  ],
};

const PRESCRIPCION: PrescripcionDeclarada = {
  id: 7,
  codContribuyente: '00000000008',
  contribuyente: 'SULLON VILCHEZ-JOSE RAUL',
  tributo: 'PREDIAL',
  ejercicioDesde: 2016,
  ejercicioHasta: 2021,
  fechaDePresentacion: '2026-03-02',
  plazoAplicable: 'DECLARACION_PRESENTADA',
  plazo: '4 ANIOS',
  resultado: 'PROCEDE_EN_PARTE',
  nDeResolucion: 'RES-0041-2026',
  ejerciciosPrescritos: [2016, 2017],
  usuario: 'jperez',
  observacion: 'Solicitud del obligado',
};

function envolver<T>(contenido: readonly T[]): Paginado<T> {
  return {
    contenido,
    pagina: 0,
    tamano: 1,
    totalElementos: contenido.length,
    totalPaginas: 1,
    hayMas: false,
  };
}

// ── El reparto, campo a campo ────────────────────────────────────────────────────────────────

describe('`coa-exp` — el expediente y sus actos', () => {
  const reparto = COA_EXP.repartir(PROCESO as never);

  it('los cinco campos que la operacion publica salen de la respuesta', () => {
    expect(reparto.valores.get(coordenada(0, 0))).toBe('2026-0418');
    expect(reparto.valores.get(coordenada(0, 1))).toBe('00000000008');
    expect(reparto.valores.get(coordenada(0, 3))).toBe('04/08/2026');
  });

  it('y las dos cifras llevan su fecha, que es la regla 9 y no un adorno', () => {
    // No existe «la deuda»: existe `deudaActualizadaA(fecha)` (RNF-075). Las siete cifras del
    // expediente estan a `deudaAlDia`, que es a la que el backend proyecto el interes.
    expect(reparto.valores.get(coordenada(0, 6))).toBe('S/ 9,412.15 · 06/09/2026');
    expect(reparto.valores.get(coordenada(0, 7))).toBe('S/ 96.00 · 06/09/2026');
  });

  it('y con OTRA respuesta sale otro valor: no hay ninguna constante escrita aqui', () => {
    const otro = COA_EXP.repartir({
      ...PROCESO,
      expediente: { ...PROCESO.expediente, deudaMateriaDeCobranza: '1.23', deudaAlDia: '2027-01-31' },
    } as never);

    expect(otro.valores.get(coordenada(0, 6))).toBe('S/ 1.23 · 31/01/2027');
  });

  it('«Documento» dice «no publicado»: el expediente no lo trae, y no se va a buscar al padron', () => {
    expect(reparto.valores.has(coordenada(0, 2))).toBe(false);
    expect(reparto.noPublicados.get(coordenada(0, 2))).toBe(NO_PUBLICADO);
  });

  it('la tabla sale de `actuaciones`, y su columna «Estado» dibuja la MEDIDA', () => {
    const filas = reparto.filas.get(0);
    expect(filas).toHaveLength(2);
    expect(filas?.[0]).toEqual([
      '1',
      'RESOLUCION DE EJECUCION COACTIVA',
      '04/08/2026',
      SIN_DATO,
      SIN_DATO,
    ]);
    // Solo la REC-2 lleva medida; las demas filas dicen la raya y **nunca «Conforme»**, que
    // seria afirmar que el acto surtio efecto sin que nadie lo haya publicado.
    expect(filas?.[1]?.[4]).toBe('RETENCION BANCARIA');
  });

  it('NO empareja la costa de cada acto: la llave ya esta, la operacion no se pide (#200)', () => {
    // Desde #177 `ActoResource` publica `actoId` —el mismo con que `CostaResource` referencia el
    // acto que tarifa—, asi que el cruce es posible; lo que esta hoja no hace todavia es pedir
    // `GET /coactiva/liquidaciones-costas`, que es #200. Lo que NUNCA se hara es emparejar por
    // `tipo`: se rompe el primer dia que un expediente tenga dos EMBARGO —`costa_acto_uq` es por
    // acto, no por tipo— y la costa de uno acabaria escrita en la fila del otro. Una costa es
    // deuda que se le anade al obligado.
    const columnaDeCostas = reparto.filas.get(0)?.map((fila) => fila[3]);
    expect(columnaDeCostas).toEqual([SIN_DATO, SIN_DATO]);
    // Y por si alguien la dedujera igualmente: ninguna celda dice lo que valen esas costas.
    expect(reparto.filas.get(0)?.flat()).not.toContain('18.00');
  });
});

describe('`coa-cost` — las costas liquidadas y el plazo de prescripcion', () => {
  const reparto = COA_COST.repartir({
    liquidacion: LIQUIDACION,
    prescripcion: PRESCRIPCION,
  } as never);

  it('el expediente y las costas tasadas salen de la liquidacion, con su fecha', () => {
    expect(reparto.valores.get(coordenada(0, 0))).toBe('2026-0418');
    // `totalS` es «lo liquidado, congelado a `fecha`»: se PIDE, no se suma sobre la tabla.
    expect(reparto.valores.get(coordenada(0, 2))).toBe('S/ 96.00 · 06/09/2026');
  });

  it('el reloj sale de la prescripcion declarada sobre el MISMO tributo', () => {
    expect(reparto.valores.get(coordenada(0, 5))).toBe('4 ANIOS');
  });

  it('sin ninguna declaracion sobre ese tributo, el reloj dice «no publicado»', () => {
    const sinDeclarar = COA_COST.repartir({
      liquidacion: LIQUIDACION,
      prescripcion: null,
    } as never);

    expect(sinDeclarar.valores.has(coordenada(0, 5))).toBe(false);
    expect(sinDeclarar.noPublicados.get(coordenada(0, 5))).toBe(NO_PUBLICADO);
  });

  it('y los TRES que no se publican lo dicen, en vez de sumarse sobre la pagina', () => {
    // «Actos dictados»: `costas[]` son los actos LIQUIDADOS, y una liquidacion puede cubrir un
    // subconjunto. «Gastos de notificacion»: la liquidacion no los separa de las demas costas.
    // «Total de costas»: el artboard lo escribe como tasadas + gastos, y con un sumando sin
    // publicar escribir aqui `totalS` seria afirmar que los gastos son cero.
    for (const campo of [1, 3, 4]) {
      expect(reparto.noPublicados.get(coordenada(0, campo)), `campo ${campo}`).toBe(NO_PUBLICADO);
      expect(
        reparto.valores.has(coordenada(0, campo)),
        `El campo ${campo} de «coa-cost» trae un valor, y la operacion no lo publica. En costas\n` +
          'eso no es un hueco menos: es deuda que se le anade al obligado, con una cifra que\n' +
          'nadie podria distinguir de una liquidada de verdad.',
      ).toBe(false);
    }
    expect(reparto.valores.size).toBe(3);
    // Las dos costas suman 96.00, que es lo que dice `totalS`. **Que coincidan no las hace lo
    // mismo**: si un dia difieren, nadie sabria que el numero era deducido.
    expect([...reparto.valores.values()]).not.toContain('S/ 96.00');
  });

  it('la tabla sale de `costas[]`, y «Cantidad» dice la raya', () => {
    const filas = reparto.filas.get(0);
    expect(filas).toHaveLength(2);
    expect(filas?.[0]).toEqual([
      'Resolucion de ejecucion coactiva',
      'ARANCEL_COSTA:REC1 (Ord. 012-2025)',
      SIN_DATO,
      '18.00',
    ]);
    expect(pantallaDe('coa-cost').bloques[0]?.tabla?.columnas).toHaveLength(4);
  });
});

describe('`coa-panel` — se mudo de archivo, y sigue pintando UNO de sus cinco', () => {
  const PAGINA: Paginado<DeudaEnCoactiva> = {
    contenido: [],
    pagina: 0,
    tamano: 20,
    totalElementos: 388,
    totalPaginas: 20,
    hayMas: true,
  };
  const reparto = COA_PANEL.repartir(PAGINA as never);

  it('«expedientes abiertos» sale del TOTAL, no del tamano de la pagina', () => {
    // `totalElementos` y no `contenido.length`: la pagina trae veinte de 388, y contar lo que
    // llego daria «20 expedientes abiertos» — un numero exacto y falso.
    expect(reparto.valores.get(coordenada(0, 1))).toBe('388');
    expect(reparto.valores.get(coordenada(0, 1))).not.toBe('20');
  });

  it('y los otros cuatro se declaran «no publicado», como antes de la mudanza', () => {
    // #170 no los toca: se podrian deducir de `ultimaActuacion.acto` y `totalS`, pero **solo
    // sobre la pagina que llego**, que es justo lo prohibido.
    for (const campo of [2, 3, 4, 5]) {
      expect(reparto.noPublicados.get(coordenada(0, campo)), `campo ${campo}`).toBe(NO_PUBLICADO);
    }
    expect(reparto.valores.size).toBe(1);
  });
});

// ── La pantalla montada: se cambia el doble y lo que se ve cambia (AC3) ──────────────────────

/** Lo que cada una de las cuatro lecturas contesta en esta prueba. Se cambia una cada vez. */
interface Instalacion {
  readonly cartera: readonly { readonly numero: string }[];
  readonly proceso: ProcesoDelExpediente;
  readonly liquidaciones: readonly LiquidacionDeCostas[];
  readonly prescripciones: readonly PrescripcionDeclarada[];
}

const COMO_LLEGA: Instalacion = {
  cartera: [{ numero: EXPEDIENTE.numero }],
  proceso: PROCESO,
  liquidaciones: [LIQUIDACION],
  prescripciones: [PRESCRIPCION],
};

/** Las URL que se pidieron, en orden. Es lo que dice si la segunda lectura se acoto bien. */
let pedidas: string[] = [];

function contesta(instalacion: Instalacion) {
  pedidas = [];
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      pedidas.push(url);
      const json = (cuerpo: unknown) =>
        Promise.resolve(
          new Response(JSON.stringify(cuerpo), {
            status: 200,
            headers: { 'content-type': 'application/json' },
          }),
        );
      // El proceso PRIMERO: `/coactiva/expedientes` es prefijo suyo.
      if (url.includes('/proceso')) return json(instalacion.proceso);
      if (url.includes('/coactiva/expedientes')) return json(envolver(instalacion.cartera));
      if (url.includes('/coactiva/liquidaciones-costas')) {
        return json(envolver(instalacion.liquidaciones));
      }
      if (url.includes('/coactiva/prescripcion')) return json(envolver(instalacion.prescripciones));
      return Promise.resolve(new Response('{}', { status: 404 }));
    }),
  );
}

function Hoja({ clave }: { readonly clave: ClaveDeHoja }) {
  return <PantallaDeRentas definicion={pantallaDe(clave)} datos={useDatosDeLaHoja(clave)} />;
}

/** Monta una hoja con su propio cliente de consultas: compartido, la cache de una serviria a otra. */
async function dibujar(clave: ClaveDeHoja, instalacion: Instalacion) {
  contesta(instalacion);
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const marco = ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
  const { unmount } = render(<Hoja clave={clave} />, { wrapper: marco });
  // Se espera al dato y no a un tiempo: montar y mirar en la misma vuelta mediria «pidiendo…».
  await waitFor(() => {
    expect(screen.queryByText('pidiendo…')).toBeNull();
  });
  return unmount;
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('`coa-exp` dibujada: lo que se ve cambia con la respuesta, no con la definicion', () => {
  it('ensena la deuda que llego, con su fecha, y NO la cifra del artboard', async () => {
    await dibujar('coa-exp', COMO_LLEGA);

    expect(screen.getByText('S/ 9,412.15 · 06/09/2026')).toBeInTheDocument();
    expect(screen.getByDisplayValue('2026-0418')).toBeInTheDocument();
    // El artboard escribe «DNI 02718844» en «Documento». Aqui dice lo que pasa de verdad.
    expect(screen.queryByText('DNI 02718844')).toBeNull();
    expect(screen.getAllByText('no publicado').length).toBeGreaterThan(0);
  });

  it('LA ROTURA: se cambia el proceso del doble y la pantalla ensena la otra cifra', async () => {
    const unmount = await dibujar('coa-exp', COMO_LLEGA);
    unmount();

    await dibujar('coa-exp', {
      ...COMO_LLEGA,
      proceso: {
        expediente: { ...EXPEDIENTE, deudaMateriaDeCobranza: '1.23', deudaAlDia: '2027-01-31' },
        actuaciones: [{ ...PROCESO.actuaciones[0]!, titulo: 'ACTA DE EMBARGO' }],
      },
    });

    expect(screen.getByText('S/ 1.23 · 31/01/2027')).toBeInTheDocument();
    expect(screen.queryByText('S/ 9,412.15 · 06/09/2026')).toBeNull();
    expect(screen.getByText('ACTA DE EMBARGO')).toBeInTheDocument();
  });

  it('y la CARTERA decide cual expediente se pide: otro numero, otra peticion', async () => {
    // Es lo que demuestra que `GET /coactiva/expedientes` mueve lo suyo y no es decorativa: de
    // ella sale el `{numero}` con que se pide el proceso.
    await dibujar('coa-exp', { ...COMO_LLEGA, cartera: [{ numero: '2025-0007' }] });

    expect(pedidas.some((url) => url.includes('/coactiva/expedientes/2025-0007/proceso'))).toBe(
      true,
    );
  });

  it('y con la cartera vacia no pide ningun proceso: dice que no hay, y no falla', async () => {
    await dibujar('coa-exp', { ...COMO_LLEGA, cartera: [] });

    expect(pedidas.some((url) => url.includes('/proceso'))).toBe(false);
    expect(screen.getAllByText('sin datos').length).toBeGreaterThan(0);
  });
});

describe('`coa-cost` dibujada: cada una de sus dos lecturas mueve lo suyo', () => {
  it('ensena las costas tasadas y el plazo, los dos de donde vienen', async () => {
    await dibujar('coa-cost', COMO_LLEGA);

    expect(screen.getByText('S/ 96.00 · 06/09/2026')).toBeInTheDocument();
    expect(screen.getByText('4 ANIOS')).toBeInTheDocument();
    expect(screen.getByText('ARANCEL_COSTA:REC1 (Ord. 012-2025)')).toBeInTheDocument();
    // La prescripcion se pidio ACOTADA al tributo de la liquidacion, que es la unica llave que
    // las dos comparten. Sin acotar, la declaracion de cualquiera se leeria como suya.
    expect(pedidas.some((url) => url.includes('/coactiva/prescripcion?tributo=PREDIAL'))).toBe(
      true,
    );
  });

  it('LA ROTURA: cambia la liquidacion y cambian las costas, no el reloj', async () => {
    const unmount = await dibujar('coa-cost', COMO_LLEGA);
    unmount();

    await dibujar('coa-cost', {
      ...COMO_LLEGA,
      liquidaciones: [{ ...LIQUIDACION, totalS: '250.50', fecha: '2027-02-01' }],
    });

    expect(screen.getByText('S/ 250.50 · 01/02/2027')).toBeInTheDocument();
    expect(screen.queryByText('S/ 96.00 · 06/09/2026')).toBeNull();
    expect(screen.getByText('4 ANIOS')).toBeInTheDocument();
  });

  it('LA ROTURA: cambia la prescripcion y cambia el reloj, no las costas', async () => {
    const unmount = await dibujar('coa-cost', COMO_LLEGA);
    unmount();

    await dibujar('coa-cost', {
      ...COMO_LLEGA,
      prescripciones: [{ ...PRESCRIPCION, plazo: '6 ANIOS' }],
    });

    expect(screen.getByText('6 ANIOS')).toBeInTheDocument();
    expect(screen.queryByText('4 ANIOS')).toBeNull();
    expect(screen.getByText('S/ 96.00 · 06/09/2026')).toBeInTheDocument();
  });
});
