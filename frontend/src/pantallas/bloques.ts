import type { DefinicionDeBloque, DefinicionDePantalla, PiezaDeLaPantalla } from '@kamayuk/ui';

/**
 * **Los bloques de una pantalla, cuando la pantalla puede llevar otras piezas** (#288).
 *
 * <h2>Por que hace falta esto</h2>
 *
 * Hasta #288 las cuarenta definiciones eran `DefinicionDePantalla` a secas, o sea **solo bloques**,
 * y una docena de sitios leia `bloque.campos` y `bloque.tabla` derecho. Desde #288 `ini-flujo`
 * lleva ademas una pieza del consumidor —el grafico—, asi que `bloques` es una union y esa lectura
 * ya no compila: es exactamente el efecto que el javadoc de `DefinicionDePantalla` anuncia cuando
 * dice que «ensanchar `bloques` a `PiezaDeLaPantalla` rompe a quien las lee».
 *
 * Que no compile es lo que se quiere: cada sitio tiene que decir **que hace con lo que no es un
 * bloque**. Lo que casi todos hacen es lo mismo —no les toca—, y eso es lo que esta funcion dice en
 * un solo sitio.
 *
 * <h2>Por que se escribe aqui y no se importa de `@kamayuk/ui`</h2>
 *
 * Porque la libreria **no lo publica**: `esBloque` vive en `interprete/componer.ts` y su `index.ts`
 * exporta de ahi `piezasSinRegistrar`, `resolverTexto` y `seCumple`, pero no este. Es un dato para
 * `kamayuk-lib`#25: el primer consumidor del punto de extension necesita distinguir un bloque de
 * una pieza en cuanto recorre sus definiciones, que es lo primero que hace todo el mundo.
 *
 * La regla se copia de la de alli —un bloque es el que no dice `tipo` o dice `'bloque'`—, y no se
 * adivina: los bloques de `kamayuk-lib`#27 no llevan `tipo` y siguen siendo bloques.
 */

/** Si una pieza es un bloque. */
export function esBloque(pieza: PiezaDeLaPantalla): pieza is DefinicionDeBloque<string, string> {
  return pieza.tipo === undefined || pieza.tipo === 'bloque';
}

/**
 * Los bloques de una definicion, **sin perder el indice** de los que quedan fuera.
 *
 * El indice es lo que empareja un bloque con sus datos —`filas` va por indice de pieza—, asi que
 * filtrar y renumerar seria mover los datos de sitio en silencio. Por eso devuelve el par.
 */
export function bloquesConSuIndice(
  definicion: DefinicionDePantalla<PiezaDeLaPantalla>,
): readonly (readonly [DefinicionDeBloque<string, string>, number])[] {
  return definicion.bloques.flatMap((pieza, i) => (esBloque(pieza) ? [[pieza, i] as const] : []));
}

/** Solo los bloques, para quien no necesita el indice. */
export function bloquesDe(
  definicion: DefinicionDePantalla<PiezaDeLaPantalla>,
): readonly DefinicionDeBloque<string, string>[] {
  return definicion.bloques.filter(esBloque);
}

/** Las tablas que los bloques de una pantalla declaran, en su orden. Sin las de los bloques sin tabla. */
export function tablasDe(
  definicion: DefinicionDePantalla<PiezaDeLaPantalla>,
): readonly NonNullable<DefinicionDeBloque<string, string>['tabla']>[] {
  return bloquesDe(definicion).flatMap((bloque) => (bloque.tabla === undefined ? [] : [bloque.tabla]));
}
