import type { CorridaDelPredial } from '../lecturas.ts';
import {
  DE_UNA_PARTE_DEL_PADRON,
  LA_ULTIMA_FUE_UNA_SIMULACION,
  type PalabraDeHueco,
} from '../palabrasDeHueco.ts';

/** El alcance de una corrida del padron entero: `DeterminarPredialMasivo.ALCANCE_TODOS`. */
const TODO_EL_PADRON = 'TODOS';

/**
 * **Por que las cifras de esta corrida NO son las de la emision del ejercicio**, o `null` si lo
 * son (#357).
 *
 * La usan las dos hojas que escriben la corrida bajo un rotulo que afirma la emision —`panel`
 * («Cuentas emitidas», «Monto determinado») e `ini-panel` («Observados sin emision»)—, y vive en
 * un sitio para que las dos no decidan distinto sobre la misma corrida.
 *
 * <h2>Es una RED, y no el arreglo</h2>
 *
 * El arreglo es que las dos piden `?simulacion=false` y el backend no les devuelve un ensayo. Esto
 * es lo que pasa si uno llega igual —una ruta cambiada, un doble que no filtra—: sus cifras no se
 * escriben como emitidas y el hueco dice por que. Sin la red, el dia que el filtro se pierda el
 * panel vuelve a decir «Cuentas emitidas 120» de algo que no emitio nada, y nada se pone rojo.
 *
 * <h2>Y la emision de una parte del padron tampoco es la del ejercicio</h2>
 *
 * Una emision por sector asienta deuda de verdad, pero «120 cuentas emitidas» de un sector se lee
 * como el padron entero y tapa la emision anual que se hizo antes. Que cifra es la del ejercicio
 * cuando se emitio por partes —sumarlas— es otra pregunta que #357 deja fuera; aqui solo se
 * impide afirmarla.
 *
 * El ensayo se mira primero: una simulacion de un sector es, antes que nada, un ensayo.
 */
export function porQueNoEsLaEmisionDelEjercicio(corrida: CorridaDelPredial): PalabraDeHueco | null {
  if (corrida.simulacion) return LA_ULTIMA_FUE_UNA_SIMULACION;
  if (corrida.alcance !== TODO_EL_PADRON) return DE_UNA_PARTE_DEL_PADRON;
  return null;
}
