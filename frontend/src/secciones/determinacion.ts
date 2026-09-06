import { sumarImportes, mismosCentimos } from '../dominio/aritmetica.ts';
import { compararImportes, formatearImporte } from '../dominio/formato.ts';
import type {
  ArbitrioServido,
  DeterminacionDeAlcabala,
  DeterminacionDeEspectaculo,
  DeterminacionIndividual,
  DeterminacionVehicular,
  EtapaDeLaCorrida,
  ImporteConFecha,
} from '../datos/lecturas.ts';

/**
 * Los seis tipos de determinacion, y como se compone la memoria de cada uno.
 *
 * <h2>Que se porta del artboard y que se lee de la respuesta</h2>
 *
 * Del artboard —`NODOS` (`:1089`) y `DETERMINACIONES` (`:1095`)— se porta el **cuadro**: el
 * titulo de cada tipo, su nota explicativa, sus columnas con la marca de alineacion numerica,
 * el operador de cada fila y el nombre de cada concepto. Eso es el diseno de la tabla, y vive
 * aqui por lo mismo que `expediente.ts` guarda los rotulos del formulario: una pantalla **no
 * puede importar `datos/prototipo.ts`**, porque `arranque.ts` carga el proxy con un `import()`
 * dinamico detras de una bandera para que un `yarn build` de produccion no lleve dentro ni una
 * cifra del artboard (AC3 de #4, medido en bytes).
 *
 * De la **respuesta** sale todo lo demas: las cifras, las fechas, los porcentajes, los rotulos
 * de tramo y los detalles que dicen algo del dato. Que la definicion siga siendo la del
 * artboard lo comprueba `verificaciones/determinacion-y-valores-del-artboard.test.ts` leyendo
 * el `.dc.html`; que las cifras salgan del proxy y no de aqui lo comprueba `Determinacion.test.tsx`.
 *
 * <h2>Lo que se DERIVA, y no se copia (AC4)</h2>
 *
 * El artboard escribe once detalles que no son rotulos sino **afirmaciones sobre el dato**:
 * «2 predios, al 100 % y al 50 %», «S/ 80,250.00 del afecto», «Suma de los tres tramos», «En 4
 * cuotas trimestrales de S/ 147.98», «Sin beneficio aplicado este ejercicio», «Comprobación: el
 * impuesto lo supera». Copiarlas dejaria una pantalla que **se ve identica y esta muerta**: el
 * dia que el contribuyente tuviera tres predios seguiria diciendo dos. Aqui se componen de la
 * respuesta, y por eso `filasDelPredialIndividual` es una funcion pura que se puede llamar con
 * otra respuesta y comprobar que dice otra cosa.
 *
 * <h2>La pantalla no suma: pide, y comprueba</h2>
 *
 * El insoluto y el total se dibujan tal como los publica el backend —«pidelo, no lo sumes»,
 * que es lo que dice la prohibicion `aritmetica-con-importes` de ESLint— y ademas se
 * **comprueba** que cuadren con las filas que la propia tabla ensena: la suma de los tres
 * tramos tiene que ser el insoluto, y el insoluto mas el derecho de emision, el total. Si no
 * cuadran, la pantalla lo dice en vez de dibujar un total que miente sobre sus sumandos. Ver
 * `cuadreDelPredial`; quien deriva es el backend, y mientras no lo haya, `totalesDelPredial`
 * de `datos/operaciones.ts`.
 *
 * <h2>Dos de las seis no se pueden dibujar, y esta medido por que</h2>
 *
 * `POST /rentas/alcabala` publica `{ id, ejercicio, predioId, contribuyenteId, baseImponible,
 * montoDeterminado }` y `POST /rentas/espectaculos`, `{ id, ejercicio, organizadorId,
 * ingresoDeclarado, montoDeterminado }`. **Ninguna de las dos publica `fechaCalculo`** — y las
 * otras cuatro determinaciones si—. Un importe sin la fecha a la que esta calculado no se
 * dibuja (regla 9, RNF-075), y el componente `Importe` lo impide por tipo: no hay forma de
 * pasarle una fecha que nadie publica. Asi que sus importes salen como «—» y la seccion dice
 * cual es el campo que falta, nombrandolo. Es la misma decision que F-5 tomo con el chip «Con
 * deuda»: una tabla vacia que dice por que lo esta es mejor que una cifra que nadie sostiene.
 */

/** Una columna: su rotulo y si es numerica —alineada a la derecha, con `tabular-nums`—. */
export type ColumnaDeLaMemoria = readonly [string, boolean];

/**
 * Una celda de la memoria.
 *
 * `texto` para lo que se escribe tal cual; `importe` para el dinero, que viaja **con su fecha**
 * y se dibuja con `Importe`. Los dos nulos significan **que ninguna operacion lo publica**, y la
 * pantalla escribe un guion: es distinto de una cadena vacia, que seria un dato vacio de verdad.
 */
export interface CeldaDeLaMemoria {
  readonly texto: string | null;
  readonly importe: ImporteConFecha | null;
}

/** Una fila de la memoria, con su clave estable para React. */
export interface FilaDeLaMemoria {
  readonly clave: string;
  readonly celdas: readonly CeldaDeLaMemoria[];
}

/** Las claves de los seis tipos. Son de este port: el artboard los indexa por posicion. */
export const TIPOS_DE_DETERMINACION = [
  'predial-individual',
  'predial-masivo',
  'arbitrios',
  'vehicular',
  'alcabala',
  'espectaculos',
] as const;

export type ClaveDelTipo = (typeof TIPOS_DE_DETERMINACION)[number];

/** El cuadro de un tipo de determinacion, portado de `DETERMINACIONES`. */
export interface TipoDeDeterminacion {
  readonly clave: ClaveDelTipo;
  readonly titulo: string;
  readonly nota: string;
  /**
   * El nombre de lo que se cuenta, **en singular**. El conteo del artboard —«62,418 cuentas»—
   * se compone con `conteo()`, que pluraliza; la cifra sale de la respuesta y no de aqui.
   */
  readonly unidad: string;
  readonly columnas: readonly ColumnaDeLaMemoria[];
}

const TEXTO = (texto: string): CeldaDeLaMemoria => ({ texto, importe: null });

/**
 * Una celda de dinero. **La fecha va DENTRO de la celda y no en la cabecera de la tabla**
 * (regla 9): un cuadro puede componerse de dos operaciones calculadas en dias distintos, y una
 * fecha unica arriba fecharia las dos con la de una.
 */
const DINERO = (valor: string, aLaFecha: string): CeldaDeLaMemoria => ({
  texto: null,
  importe: { importe: valor, actualizadoA: aLaFecha },
});
/** Lo que ninguna operacion publica. La pantalla lo dibuja como guion. */
const NADA: CeldaDeLaMemoria = { texto: null, importe: null };

/** Las cuatro columnas de una memoria de calculo: operador, concepto, detalle e importe. */
const COLUMNAS_DE_MEMORIA: readonly ColumnaDeLaMemoria[] = [
  ['', false],
  ['Concepto', false],
  ['Detalle', false],
  ['S/', true],
];

export const TIPOS: readonly TipoDeDeterminacion[] = [
  {
    clave: 'predial-individual',
    titulo: 'Predial — individual',
    nota: 'Escala progresiva acumulativa sobre el autovalúo de todos los predios del contribuyente en el distrito, con el mínimo imponible de 0.6 % de la UIT.',
    unidad: 'contribuyente',
    columnas: COLUMNAS_DE_MEMORIA,
  },
  {
    clave: 'predial-masivo',
    titulo: 'Predial — masivo',
    nota: 'Proceso de emisión anual. Recalcula el padrón completo y deja constancia de los contribuyentes observados que quedan fuera de la emisión.',
    unidad: 'cuenta',
    columnas: [
      ['Etapa', false],
      ['Registros', true],
      ['Monto S/', true],
      ['Observados', true],
      ['Estado', false],
    ],
  },
  {
    clave: 'arbitrios',
    titulo: 'Arbitrios municipales',
    nota: 'Limpieza pública, parques y serenazgo. La tasa depende del uso del predio, la zona y los metros de frontis declarados en la ficha catastral.',
    unidad: 'servicio',
    columnas: [
      ['Servicio', false],
      ['Criterio de distribución', false],
      ['Frecuencia', false],
      ['Mensual S/', true],
      ['Anual S/', true],
    ],
  },
  {
    clave: 'vehicular',
    titulo: 'Patrimonio vehicular',
    nota: 'El 1 % sobre la base imponible, con un mínimo del 1.5 % de la UIT, por los tres ejercicios en que el vehículo permanece afecto.',
    unidad: 'ejercicio',
    columnas: COLUMNAS_DE_MEMORIA,
  },
  {
    clave: 'alcabala',
    titulo: 'Alcabala',
    nota: 'El 3 % sobre el exceso de las primeras 10 UIT, tomando como base el mayor valor entre el de transferencia y el autovalúo ajustado por el IPM.',
    unidad: 'transferencia',
    columnas: COLUMNAS_DE_MEMORIA,
  },
  {
    clave: 'espectaculos',
    titulo: 'Espectáculos públicos',
    nota: 'Grava el monto que se abona por presenciar el espectáculo. El organizador actúa como agente perceptor: retiene y entrega.',
    unidad: 'evento',
    columnas: [
      ['Expediente', false],
      ['Organizador', false],
      ['Espectáculo', false],
      ['Aforo', true],
      ['Recaudación S/', true],
      ['Tasa', false],
      ['Impuesto S/', true],
    ],
  },
];

/**
 * El conteo que va al lado del tipo: «62,418 cuentas», «1 contribuyente».
 *
 * **La cifra sale de la respuesta**; de aqui sale solo el nombre de lo que se cuenta y su
 * plural. Es la regla 4 de PORTAR.md: una cifra derivada se deriva. Con `cuantos` nulo no se
 * escribe nada, que es lo que hay que ensenar mientras la memoria no ha llegado — un cero
 * diria que no hay ninguno.
 */
export function conteo(cuantos: number | null, unidad: string): string {
  if (cuantos === null) {
    return '';
  }
  return `${cuantos.toLocaleString('en-US')} ${cuantos === 1 ? unidad : `${unidad}s`}`;
}

/** `'100.00'` → `'100 %'`. Recorta los decimales que no dicen nada, sin tocar los que si. */
function porcentaje(valor: string): string {
  const limpio = valor.includes('.') ? valor.replace(/0+$/, '').replace(/\.$/, '') : valor;
  return `${limpio} %`;
}

/**
 * Los cardinales del uno al seis, para escribir «Suma de los tres tramos».
 *
 * Es ortografia, no dato: el numero de tramos sale de la respuesta y esto solo lo escribe con
 * letra, como el artboard. Con mas de seis se cae a la cifra, que dice lo mismo peor.
 */
const CARDINALES: readonly string[] = ['cero', 'un', 'dos', 'tres', 'cuatro', 'cinco', 'seis'];

function enPalabras(cuantos: number): string {
  return CARDINALES[cuantos] ?? String(cuantos);
}

// ── Predial individual (AC3, AC4) ───────────────────────────────────────────────────────────

/**
 * El rotulo de un tramo, tal como el backend lo publica en `reglasAplicadas`.
 *
 * Ahi viajan la nota de la memoria y el rotulo de cada tramo —«Tramo 1 — hasta 15 UIT · 0.2 %»—,
 * que es la justificacion del calculo. Si el backend dejara de publicarlos, el tramo se rotula
 * con lo que si publica: su orden y su alicuota. Componerlo entero desde aqui seria escribir
 * los limites de la escala en la pantalla, que es la regla 5.
 */
function rotuloDelTramo(memoria: DeterminacionIndividual, orden: number, alicuota: string): string {
  const reglas = memoria.reglasAplicadas.filter((regla) => regla.startsWith('Tramo '));
  return reglas[orden - 1] ?? `Tramo ${String(orden)} · ${porcentaje(alicuota)}`;
}

/** «2 predios, al 100 % y al 50 %», de los predios que la respuesta trae. */
function deQuePrediosSale(memoria: DeterminacionIndividual): string | null {
  if (memoria.predios.length === 0) {
    return null;
  }
  const cuotas = memoria.predios.map((predio) => `al ${porcentaje(predio.porcentajePropiedad)}`);
  const cuantos = memoria.predios.length;
  return `${String(cuantos)} ${cuantos === 1 ? 'predio' : 'predios'}, ${cuotas.join(' y ')}`;
}

/** «En 4 cuotas de S/ 147.98», del cronograma que la respuesta trae. */
function comoSePaga(memoria: DeterminacionIndividual): string | null {
  const cuotas = memoria.cuotas;
  const primera = cuotas[0];
  if (primera === undefined) {
    return null;
  }
  const cuantas = `En ${String(cuotas.length)} ${cuotas.length === 1 ? 'cuota' : 'cuotas'}`;
  // El artboard escribe «trimestrales» y el contrato no publica la periodicidad: `modalidad`
  // dice «Fraccionada», que es otra cosa —si se fracciona, no cada cuanto—. Derivarla de que
  // haya cuatro cuotas seria inventar la regla.
  const todasIguales = cuotas.every((cuota) => mismosCentimos(cuota.importe, primera.importe));
  return todasIguales ? `${cuantas} de ${formatearImporte(primera.importe)}` : cuantas;
}

/**
 * Las nueve filas de la memoria del predial individual (AC3).
 *
 * Pura, y por eso se puede llamar con otra respuesta: es lo que permite comprobar que cambiar
 * una fila mueve lo que depende de ella, en vez de comprobar que la pantalla dibuja lo que la
 * pantalla dibuja.
 */
export function filasDelPredialIndividual(
  memoria: DeterminacionIndividual,
): readonly FilaDeLaMemoria[] {
  const tramos = memoria.tramos.map((tramo): FilaDeLaMemoria => {
    const gravado = formatearImporte(tramo.porcionGravada);
    return {
      clave: `tramo-${String(tramo.orden)}`,
      celdas: [
        TEXTO('×'),
        TEXTO(rotuloDelTramo(memoria, tramo.orden, tramo.alicuota)),
        TEXTO(`${gravado} del afecto`),
        DINERO(tramo.aporte, memoria.fechaCalculo),
      ],
    };
  });

  const sinBeneficio = mismosCentimos(memoria.valuoExonerado, '0.00');

  return [
    {
      clave: 'valuo-total',
      celdas: [
        TEXTO(''),
        TEXTO('Valuo total del conjunto'),
        { texto: deQuePrediosSale(memoria), importe: null },
        DINERO(memoria.valuoTotal, memoria.fechaCalculo),
      ],
    },
    {
      clave: 'valuo-exonerado',
      celdas: [
        TEXTO('−'),
        TEXTO('Valuo exonerado'),
        TEXTO(
          sinBeneficio
            ? 'Sin beneficio aplicado este ejercicio'
            : 'Deducciones y exoneraciones del ejercicio',
        ),
        DINERO(memoria.valuoExonerado, memoria.fechaCalculo),
      ],
    },
    {
      clave: 'valuo-afecto',
      celdas: [
        TEXTO('='),
        TEXTO('Valuo afecto'),
        TEXTO('Base imponible del predial'),
        DINERO(memoria.valuoAfecto, memoria.fechaCalculo),
      ],
    },
    ...tramos,
    {
      clave: 'insoluto',
      celdas: [
        TEXTO('='),
        TEXTO('Impuesto insoluto anual'),
        TEXTO(`Suma de los ${enPalabras(memoria.tramos.length)} tramos`),
        DINERO(memoria.impuestoInsoluto, memoria.fechaCalculo),
      ],
    },
    {
      clave: 'derecho-de-emision',
      celdas: [
        TEXTO('+'),
        TEXTO('Derecho de emisión'),
        TEXTO('Tasa del TUPA por cuponera'),
        DINERO(memoria.derechoDeEmision, memoria.fechaCalculo),
      ],
    },
    {
      clave: 'total',
      celdas: [
        TEXTO('='),
        TEXTO('Total a pagar'),
        { texto: comoSePaga(memoria), importe: null },
        DINERO(memoria.totalAPagar, memoria.fechaCalculo),
      ],
    },
  ];
}

/** Lo que dice si la memoria cuadra consigo misma. */
export interface CuadreDeLaMemoria {
  /** La suma de los aportes de los tramos que la tabla ensena. */
  readonly sumaDeLosTramos: string;
  /** Esa suma mas el derecho de emision. */
  readonly sumaDeLasPartidas: string;
  /** Si el insoluto publicado es la suma de los tramos publicados. */
  readonly insolutoCuadra: boolean;
  /** Si el total publicado es el insoluto mas el derecho de emision. */
  readonly totalCuadra: boolean;
}

/**
 * Si el insoluto y el total que la respuesta publica cuadran con las filas que se ensenan (AC4).
 *
 * **No sustituye a lo publicado: lo verifica.** La pantalla dibuja `impuestoInsoluto` y
 * `totalAPagar` tal como llegan —el total lo calcula el backend y lo sostiene con su fecha— y
 * usa esto para no callarse cuando no cuadran. Un cuadro que ensena tres tramos y un insoluto
 * que no es su suma es un error que quien atiende no puede ver, y que se traslada a una
 * cuponera.
 */
export function cuadreDelPredial(memoria: DeterminacionIndividual): CuadreDeLaMemoria {
  const sumaDeLosTramos = sumarImportes(memoria.tramos.map((tramo) => tramo.aporte));
  const sumaDeLasPartidas = sumarImportes([memoria.impuestoInsoluto, memoria.derechoDeEmision]);
  return {
    sumaDeLosTramos,
    sumaDeLasPartidas,
    insolutoCuadra: mismosCentimos(sumaDeLosTramos, memoria.impuestoInsoluto),
    totalCuadra: mismosCentimos(sumaDeLasPartidas, memoria.totalAPagar),
  };
}

// ── Predial masivo (AC5) ────────────────────────────────────────────────────────────────────

/**
 * Las etapas de la emision anual, **con la ultima incluida** (AC5).
 *
 * No se filtra ninguna, y menos la que queda «Con observados»: los 534 que se quedaron fuera
 * son la medida de lo que la emision no cubrio, y esconderlos haria pasar por completa una
 * corrida que no lo esta.
 */
export function filasDeLaCorrida(
  etapas: readonly EtapaDeLaCorrida[],
  aLaFecha: string,
): readonly FilaDeLaMemoria[] {
  return etapas.map((etapa) => ({
    clave: etapa.etapa,
    celdas: [
      TEXTO(etapa.etapa),
      TEXTO(etapa.registros.toLocaleString('en-US')),
      // El backend publica la cadena vacia donde la etapa no mueve dinero, y la pantalla
      // escribe el guion del artboard. Un cero diria que se emitio cero.
      etapa.monto === '' ? NADA : DINERO(etapa.monto, aLaFecha),
      TEXTO(etapa.observados.toLocaleString('en-US')),
      TEXTO(etapa.estado),
    ],
  }));
}

// ── Arbitrios ───────────────────────────────────────────────────────────────────────────────

/**
 * Los arbitrios determinados, uno por servicio.
 *
 * **Tres de las cinco columnas quedan vacias y esta medido**: `GET /rentas/arbitrios` publica
 * `{ id, ejercicio, servicio, periodo, contribuyenteId, predioId, monto, fechaCalculo }`. El
 * **criterio de distribucion** y la **frecuencia** no los publica ninguna de las 181
 * operaciones —son del cuadro de tarifas, que es de `normativa`—, y el **anual** seria el
 * mensual por doce: multiplicar aqui es aritmetica sobre dinero, y ademas seria suponer que los
 * doce periodos valen lo mismo, que es una regla del arbitrio y no de esta pantalla.
 */
export function filasDeLosArbitrios(
  arbitrios: readonly ArbitrioServido[],
): readonly FilaDeLaMemoria[] {
  return arbitrios.map((arbitrio) => ({
    clave: String(arbitrio.id),
    celdas: [
      TEXTO(arbitrio.servicio),
      NADA,
      NADA,
      DINERO(arbitrio.monto, arbitrio.fechaCalculo),
      NADA,
    ],
  }));
}

// ── Patrimonio vehicular ────────────────────────────────────────────────────────────────────

/**
 * La memoria vehicular del ejercicio mas reciente que la respuesta trae.
 *
 * **Las dos primeras filas del artboard quedan vacias**: el valor de adquisicion declarado por
 * el titular y la tabla referencial del MEF no los publica ninguna operacion; lo que llega es
 * la base imponible, que es el mayor de los dos ya resuelto.
 */
export function filasDelVehicular(memoria: DeterminacionVehicular): readonly FilaDeLaMemoria[] {
  const ejercicios = memoria.determinaciones;
  const ultimo = ejercicios[ejercicios.length - 1];
  if (ultimo === undefined) {
    return [];
  }
  // Si el impuesto supera el minimo, el minimo no se aplica. Lo dice la comparacion de las dos
  // cifras que la respuesta trae, no una frase copiada del artboard.
  const superaElMinimo = compararImportes(ultimo.montoDeterminado, memoria.minimoImponible) > 0;

  return [
    {
      clave: 'adquisicion',
      celdas: [TEXTO(''), TEXTO('Valor de adquisición'), TEXTO('Declarado por el titular'), NADA],
    },
    {
      clave: 'tabla-mef',
      celdas: [
        TEXTO(''),
        TEXTO('Tabla referencial MEF'),
        TEXTO('Publicada para el año de fabricación'),
        NADA,
      ],
    },
    {
      clave: 'base',
      celdas: [
        TEXTO('='),
        TEXTO('Base imponible'),
        TEXTO('El mayor de los dos'),
        DINERO(ultimo.baseImponible, memoria.fechaCalculo),
      ],
    },
    {
      clave: 'alicuota',
      // «Tasa» es el rotulo que el artboard escribe y se conserva; lo que se llama `alicuota`
      // es el codigo (regla 8): «tasa» es un tipo de tributo del manual.
      celdas: [
        TEXTO('×'),
        TEXTO('Tasa'),
        TEXTO(porcentaje(memoria.alicuota)),
        DINERO(ultimo.montoDeterminado, memoria.fechaCalculo),
      ],
    },
    {
      clave: 'impuesto',
      celdas: [
        TEXTO('='),
        TEXTO('Impuesto anual'),
        TEXTO(`Ejercicio ${ultimo.ejercicio} · placa ${ultimo.placa}`),
        DINERO(ultimo.montoDeterminado, memoria.fechaCalculo),
      ],
    },
    {
      clave: 'minimo',
      celdas: [
        TEXTO(''),
        TEXTO('Mínimo imponible'),
        TEXTO(
          superaElMinimo
            ? 'Comprobación: el impuesto lo supera'
            : 'Comprobación: se aplica el mínimo',
        ),
        DINERO(memoria.minimoImponible, memoria.fechaCalculo),
      ],
    },
  ];
}

// ── Las dos que no llevan fecha ─────────────────────────────────────────────────────────────

/**
 * El campo que le falta a una operacion para poder dibujar sus importes.
 *
 * Se nombra, y no se describe: «le falta la fecha» invita a inventarla; «no publica
 * `fechaCalculo`» dice donde mirar y que anadir.
 */
export const CAMPO_QUE_FALTA = 'fechaCalculo';

/** Las dos operaciones cuyos importes no se pueden dibujar, con lo que si publican. */
export const SIN_FECHA_DE_CALCULO: Readonly<Record<string, readonly string[]>> = {
  alcabala: ['baseImponible', 'montoDeterminado'],
  espectaculos: ['ingresoDeclarado', 'montoDeterminado'],
};

/**
 * Las siete filas de la alcabala.
 *
 * **Ninguna lleva importe**, y no es que la respuesta venga vacia: `baseImponible` y
 * `montoDeterminado` llegan con dato. Lo que no llega es la fecha a la que estan calculados, y
 * sin ella no se dibuja una cifra (regla 9). Las otras cinco filas tampoco: el valor de
 * transferencia, el autovaluo ajustado por IPM, el tramo inafecto y la alicuota no los publica
 * ninguna operacion.
 *
 * Recibe la respuesta aunque no la use para el importe: de ella sale el ejercicio, que es lo
 * unico dibujable, y tenerla delante es lo que hace que el dia que publique su fecha esta
 * funcion cambie en una linea.
 */
export function filasDeLaAlcabala(memoria: DeterminacionDeAlcabala): readonly FilaDeLaMemoria[] {
  return [
    {
      clave: 'transferencia',
      celdas: [TEXTO(''), TEXTO('Valor de transferencia'), NADA, NADA],
    },
    {
      clave: 'ipm',
      celdas: [TEXTO(''), TEXTO('Autovalúo ajustado por IPM'), NADA, NADA],
    },
    {
      clave: 'base-de-calculo',
      celdas: [TEXTO('='), TEXTO('Base de cálculo'), TEXTO('El mayor de los dos'), NADA],
    },
    {
      clave: 'inafecto',
      celdas: [TEXTO('−'), TEXTO('Tramo inafecto'), NADA, NADA],
    },
    {
      clave: 'base-imponible',
      celdas: [TEXTO('='), TEXTO('Base imponible'), TEXTO(`Ejercicio ${memoria.ejercicio}`), NADA],
    },
    {
      clave: 'alicuota',
      celdas: [TEXTO('×'), TEXTO('Tasa'), NADA, NADA],
    },
    {
      clave: 'a-pagar',
      celdas: [TEXTO('='), TEXTO('Alcabala a pagar'), NADA, NADA],
    },
  ];
}

/**
 * La unica fila de espectaculos que el contrato contesta.
 *
 * El artboard lista **tres** eventos y `POST /rentas/espectaculos` determina **uno**: la
 * operacion es de una sola determinacion, y no hay ninguna que liste los espectaculos de un
 * ejercicio. Sus dos importes tampoco se dibujan, por lo mismo que los de la alcabala.
 */
export function filasDelEspectaculo(
  memoria: DeterminacionDeEspectaculo,
): readonly FilaDeLaMemoria[] {
  return [
    {
      clave: String(memoria.id),
      celdas: [NADA, NADA, TEXTO(`Ejercicio ${memoria.ejercicio}`), NADA, NADA, NADA, NADA],
    },
  ];
}
