import { readdirSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

import { RAIZ, leer, sinComentarios } from './tokens.ts';

/**
 * AC2 — del marco se porta **la variante A, y no queda rastro de las otras dos**.
 *
 * <h2>Por que esto no lo puede probar una prueba de comportamiento</h2>
 *
 * Una rama muerta **no se ve**. Si `PanelDeModulos` conservara `panelVar` con el
 * valor `'a'` fijo y sus tres ramas debajo, la pantalla se dibujaria exactamente
 * igual, todas las pruebas del marco seguirian verdes y el `aside` mediria sus
 * 252 px. Lo unico que cambiaria es que habria doscientas lineas que nadie puede
 * poner en rojo — y a la primera pantalla que alguien anada, la variante `c`
 * volvera a ensenarse en una revision como si fuera una opcion viva.
 *
 * Asi que esto se verifica como se verifica «ningun `SET SESSION`» en el
 * backend: **leyendo el codigo fuente**, no ejecutandolo. Es la misma forma de
 * regla que la undecima —un `JOIN` que cruza la frontera tampoco deja huella— y
 * por el mismo motivo.
 *
 * <h2>Y la medida</h2>
 *
 * Los 292 px eran de la variante `c`, que necesitaba sitio para el riel de
 * iconos MAS la lista de submodulos al lado. Con solo la `a`, el panel mide 252.
 * Un panel de 292 no da error: da cuarenta pixeles de mas en cada pantalla del
 * sistema, para siempre, y nadie sabe de donde salieron.
 */

/** El directorio del marco, y todos sus archivos de codigo. */
const MARCO = join(RAIZ, 'src/marco');

const archivosDelMarco = readdirSync(MARCO).filter(
  (nombre) => /\.tsx?$/.test(nombre) && !nombre.endsWith('.test.ts') && !nombre.endsWith('.test.tsx'),
);

/**
 * El codigo sin comentarios, de las DOS clases.
 *
 * `sinComentarios` —el de `tokens.ts`— quita solo los de bloque, porque nacio
 * para CSS y en CSS no hay otros. Aqui hace falta quitar tambien los de linea:
 * los comentarios de este marco NOMBRAN las tres variantes para explicar por que
 * no estan, y un `// la cola ya no depende de hayCola` pondria rojo justo a la
 * prosa que documenta la decision. Se midio: sin esta pasada, ese comentario
 * basta para tumbar la barrera.
 *
 * Es un recorte y se dice: un `//` dentro de una cadena —una URL, por ejemplo—
 * se llevaria por delante el resto de la linea. En `src/marco/` no hay ninguna,
 * y si la hubiera el efecto seria un falso VERDE de esa linea, nunca un rojo.
 */
const sinNingunComentario = (fuente: string): string =>
  sinComentarios(fuente).replace(/\/\/.*$/gm, '');

/** Lo que el conmutador dejaria detras, con lo que era cada cosa. */
const RASTROS: ReadonlyArray<readonly [string, string]> = [
  ['panelVar', 'el estado que elegia marco en el prototipo'],
  ['panelOpciones', 'los tres botones A/B/C del conmutador'],
  ['esA', 'la rama de la variante A, que ya no tiene de que distinguirse'],
  ['esB', 'la rama de la variante B, que no se porta'],
  ['esC', 'la rama de la variante C, que no se porta'],
  ['hayCola', 'la condicion de la cola de trabajo: era `v !== "c"`'],
];

describe('AC2 — el conmutador A/B/C no existe en el codigo', () => {
  it('hay codigo del marco que leer', () => {
    // Sin esta comprobacion, un directorio renombrado dejaria la lista vacia y
    // los seis casos de abajo pasarian sin haber leido un solo archivo.
    expect(archivosDelMarco.length).toBeGreaterThanOrEqual(8);
  });

  it.each(RASTROS)('no queda ni un «%s» (%s)', (identificador) => {
    const donde = archivosDelMarco.filter((nombre) =>
      new RegExp(`\\b${identificador}\\b`).test(sinNingunComentario(leer(join(MARCO, nombre)))),
    );

    expect(
      donde,
      `«${identificador}» sigue en el codigo del marco. Del artboard se porta la variante A y\n` +
        'nada mas: lo que quede de las otras dos es una rama que ninguna prueba puede poner\n' +
        'en rojo, y que la siguiente revision leera como una opcion viva.',
    ).toEqual([]);
  });

  it('tampoco queda el aria-pressed del conmutador, que era su unica marca', () => {
    const conmutadores = archivosDelMarco.filter((nombre) =>
      leer(join(MARCO, nombre)).includes('aria-pressed'),
    );

    expect(conmutadores).toEqual([]);
  });
});

describe('AC2 — el panel mide 252 px, que es la medida de la variante A', () => {
  const css = sinComentarios(leer(join(RAIZ, 'src/estilos/marco.css')));

  it('«.kr-marco__panel» declara 252 px', () => {
    expect(css).toMatch(/\.kr-marco__panel\s*\{[^}]*flex:\s*0 0 252px/);
    expect(css).toMatch(/\.kr-marco__panel\s*\{[^}]*width:\s*252px/);
  });

  it('y no queda ningun 292 px, que era la medida de la variante C', () => {
    expect(
      css.includes('292'),
      'Los 292 px eran de la `c`, que ponia un riel de iconos y la lista de submodulos uno\n' +
        'al lado del otro. Sin la `c` sobran cuarenta pixeles en cada pantalla del sistema.',
    ).toBe(false);
  });
});

describe('AC2 — la cola de trabajo se muestra siempre', () => {
  it('no cuelga de ninguna condicion en el panel', () => {
    const panel = sinNingunComentario(leer(join(MARCO, 'PanelDeModulos.tsx')));
    const cola = panel.slice(panel.indexOf('kr-marco__cola'));

    // `hayCola` era `v !== 'c'`. Lo que se comprueba aqui es que el bloque de la
    // cola no esta detras de un `&&` de JSX, que es como se escribe un
    // condicional en este arbol.
    const antesDeLaCola = panel.slice(0, panel.indexOf('<div className="kr-marco__cola">'));
    const ultimaLlave = antesDeLaCola.lastIndexOf('{');

    expect(cola.length, 'el bloque de la cola tiene que existir').toBeGreaterThan(0);
    expect(
      antesDeLaCola.slice(ultimaLlave).includes('&&'),
      'La cola de trabajo cuelga de una condicion. Con la variante C fuera no hay ninguna\n' +
        'situacion en la que el panel se dibuje sin ella.',
    ).toBe(false);
  });
});
