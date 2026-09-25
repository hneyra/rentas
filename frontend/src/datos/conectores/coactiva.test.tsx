import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { coordenada } from '@kamayuk/ui';

import type { Reparto } from '../conectores.ts';
import { NO_PUBLICADO } from '../conectores.ts';
import type {
  LiquidacionDeCostas,
  Paginado,
  PrescripcionDeclarada,
  ProcesoDelExpediente,
  ResumenDeLaCarteraCoactiva,
} from '../lecturas.ts';
import { useDatosDeLaHoja } from '../useDatosDeLaHoja.ts';
import type { ClaveDeHoja } from '../../pantallas/arbol.ts';
import { bloquesDe } from '../../pantallas/bloques.ts';
import { pantallaDe } from '../../pantallas/definiciones/index.ts';
import { PantallaDeRentas } from '../../pantallas/PantallaDeRentas.tsx';
import {
  COA_COST,
  COA_EXP,
  COA_PANEL,
  SIN_CANTIDAD,
  SIN_MEDIDA,
  costasPorActo,
  plazoDelObligado,
  sinDato,
} from './coactiva.ts';

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
  // El reloj de los seis ejercicios del rango (#230). `coa-cost` no lo dibuja —lee el `plazo` y el
  // resultado—, y se declara porque el tipo lo exige: un campo declarado es un campo que el
  // proveedor no puede retirar sin poner rojo este build.
  ejercicios: [
    { ejercicio: 2016, prescribeEl: '2021-01-01', prescrita: true },
    { ejercicio: 2017, prescribeEl: '2022-01-01', prescrita: true },
    { ejercicio: 2018, prescribeEl: '2023-01-01', prescrita: false },
    { ejercicio: 2019, prescribeEl: '2024-01-01', prescrita: false },
    { ejercicio: 2020, prescribeEl: '2025-01-01', prescrita: false },
    { ejercicio: 2021, prescribeEl: '2026-01-01', prescrita: false },
  ],
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

/** Las celdas de cada fila de una tabla con `clave`. Las dos de Coactiva lo son desde #195. */
const celdasDe = (reparto: Reparto, clave: string) =>
  (reparto.tablas?.get(clave)?.filas ?? []).map((fila) => fila.celdas);

describe('`coa-exp` — el expediente, sus actos y la costa de cada uno', () => {
  const reparto = COA_EXP.repartir({
    proceso: PROCESO,
    costas: costasPorActo([LIQUIDACION]),
  } as never);

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
      proceso: {
        ...PROCESO,
        expediente: {
          ...PROCESO.expediente,
          deudaMateriaDeCobranza: '1.23',
          deudaAlDia: '2027-01-31',
        },
      },
      costas: costasPorActo([LIQUIDACION]),
    } as never);

    expect(otro.valores.get(coordenada(0, 6))).toBe('S/ 1.23 · 31/01/2027');
  });

  it('«Documento» dice «no publicado»: el expediente no lo trae, y no se va a buscar al padron', () => {
    expect(reparto.valores.has(coordenada(0, 2))).toBe(false);
    expect(reparto.noPublicados.get(coordenada(0, 2))).toBe(NO_PUBLICADO);
  });

  it('la tabla sale de `actuaciones`, y su columna «Estado» dibuja la MEDIDA', () => {
    const filas = celdasDe(reparto, 'actos-del-expediente');
    expect(filas).toHaveLength(2);
    expect(filas[0]).toEqual([
      '1',
      'RESOLUCION DE EJECUCION COACTIVA',
      '04/08/2026',
      '18.00',
      sinDato(SIN_MEDIDA),
    ]);
    // Solo la REC-2 lleva medida; las demas dicen que no hay dato **y por que** (#195) y nunca
    // «Conforme», que seria afirmar que el acto surtio efecto sin que nadie lo haya publicado.
    expect(filas[1]?.[4]).toBe('RETENCION BANCARIA');
  });

  it('LA COLUMNA «Costa S/» se cruza por `actoId`, y cada costa cae en SU fila (#200)', () => {
    // Desde #177 `ActoResource` publica `actoId` —el mismo con que `CostaResource` referencia el
    // acto que tarifa—. 18.00 es la del REC1 (actoId 11) y 78.00 la del REC2 (actoId 12).
    expect(celdasDe(reparto, 'actos-del-expediente').map((fila) => fila[3])).toEqual([
      '18.00',
      '78.00',
    ]);
  });

  it('LA ROTURA DE #200: con DOS actos del MISMO tipo, cada costa sigue cayendo en su fila', () => {
    // Es el caso que hacia indistinguible el par por `tipo`, y el que `LaCostaCaeEnSuActoTest` del
    // backend prueba. Emparejando por tipo, las dos filas dirian la MISMA costa —la primera que
    // casara— y una costa es deuda que se le anade al obligado: ponerla en la fila equivocada es
    // peor que no ponerla.
    const dosEmbargos = {
      ...PROCESO,
      actuaciones: [
        { ...PROCESO.actuaciones[0]!, actoId: 21, tipo: 'EMBARGO', numero: '3' },
        { ...PROCESO.actuaciones[1]!, actoId: 22, tipo: 'EMBARGO', numero: '4', medida: null },
      ],
    };
    const susCostas = {
      ...LIQUIDACION,
      costas: [
        { ...LIQUIDACION.costas[0]!, actoId: 21, acto: 'EMBARGO', montoS: '40.00' },
        { ...LIQUIDACION.costas[1]!, actoId: 22, acto: 'EMBARGO', montoS: '55.00' },
      ],
    };
    const cruzado = COA_EXP.repartir({
      proceso: dosEmbargos,
      costas: costasPorActo([susCostas]),
    } as never);

    expect(celdasDe(cruzado, 'actos-del-expediente').map((fila) => fila[3])).toEqual([
      '40.00',
      '55.00',
    ]);
  });

  it('un acto que ninguna liquidacion tarifa dice su motivo, y NO un cero', () => {
    // Cero significa «el arancel dice que no cuesta nada». Lo que pasa es que no se ha liquidado,
    // y las dos cosas se cobran distinto.
    const sinLiquidar = COA_EXP.repartir({
      proceso: PROCESO,
      costas: costasPorActo([{ ...LIQUIDACION, costas: [] }]),
    } as never);
    const columna = celdasDe(sinLiquidar, 'actos-del-expediente').map((fila) => fila[3]);

    for (const celda of columna) {
      expect(celda).toMatchObject({ texto: null });
      expect(JSON.stringify(celda)).toContain('no se ha liquidado');
    }
    expect(JSON.stringify(columna)).not.toContain('0.00');
  });

  it('y la costa se toma de CUALQUIERA de las liquidaciones del expediente, sin sumarlas', () => {
    // Un expediente puede tener varias tandas de liquidacion y `costa_acto_uq` garantiza que un
    // acto se tarifa UNA vez: lo que hay que hacer es recorrerlas, no sumarlas — sumar dos
    // importes servidos en el navegador es aritmetica sobre dinero (regla 1).
    const enDosTandas = costasPorActo([
      { ...LIQUIDACION, costas: [LIQUIDACION.costas[0]!] },
      { ...LIQUIDACION, nroLiquidacion: 'LQ-2026-0092', costas: [LIQUIDACION.costas[1]!] },
    ]);
    const cruzado = COA_EXP.repartir({ proceso: PROCESO, costas: enDosTandas } as never);

    expect(celdasDe(cruzado, 'actos-del-expediente').map((fila) => fila[3])).toEqual([
      '18.00',
      '78.00',
    ]);
  });

  it('y si DOS liquidaciones tarifan el mismo acto, la celda lo dice en vez de elegir una', () => {
    // `costa_acto_uq` dice que no puede pasar. Si pasara, elegir una pondria un importe plausible
    // donde hay una contradiccion, en una columna que es deuda del obligado.
    const repetido = costasPorActo([LIQUIDACION, LIQUIDACION]);
    const cruzado = COA_EXP.repartir({ proceso: PROCESO, costas: repetido } as never);

    expect(repetido.seSupo).toBe(false);
    for (const fila of celdasDe(cruzado, 'actos-del-expediente')) {
      expect(fila[3]).toMatchObject({ texto: null });
      expect(JSON.stringify(fila[3])).toContain('costa_acto_uq');
    }
  });
});

describe('`coa-cost` — las costas liquidadas y el plazo de prescripcion', () => {
  const reparto = COA_COST.repartir({
    liquidacion: LIQUIDACION,
    obligado: EXPEDIENTE.codContribuyente,
    declaradas: envolver([PRESCRIPCION]),
  } as never);

  it('el expediente y las costas tasadas salen de la liquidacion, con su fecha', () => {
    expect(reparto.valores.get(coordenada(0, 0))).toBe('2026-0418');
    // `totalS` es «lo liquidado, congelado a `fecha`»: se PIDE, no se suma sobre la tabla.
    expect(reparto.valores.get(coordenada(0, 2))).toBe('S/ 96.00 · 06/09/2026');
  });

  it('el reloj sale de la prescripcion declarada por el OBLIGADO sobre el mismo tributo', () => {
    expect(reparto.valores.get(coordenada(0, 5))).toBe('4 ANIOS');
  });

  it('sin ninguna declaracion del obligado sobre ese tributo, el reloj dice «no publicado»', () => {
    const sinDeclarar = COA_COST.repartir({
      liquidacion: LIQUIDACION,
      obligado: EXPEDIENTE.codContribuyente,
      declaradas: envolver([]),
    } as never);

    expect(sinDeclarar.valores.has(coordenada(0, 5))).toBe(false);
    expect(sinDeclarar.noPublicados.get(coordenada(0, 5))).toBe(NO_PUBLICADO);
  });

  it('LA DEFENSA DE #386: una declaracion de OTRO obligado no se escribe, aunque llegue', () => {
    // La ruta ya pide `?codContribuyente=`. Si aun asi llega la de otro, el filtro no se aplico,
    // y al lado de un expediente su plazo se leeria como del obligado.
    const deOtro = COA_COST.repartir({
      liquidacion: LIQUIDACION,
      obligado: EXPEDIENTE.codContribuyente,
      declaradas: envolver([{ ...PRESCRIPCION, codContribuyente: '00000000031', plazo: '6 ANIOS' }]),
    } as never);

    expect(deOtro.valores.has(coordenada(0, 5))).toBe(false);
    expect(deOtro.noPublicados.get(coordenada(0, 5))).toBe(NO_PUBLICADO);
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

  it('la tabla sale de `costas[]`, y «Cantidad» dice POR QUE no hay dato (#195)', () => {
    const filas = (reparto.tablas?.get('costas-por-acto')?.filas ?? []).map((f) => f.celdas);
    expect(filas).toHaveLength(2);
    expect(filas[0]).toEqual([
      'Resolucion de ejecucion coactiva',
      'ARANCEL_COSTA:REC1 (Ord. 012-2025)',
      sinDato(SIN_CANTIDAD),
      '18.00',
    ]);
    expect(bloquesDe(pantallaDe('coa-cost'))[0]?.tabla?.columnas).toHaveLength(4);
  });
});

describe('`plazoDelObligado` — un plazo, y solo si es uno y del obligado (#386)', () => {
  const OBLIGADO = EXPEDIENTE.codContribuyente;

  it('varias declaraciones del obligado con el MISMO plazo lo dicen', () => {
    expect(plazoDelObligado(OBLIGADO, envolver([PRESCRIPCION, { ...PRESCRIPCION, id: 8 }]))).toBe(
      '4 ANIOS',
    );
  });

  it('con plazos distintos no elige ninguno', () => {
    const dos = envolver([PRESCRIPCION, { ...PRESCRIPCION, id: 8, plazo: '6 ANIOS' }]);
    expect(plazoDelObligado(OBLIGADO, dos)).toBeNull();
  });

  it('con `hayMas` tampoco: lo que no llego puede ser la declaracion que difiere', () => {
    expect(plazoDelObligado(OBLIGADO, { ...envolver([PRESCRIPCION]), hayMas: true })).toBeNull();
  });

  it('una sola fila de otro obligado tumba la respuesta entera, aunque las demas sean suyas', () => {
    const mezcladas = envolver([PRESCRIPCION, { ...PRESCRIPCION, codContribuyente: '00000000031' }]);
    expect(plazoDelObligado(OBLIGADO, mezcladas)).toBeNull();
  });

  it('y una declaracion sin contribuyente no es del obligado', () => {
    expect(
      plazoDelObligado(OBLIGADO, envolver([{ ...PRESCRIPCION, codContribuyente: null }])),
    ).toBeNull();
  });
});

/**
 * Lo que `coa-panel` recibe desde #272. **Las cifras NO son las del artboard** —1 184 / 796 / 412
 * / 388—: una muestra que las repitiera no distinguiria la pantalla que dibuja lo que llego de la
 * que dibuja lo que su definicion ya decia.
 *
 * Y no cuadran entre si a proposito: 12 + 9 + 5 = 26, y `abiertos` dice 37. La diferencia son los
 * que estan en REC-1 emitida, REC-2 emitida o suspendidos —7 + 3 + 1—, que estan abiertos y no
 * son ninguna de las tres etapas que el panel nombra. Si el conector intentara cuadrarlas, esto
 * saldria rojo.
 */
const RESUMEN: ResumenDeLaCarteraCoactiva = {
  aLaFecha: '2026-09-20',
  ejercicio: null,
  expedientes: 41,
  abiertos: 37,
  sinRec: 12,
  conRecNotificada: 9,
  conMedidaCautelar: 5,
  porEtapa: [
    { etapa: 'INICIADO', codigo: '000', etiqueta: 'INICIADO', expedientes: 12 },
    { etapa: 'REC1_EMITIDA', codigo: '011', etiqueta: 'REC 01 EMITIDO', expedientes: 7 },
    { etapa: 'REC1_NOTIFICADA', codigo: '012', etiqueta: 'REC 01 NOTIFICADA', expedientes: 9 },
    { etapa: 'REC2_EMITIDA', codigo: '021', etiqueta: 'REC 02 EMITIDA', expedientes: 3 },
    { etapa: 'MEDIDA_CAUTELAR', codigo: '031', etiqueta: 'MEDIDA CAUTELAR', expedientes: 5 },
    { etapa: 'SUSPENDIDO', codigo: '041', etiqueta: 'SUSPENDIDO', expedientes: 1 },
    { etapa: 'CONCLUIDO', codigo: '051', etiqueta: 'CONCLUIDO', expedientes: 4 },
  ],
};

describe('`coa-panel` — cuatro de sus cinco, y el rotulo que #272 corrigio', () => {
  const reparto = COA_PANEL.repartir(RESUMEN as never);

  it('«Expedientes abiertos» dice `abiertos`, NO el total de la cartera', () => {
    // Es el defecto que #272 midio: hasta entonces este campo salia del `totalElementos` de
    // `GET /coactiva/deudas`, que cuenta TODOS los expedientes —concluidos incluidos— bajo un
    // rotulo que promete los abiertos. Los dos numeros llegan ahora por separado, y el que se
    // dibuja es el que el rotulo nombra.
    expect(reparto.valores.get(coordenada(0, 1))).toBe('37');
    expect(reparto.valores.get(coordenada(0, 1))).not.toBe('41');
  });

  it('cada etapa va a SU campo, y no se reparten de cualquier manera', () => {
    expect(reparto.valores.get(coordenada(0, 2))).toBe('9');
    expect(reparto.valores.get(coordenada(0, 3))).toBe('5');
    expect(reparto.valores.get(coordenada(0, 4))).toBe('12');
  });

  it('y aqui no se suma nada: «abiertos» no es la suma de las tres etapas', () => {
    // 12 + 9 + 5 = 26, y el campo dice 37. Cuadrarlo seria inventarse los expedientes que estan
    // en REC-1 emitida, REC-2 emitida o suspendidos, que la operacion publica en `porEtapa` y
    // esta pantalla no tiene donde dibujar.
    const sumaDeLasTres = RESUMEN.sinRec + RESUMEN.conRecNotificada + RESUMEN.conMedidaCautelar;
    expect(String(sumaDeLasTres)).not.toBe(reparto.valores.get(coordenada(0, 1)));
  });

  it('«Deuda en cartera» sigue siendo el unico «no publicado», con su motivo', () => {
    // Ninguna operacion del contrato publica la deuda de la cartera: componerla costaria una
    // lectura del libro por expediente y contaria dos veces la obligacion que dos expedientes del
    // mismo obligado formalizaran. Sumar `totalS` de la pagina de `/coactiva/deudas` daria un
    // numero indistinguible de uno real, que es justo lo que `conectores.ts` prohibe.
    expect(reparto.noPublicados.get(coordenada(0, 5))).toBe(NO_PUBLICADO);
    expect(reparto.noPublicados.size).toBe(1);
    expect(reparto.valores.size).toBe(4);
  });
});

// ── La pantalla montada: se cambia el doble y lo que se ve cambia (AC3) ──────────────────────

/** Lo que cada una de las cuatro lecturas contesta en esta prueba. Se cambia una cada vez. */
interface Instalacion {
  readonly cartera: readonly { readonly numero: string }[];
  readonly proceso: ProcesoDelExpediente;
  readonly liquidaciones: readonly LiquidacionDeCostas[];
  readonly prescripciones: readonly PrescripcionDeclarada[];
  readonly resumen: ResumenDeLaCarteraCoactiva;
  /** El backend descuidado: filtra las prescripciones por tributo y NO por obligado (#386). */
  readonly ignoraElObligado?: boolean;
}

const COMO_LLEGA: Instalacion = {
  cartera: [{ numero: EXPEDIENTE.numero }],
  proceso: PROCESO,
  liquidaciones: [LIQUIDACION],
  prescripciones: [PRESCRIPCION],
  resumen: RESUMEN,
};

/**
 * **La relacion de prescripciones como la contesta el backend, y no la misma lista a todo** (#386).
 *
 * Hasta #386 el doble contestaba `instalacion.prescripciones` a cualquier `/coactiva/prescripcion`,
 * y con una sola declaracion de muestra —del mismo obligado que el expediente— la prueba no podia
 * distinguir una lectura acotada por el obligado de una acotada por el tributo: las dos recibian la
 * misma fila. Aqui se hace lo que hace `PrescripcionController`: filtrar por `?codContribuyente=`
 * y `?tributo=` **leidos de la URL**, ordenar por fecha de presentacion en sentido ascendente —el
 * orden por omision de `ParametrosDePaginacion`— y cortar por `?tamano=`, diciendo `hayMas`.
 *
 * `ignoraElObligado` es el backend descuidado: filtra por tributo y no por obligado. Es lo que
 * ejerce la defensa en profundidad de `repartir`.
 */
function relacionDePrescripciones(url: string, instalacion: Instalacion) {
  const consulta = new URL(url, 'http://doble').searchParams;
  const obligado = instalacion.ignoraElObligado ? null : consulta.get('codContribuyente');
  const tributo = consulta.get('tributo');
  const tamano = Number(consulta.get('tamano') ?? '20');
  const filtradas = instalacion.prescripciones
    .filter((d) => obligado === null || d.codContribuyente === obligado)
    .filter((d) => tributo === null || d.tributo === tributo)
    .sort((a, b) => a.fechaDePresentacion.localeCompare(b.fechaDePresentacion));
  return {
    contenido: filtradas.slice(0, tamano),
    pagina: 0,
    tamano,
    totalElementos: filtradas.length,
    totalPaginas: Math.max(1, Math.ceil(filtradas.length / tamano)),
    hayMas: filtradas.length > tamano,
  };
}

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
      // El resumen PRIMERO: no es prefijo de nadie, pero dejarlo detras invitaria a que alguien
      // lo colara bajo `/coactiva/expedientes` el dia que la ruta cambie.
      if (url.includes('/coactiva/cartera/resumen')) return json(instalacion.resumen);
      // El proceso PRIMERO: `/coactiva/expedientes` es prefijo suyo.
      if (url.includes('/proceso')) return json(instalacion.proceso);
      if (url.includes('/coactiva/expedientes')) return json(envolver(instalacion.cartera));
      if (url.includes('/coactiva/liquidaciones-costas')) {
        return json(envolver(instalacion.liquidaciones));
      }
      if (url.includes('/coactiva/prescripcion')) {
        return json(relacionDePrescripciones(url, instalacion));
      }
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
    // La prescripcion se pidio ACOTADA al obligado del expediente y al tributo de la liquidacion
    // (#386). Acotada solo por tributo, la declaracion de cualquiera se leeria como suya.
    expect(
      pedidas.some((url) =>
        url.includes('/coactiva/prescripcion?codContribuyente=00000000008&tributo=PREDIAL'),
      ),
    ).toBe(true);
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

/**
 * **La declaracion de OTRO obligado sobre el mismo tributo, y mas antigua** (#386).
 *
 * Es la siembra que distingue. El obligado del expediente `2026-0418` es `00000000008`, que
 * DECLARO el predial: su plazo es «4 ANIOS». Esta es de `00000000031`, que no declaro —«6 ANIOS»—,
 * y se presento el 10/02/2025, antes que la del 008: en el orden por omision del backend es la
 * PRIMERA de la relacion de PREDIAL. Una lectura acotada por tributo la recibe a ella.
 */
const DE_OTRO_OBLIGADO: PrescripcionDeclarada = {
  ...PRESCRIPCION,
  id: 3,
  codContribuyente: '00000000031',
  contribuyente: 'GARCIA NUNEZ-ROSA ELENA',
  fechaDePresentacion: '2025-02-10',
  plazoAplicable: 'SIN_DECLARACION',
  plazo: '6 ANIOS',
  nDeResolucion: 'RES-0007-2025',
};

describe('`coa-cost` dibujada: el reloj es el del OBLIGADO del expediente, no el del tributo (#386)', () => {
  it('con una declaracion de otro sobre el mismo tributo, pinta la del obligado: 4 ANIOS', async () => {
    await dibujar('coa-cost', {
      ...COMO_LLEGA,
      prescripciones: [DE_OTRO_OBLIGADO, PRESCRIPCION],
    });

    expect(screen.getByText('4 ANIOS')).toBeInTheDocument();
    expect(screen.queryByText('6 ANIOS')).toBeNull();
    // El sujeto de la segunda lectura sale de la primera: el proceso del expediente de la
    // liquidacion, y de el el obligado.
    expect(pedidas.some((url) => url.includes('/coactiva/expedientes/2026-0418/proceso'))).toBe(
      true,
    );
    expect(
      pedidas.some(
        (url) =>
          url.includes('/coactiva/prescripcion?') && url.includes('codContribuyente=00000000008'),
      ),
    ).toBe(true);
  });

  it('si el obligado no declaro nada, el reloj dice «no publicado» y NUNCA el plazo de otro', async () => {
    await dibujar('coa-cost', { ...COMO_LLEGA, prescripciones: [DE_OTRO_OBLIGADO] });

    expect(screen.queryByText('6 ANIOS')).toBeNull();
    expect(screen.queryByText('4 ANIOS')).toBeNull();
    // Los tres de siempre y el reloj.
    expect(screen.getAllByText('no publicado')).toHaveLength(4);
  });

  it('si el obligado tiene dos declaraciones con plazos distintos, no se elige una: «no publicado»', async () => {
    await dibujar('coa-cost', {
      ...COMO_LLEGA,
      prescripciones: [
        PRESCRIPCION,
        {
          ...PRESCRIPCION,
          id: 9,
          fechaDePresentacion: '2026-05-20',
          plazoAplicable: 'SIN_DECLARACION',
          plazo: '6 ANIOS',
        },
      ],
    });

    expect(screen.queryByText('4 ANIOS')).toBeNull();
    expect(screen.queryByText('6 ANIOS')).toBeNull();
    expect(screen.getAllByText('no publicado')).toHaveLength(4);
  });

  it('y si todas las del obligado dicen el MISMO plazo, lo pinta: se pide su relacion, no una fila', async () => {
    // Con `?tamano=1` —o `2`— llegaria una pagina con `hayMas`, y el campo diria «no publicado»
    // aunque las tres declaraciones digan lo mismo. La otra, la del 031, no cuenta.
    await dibujar('coa-cost', {
      ...COMO_LLEGA,
      prescripciones: [
        DE_OTRO_OBLIGADO,
        PRESCRIPCION,
        { ...PRESCRIPCION, id: 10, fechaDePresentacion: '2026-04-15' },
        { ...PRESCRIPCION, id: 11, fechaDePresentacion: '2026-06-30' },
      ],
    });

    expect(screen.getByText('4 ANIOS')).toBeInTheDocument();
    expect(screen.queryByText('6 ANIOS')).toBeNull();
  });

  it('y si el backend no filtrara por obligado, la defensa de `repartir` no pinta el de otro', async () => {
    // Solo la del 031: sin la defensa, un backend que no filtra por obligado entregaria UNA
    // declaracion con UN plazo, y nada mas la distinguiria de la del obligado.
    await dibujar('coa-cost', {
      ...COMO_LLEGA,
      prescripciones: [DE_OTRO_OBLIGADO],
      ignoraElObligado: true,
    });

    expect(screen.queryByText('6 ANIOS')).toBeNull();
    expect(screen.getAllByText('no publicado')).toHaveLength(4);
  });
});

describe('`coa-panel` dibujada: ensena lo que llego, y no las cifras del artboard (#272)', () => {
  it('las cuatro cifras son las de la respuesta, no las 1.184 / 796 / 412 / 388 del artboard', async () => {
    await dibujar('coa-panel', COMO_LLEGA);

    expect(screen.getByText('37')).toBeInTheDocument();
    expect(screen.getByText('9')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getByText('12')).toBeInTheDocument();
    // Las del artboard no se ven por ningun lado: si se vieran, la pantalla estaria dibujando su
    // definicion y no el dato.
    for (const delArtboard of ['1,184', '796', '412', '388']) {
      expect(screen.queryByText(delArtboard), delArtboard).toBeNull();
    }
    // Y «Deuda en cartera» dice por que no hay dato, en vez de un cero o una raya muda.
    expect(screen.getAllByText('no publicado').length).toBe(1);
  });

  it('LA ROTURA: se cambia el resumen del doble y las cuatro cifras cambian con el', async () => {
    const unmount = await dibujar('coa-panel', COMO_LLEGA);
    unmount();

    await dibujar('coa-panel', {
      ...COMO_LLEGA,
      resumen: {
        ...RESUMEN,
        abiertos: 601,
        conRecNotificada: 214,
        conMedidaCautelar: 77,
        sinRec: 310,
      },
    });

    expect(screen.getByText('601')).toBeInTheDocument();
    expect(screen.getByText('214')).toBeInTheDocument();
    expect(screen.getByText('77')).toBeInTheDocument();
    expect(screen.getByText('310')).toBeInTheDocument();
    expect(screen.queryByText('37')).toBeNull();
  });

  it('y NO pide `GET /coactiva/deudas`: esa cifra ya no sale de ahi', async () => {
    // Era su unica lectura hasta #272, y de su `totalElementos` salia «Expedientes abiertos» —un
    // total que cuenta TODOS los expedientes, concluidos incluidos—. Si volviera a pedirse, o
    // bien se estaria contando otra vez lo mismo o bien se estaria componiendo en el cliente.
    await dibujar('coa-panel', COMO_LLEGA);

    expect(pedidas.some((url) => url.includes('/coactiva/cartera/resumen'))).toBe(true);
    expect(pedidas.some((url) => url.includes('/coactiva/deudas'))).toBe(false);
  });
});
