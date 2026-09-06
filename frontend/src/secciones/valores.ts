import type {
  ArbitrioServido,
  DeterminacionIndividual,
  DeterminacionVehicular,
} from '../datos/lecturas.ts';
import type { CeldaDeLaMemoria, ColumnaDeLaMemoria, FilaDeLaMemoria } from './determinacion.ts';

/**
 * Las tres pestanas de «Valores», portadas de `VAL` (`:1163`), y de donde sale cada celda.
 *
 * <h2>El hallazgo de esta seccion, medido sobre las 181 operaciones</h2>
 *
 * **Ninguna operacion de este backend publica la tabla de valores del ejercicio.** No es una
 * carencia del proxy ni una captura incompleta: los valores normativos —la UIT, los tramos de
 * la escala, los minimos imponibles, la deduccion de pensionista, el cuadro de arbitrios por
 * zona, la TIM y las tasas de fraccionamiento— **son de `normativa`**, que los sella
 * (ADR-0025), y este sistema los **consume** de una copia local a traves de
 * `LectorDeParametros`. Nunca los republica por HTTP. Recorrido `formas-de-la-api.json`, lo
 * unico que dice algo de ellos es `GET /seguridad/parametros/ejercicios/{ejercicio}`, y lo que
 * dice son **las senas del conjunto** —ejercicio, conjunto, version y si esta sellado—, no un
 * solo valor.
 *
 * Y sin embargo la pantalla no queda vacia, porque las cifras **ya resueltas** viajan dentro de
 * cada determinacion: `POST /rentas/predial/calculo-individual` publica `uit`,
 * `tramos[].alicuota`, `tramos[].limiteSuperior`, `minimoImponible` y `derechoDeEmision`;
 * `POST /rentas/vehicular/calculo`, su propio `minimoImponible`; y `GET /rentas/arbitrios`, los
 * servicios que se determinan. Asi que la primera pestana se compone de lo que el calculo
 * devolvio —que es exactamente lo que el sistema aplico, no lo que alguien cree que aplico— y
 * lo que no llega se dibuja con un guion y se dice por que.
 *
 * <h2>Por que la pastilla «Solo lectura» del artboard es verdad, y ahora se sostiene</h2>
 *
 * Porque el conjunto esta **sellado**. Un conjunto sellado no se edita: se sustituye por otro
 * con su version, que es lo que hace que recalcular 2027 en 2037 de el mismo centimo (RNF-053).
 * El artboard dibuja la pastilla sin decir por que; aqui la sostiene la respuesta de
 * `GET /seguridad/parametros/ejercicios/{ejercicio}`, que publica `sellado` y `version`.
 *
 * <h2>Ni un literal tributario en este archivo (regla 5)</h2>
 *
 * No hay ni una UIT, ni un tramo, ni una alicuota, ni un minimo escritos aqui. Donde el
 * artboard escribe «Hasta 15 UIT» o «0.6 % de la UIT», este port **lee la regla que el backend
 * publica** —`reglasAplicadas`, que trae la nota de la memoria y el rotulo de cada tramo— y
 * saca de ahi el texto; donde no hay regla publicada, la celda queda vacia. Lo unico numerico
 * que sobrevive es la prosa de las notas de cabecera, que es la del artboard y no la usa nadie
 * para calcular — la misma decision que `secciones/expediente.ts` tomo en F-5.
 */

/** Una de las tres pestanas de valores, con su cuadro. */
export interface TablaDeValores {
  readonly clave: string;
  readonly rotulo: string;
  readonly nota: string;
  readonly pie: string;
  readonly columnas: readonly ColumnaDeLaMemoria[];
}

const TEXTO = (texto: string): CeldaDeLaMemoria => ({ texto, importe: null });
const DINERO = (valor: string, aLaFecha: string): CeldaDeLaMemoria => ({
  texto: null,
  importe: { importe: valor, actualizadoA: aLaFecha },
});
const NADA: CeldaDeLaMemoria = { texto: null, importe: null };

export const TABLAS_DE_VALORES: readonly TablaDeValores[] = [
  {
    clave: 'escala',
    rotulo: 'UIT y escala progresiva',
    nota: 'La UIT del ejercicio y los tres tramos de la escala del predial. Cambiar la UIT recalcula mínimos, tramos y multas en todo el sistema.',
    pie: 'La escala es acumulativa: cada tramo se aplica solo a la porción del autovalúo que le corresponde, no al total.',
    columnas: [
      ['Concepto', false],
      ['Base', false],
      ['Tasa o valor', false],
      ['Equivalente S/', true],
    ],
  },
  {
    clave: 'arbitrios',
    rotulo: 'Arbitrios por servicio',
    nota: 'Tasa mensual por metro de frontis o metro construido, según el servicio, la zona y el uso del predio.',
    pie: 'Los arbitrios se determinan por predio, no por contribuyente: cada uno tiene su frontis y su zona.',
    columnas: [
      ['Servicio', false],
      ['Zona 1', true],
      ['Zona 2', true],
      ['Zona 3', true],
      ['Zona 4', true],
      ['Criterio', false],
    ],
  },
  {
    clave: 'intereses',
    rotulo: 'Intereses y reajustes',
    nota: 'Interés moratorio, reajuste por índice de precios y las tasas del fraccionamiento. Se aplican día a día sobre el insoluto vencido.',
    pie: 'El interés corre desde el día siguiente al vencimiento de la cuota. Una amnistía puede condonarlo, nunca el insoluto.',
    columnas: [
      ['Concepto', false],
      ['Vigencia', false],
      ['Tasa mensual', true],
      ['Tasa diaria', true],
    ],
  },
];

/** `'0.2'` → `'0.2 %'`, sin los ceros que no dicen nada. */
function porcentaje(valor: string): string {
  const limpio = valor.includes('.') ? valor.replace(/0+$/, '').replace(/\.$/, '') : valor;
  return `${limpio} %`;
}

/** La primera letra en mayuscula: «hasta 15 UIT» → «Hasta 15 UIT», como lo escribe el cuadro. */
function enMayuscula(texto: string): string {
  return texto.charAt(0).toUpperCase() + texto.slice(1);
}

/**
 * La base de un tramo, sacada del rotulo que el backend publica.
 *
 * `reglasAplicadas` trae «Tramo 1 — hasta 15 UIT · 0.2 %», que es la regla aplicada tal cual.
 * La base es lo que va entre la raya y el punto medio; la alicuota viaja aparte y por su
 * nombre, asi que de aqui solo sale el texto. Sin regla publicada, la celda queda vacia: los
 * limites de la escala no se escriben en la pantalla (regla 5).
 */
function baseDelTramo(memoria: DeterminacionIndividual, orden: number): string | null {
  const reglas = memoria.reglasAplicadas.filter((regla) => regla.startsWith('Tramo '));
  const regla = reglas[orden - 1];
  if (regla === undefined) {
    return null;
  }
  const partes = /—\s*(.+?)\s*·/.exec(regla);
  return partes?.[1] === undefined ? null : enMayuscula(partes[1]);
}

/**
 * La base del minimo imponible del predial, sacada de la nota de la memoria.
 *
 * El backend publica en `reglasAplicadas[0]` la nota entera —«… con el mínimo imponible de 0.6 %
 * de la UIT»—, que es la regla que aplico. De ahi sale el texto; escribirlo aqui seria un
 * literal tributario en el codigo (regla 5).
 */
function baseDelMinimo(memoria: DeterminacionIndividual): string | null {
  for (const regla of memoria.reglasAplicadas) {
    // Hasta el final de la frase, y no hasta el primer punto: el propio valor lleva uno
    // —«0.6 % de la UIT»— y cortar ahi devolvia «0».
    const partes = /m[íi]nimo imponible de (.+?)\.?\s*$/i.exec(regla);
    if (partes?.[1] !== undefined) {
      return enMayuscula(partes[1].trim());
    }
  }
  return null;
}

/** Lo que hace falta para componer la primera pestana. */
export interface ValoresServidos {
  readonly predial: DeterminacionIndividual | null;
  readonly vehicular: DeterminacionVehicular | null;
  readonly arbitrios: readonly ArbitrioServido[] | null;
}

/**
 * Las filas de «UIT y escala progresiva», compuestas de lo que las dos memorias devolvieron.
 *
 * Ocho filas como el artboard, y **una queda entera sin dato**: la deduccion de pensionista.
 * `GET /rentas/beneficios` publica el monto de la deduccion **del contribuyente que la tiene
 * concedida**, que no es la deduccion del ejercicio: generalizar de un beneficiario a la tabla
 * normativa seria publicar como norma lo que es un acto administrativo de una persona.
 */
export function filasDeLaEscala(servidos: ValoresServidos): readonly FilaDeLaMemoria[] {
  const predial = servidos.predial;
  if (predial === null) {
    return [];
  }

  const tramos = predial.tramos.map((tramo): FilaDeLaMemoria => {
    const base = baseDelTramo(predial, tramo.orden);
    return {
      clave: `tramo-${String(tramo.orden)}`,
      celdas: [
        TEXTO(`Tramo ${String(tramo.orden)} del predial`),
        base === null ? NADA : TEXTO(base),
        TEXTO(porcentaje(tramo.alicuota)),
        // El ultimo tramo no tiene tope, y el backend lo publica nulo. «sin tope» es lo que el
        // artboard escribe, y no es un importe: es la ausencia de uno.
        tramo.limiteSuperior === null
          ? TEXTO('sin tope')
          : DINERO(tramo.limiteSuperior, predial.fechaCalculo),
      ],
    };
  });

  const minimoDelPredial = baseDelMinimo(predial);
  const vehicular = servidos.vehicular;

  return [
    {
      clave: 'uit',
      celdas: [
        TEXTO(`UIT ${predial.ejercicio}`),
        TEXTO('Aprobada por el MEF'),
        NADA,
        DINERO(predial.uit, predial.fechaCalculo),
      ],
    },
    ...tramos,
    {
      clave: 'minimo-predial',
      celdas: [
        TEXTO('Mínimo imponible predial'),
        minimoDelPredial === null ? NADA : TEXTO(minimoDelPredial),
        NADA,
        DINERO(predial.minimoImponible, predial.fechaCalculo),
      ],
    },
    {
      clave: 'minimo-vehicular',
      celdas: [
        TEXTO('Mínimo imponible vehicular'),
        // La memoria vehicular publica su minimo y no la regla con que se obtiene, asi que la
        // columna «Base» queda vacia en vez de repetir la del predial.
        NADA,
        NADA,
        vehicular === null ? NADA : DINERO(vehicular.minimoImponible, vehicular.fechaCalculo),
      ],
    },
    {
      clave: 'pensionista',
      celdas: [TEXTO('Deducción de pensionista'), NADA, NADA, NADA],
    },
    {
      clave: 'derecho-de-emision',
      celdas: [
        TEXTO('Derecho de emisión'),
        TEXTO('Tasa del TUPA'),
        NADA,
        DINERO(predial.derechoDeEmision, predial.fechaCalculo),
      ],
    },
  ];
}

/**
 * Las filas de «Arbitrios por servicio».
 *
 * Los servicios son los que `GET /rentas/arbitrios` determina; **las cuatro zonas y el criterio
 * quedan vacios**. El monto que la operacion publica es el del predio del contribuyente —su
 * zona y su frontis—, y ponerlo bajo «Zona 2» seria afirmar una zona que la respuesta no dice.
 * El cuadro de tarifas por zona es de `normativa`.
 */
export function filasDeLosArbitriosPorZona(
  arbitrios: readonly ArbitrioServido[],
): readonly FilaDeLaMemoria[] {
  return arbitrios.map((arbitrio) => ({
    clave: String(arbitrio.id),
    celdas: [TEXTO(arbitrio.servicio), NADA, NADA, NADA, NADA, NADA],
  }));
}

/**
 * Las filas de «Intereses y reajustes»: **ninguna**, y es el resultado de medirlo.
 *
 * La TIM, el interes de fraccionamiento, el reajuste por IPM y el arancel de costas no los
 * publica ninguna de las 181 operaciones — ni siquiera de manera derivada, como la UIT viaja
 * dentro de una determinacion: `GET /consultas/deuda` publica el interes **en soles** de una
 * obligacion, que es el resultado de aplicar la tasa, no la tasa. Escribir «0.90 %» aqui seria
 * a la vez inventar un dato y meter un literal tributario en el codigo (regla 5).
 *
 * Es una funcion y no una constante vacia a proposito: el dia que la operacion exista, lo que
 * cambia es su cuerpo y no el sitio desde el que se llama.
 */
export function filasDeLosIntereses(): readonly FilaDeLaMemoria[] {
  return [];
}
