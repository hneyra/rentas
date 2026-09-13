/**
 * Lo que el interprete sabe de los datos de una pantalla — **y lo poco que puede saber**.
 *
 * <h2>Por que esto entra por parametro y no lo averigua el interprete</h2>
 *
 * Porque saber si una pantalla tiene backend exige saber **que operaciones declara esa hoja** y
 * **cuales sirve ESTE sistema**, y las dos cosas son de Rentas. El interprete esta destinado a
 * `@kamayuk/ui` y no puede nombrarlas: lo vigila
 * `verificaciones/el-interprete-no-nombra-un-sistema.test.ts`.
 *
 * Asi que el interprete recibe **el resultado** —hay dato, o no lo hay y por este motivo— y quien
 * lo calcula es la costura (`src/aplicacion.tsx`), que si puede saberlo.
 *
 * <h2>Por que la ausencia trae DOS frases y no una</h2>
 *
 * Medido sobre las cuarenta pantallas: **33 no tienen ninguna operacion servida**, y de las siete
 * que si, solo dos se pintan enteras. O sea que el caso normal es el hueco, no el dato.
 *
 * Un hueco con una raya y nada mas deja la peor pantalla posible: `seg-panel` —la unica con 3 de 3
 * operaciones servidas— saldria con **seis guiones y ni una palabra**. Alguien que la abra no
 * puede distinguir «esto todavia no esta conectado» de «esto esta roto» de «aqui no hay nada que
 * ver». Por eso van dos:
 *
 *   · **`enElCampo`** — lo corto, en el hueco de cada campo. Se lee cuarenta veces por pantalla.
 *   · **`explicacion`** — la frase entera, UNA vez arriba. Dice por que.
 */

/** Por que no hay dato, dicho de las dos formas que la pantalla necesita. */
export interface Ausencia {
  /** Lo corto, dentro del hueco de un campo. Una o dos palabras. */
  readonly enElCampo: string;
  /** La frase que lo explica, una sola vez por pantalla. */
  readonly explicacion: string;
  /** `info` cuando es esperado; `atencion` cuando alguien deberia mirarlo. */
  readonly tono: 'info' | 'atencion';
}

/** La coordenada de un campo dentro de una pantalla: `bloque|campo`. */
export type Coordenada = `${number}|${number}`;

/**
 * Lo que se sabe de los datos de una pantalla.
 *
 * `valores` y `filas` **son opcionales y hoy siempre faltan**: ninguna pantalla pide datos
 * todavia. Lo que existe es el otro lado —decir que no hay y por que—, que es lo que saca las
 * cifras inventadas del paquete servido (#97).
 */
export interface DatosDeLaPantalla {
  /** El valor de cada campo de solo lectura que SI se sabe. */
  readonly valores?: ReadonlyMap<Coordenada, string>;
  /** Las filas de la tabla de cada bloque que SI se sabe, por indice de bloque. */
  readonly filas?: ReadonlyMap<number, readonly (readonly string[])[]>;
  /** El conteo del encabezado de una tabla. Sin el, se cuentan las filas que haya. */
  readonly conteos?: ReadonlyMap<number, string>;
  /** Que decir donde no hay. Obligatorio: un hueco sin motivo es peor que el hueco. */
  readonly ausencia: Ausencia;
}

/** La coordenada de un campo. */
export const coordenada = (bloque: number, campo: number): Coordenada => `${bloque}|${campo}`;
