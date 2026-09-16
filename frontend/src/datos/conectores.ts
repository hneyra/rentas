import { coordenada, type Coordenada } from '@kamayuk/ui';
import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import type { CorridaDelPredial } from './lecturas.ts';
import { RUTAS, pedirUno } from './lecturas.ts';
// Los conectores de cada modulo viven en su archivo, y aqui solo se montan (#168). Aparte porque
// varios modulos se conectan a la vez: un registro con el codigo de todos dentro es un archivo que
// tres ramas editan en la misma linea.
import { CONECTORES_DE_LICENCIAS } from './conectores/licencias.ts';
import { CONECTORES_DE_COACTIVA } from './conectores/coactiva.ts';

/**
 * **Que pantalla pide que, y que de lo que llega dibuja cada campo** (#97).
 *
 * <h2>«Servida» no es «puede pintarse», y la diferencia se mide campo a campo</h2>
 *
 * Varias hojas declaran alguna operacion servida y aun asi no se conectan, porque lo que la
 * operacion publica no es lo que la pantalla ensena:
 *
 *   · **`seg-panel`** declara TRES servidas —modulos, sesion y municipalidad— y **ninguna publica
 *     nada de lo que la pantalla ensena**: usuarios registrados, activos, contrasenas caducadas.
 *   · **`valores`** declara `GET /rentas/beneficios`, y lo que ensena son UIT e intereses, que son
 *     parametros normativos. Otro contexto.
 *   · **`predios`** necesita **un contribuyente elegido** para pedir su ficha y sus predios, y
 *     esta pantalla todavia no tiene con que elegirlo.
 *   · **`seg-acc`** podria dar tres de las cinco columnas de su tabla; las otras dos —origen y
 *     sensible— no las publica nadie, y la matriz de permisos es, medido, **una bolsa de codigos
 *     planos**: no distingue propios de heredados, que es justo lo que la pantalla pregunta.
 *
 * Las que se conectan se hacen enteras y bien; las que no, lo dicen — ver `porQueNoHayDato.ts`.
 *
 * <h2>Este archivo es el REGISTRO; cada modulo vive en el suyo</h2>
 *
 * Desde #170, un conector se escribe en `conectores/<modulo>.ts` —con su javadoc campo a campo— y
 * aqui entra **una linea**: varios modulos se conectan a la vez y este es el unico archivo que
 * todos tocan. `PANEL` se queda porque no es de ningun modulo del arbol: es el panel del padron.
 *
 * <b>Ese «dos» es el de #97 y no la cuenta de hoy</b>: se deja escrito porque es la medida que
 * justifica la regla de mas abajo, y reescribirlo con el numero de esta semana la dejaria sin
 * sujeto. Los conectores que llegan despues viven en `conectores/<modulo>.ts` y se montan aqui de
 * una linea; cada archivo trae su propia medida de que publica su operacion y que no. El centinela
 * de `conectores.test.ts` es el que dice cuantos hay.
 *
 * <h2>Lo que NO se hace, y es la regla que gobierna este archivo</h2>
 *
 * **No se calcula un agregado que la operacion no publica.** `coa-panel` pregunta «con REC
 * notificada», «con medida cautelar» y «sin REC»: contarlos sobre la pagina que llega daria un
 * numero, y ese numero seria **indistinguible de uno real**. Y «deuda en cartera» sumada sobre una
 * pagina de veinte de un total de cientos seria sencillamente falsa.
 *
 * Un hueco que dice «no publicado» es informacion: dice a quien mantiene el backend exactamente
 * que le falta. Un cero calculado mal no es informacion, es una mentira con formato.
 */

/** Lo que una pantalla saca de una respuesta. */
export interface Reparto {
  /** Los campos de solo lectura que SI salen de lo que llego. */
  readonly valores: ReadonlyMap<Coordenada, string>;
  /** Las filas de la tabla de un bloque. */
  readonly filas: ReadonlyMap<number, readonly (readonly string[])[]>;
  /** Los campos que la operacion servida NO publica, con la palabra que va en su hueco. */
  readonly noPublicados: ReadonlyMap<Coordenada, string>;
}

export interface Conector {
  /** La clave de consulta de TanStack. Lleva la hoja dentro: dos pantallas no comparten cache. */
  readonly clave: readonly string[];
  readonly pedir: (senal: AbortSignal) => Promise<unknown>;
  readonly repartir: (respuesta: never) => Reparto;
}

/** La palabra del hueco cuando la operacion se pidio y no trae ese dato. */
const NO_PUBLICADO = 'no publicado';

/**
 * `panel` — el estado de la ultima corrida del padron.
 *
 * De `CorridaDelPredial` salen la fecha, los observados y **las cinco columnas de la tabla, que
 * cuadran una a una** con `EtapaDeLaCorrida`. Lo que no sale —cuentas emitidas, monto determinado,
 * derecho de emision— no se deduce de las etapas aunque se parezca: la ultima etapa trae 61 350
 * registros y la pantalla ensena 61 350 cuentas emitidas, y **que coincidan no las hace lo mismo**.
 */
const PANEL: Conector = {
  clave: ['panel', 'ultima-corrida'],
  pedir: (senal) => pedirUno<CorridaDelPredial>(RUTAS.ultimaCorrida, senal),
  repartir: (corrida: CorridaDelPredial): Reparto => ({
    valores: new Map([
      [coordenada(0, 1), corrida.fechaCalculo],
      [coordenada(0, 3), String(corrida.observados)],
    ]),
    filas: new Map([
      [
        0,
        corrida.etapas.map((e) => [
          e.etapa,
          String(e.registros),
          e.monto,
          String(e.observados),
          e.estado,
        ]),
      ],
    ]),
    noPublicados: new Map([
      [coordenada(0, 2), NO_PUBLICADO],
      [coordenada(0, 4), NO_PUBLICADO],
      [coordenada(0, 5), NO_PUBLICADO],
    ]),
  }),
};

/** Las hojas que piden de verdad. Las demas lo dicen; ver `porQueNoHayDato.ts`. */
export const CONECTORES: Readonly<Partial<Record<ClaveDeHoja, Conector>>> = {
  panel: PANEL,
  ...CONECTORES_DE_LICENCIAS,
  // Una linea por modulo, y el modulo entero en su archivo: cuatro se conectan a la vez y este
  // registro es el unico que los cuatro tocan. Ver `conectores/coactiva.ts` (#170).
  ...CONECTORES_DE_COACTIVA,
};

export { NO_PUBLICADO };
