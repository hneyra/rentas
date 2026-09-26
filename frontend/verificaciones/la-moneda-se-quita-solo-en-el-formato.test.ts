// @vitest-environment node
//
// Lee el arbol del disco y lo parsea. No hay DOM que necesitar: lo que se vigila es que el CODIGO no
// vuelva a recortar el simbolo de un importe, y eso se ve en el texto, no en la pantalla.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { RAIZ } from './artboards.ts';

/**
 * **El simbolo de la moneda se quita en UN sitio: `dominio/formato.ts`** (#389).
 *
 * <h2>De que defecto viene</h2>
 *
 * De esto, copiado en `src/datos/conectores/inicio.ts` y en `src/datos/conectores/fiscalizacion.ts`
 * hasta #389:
 *
 *     const LA_MONEDA = /^S\/\s/;
 *     function enColumnaDeSoles(importe) {
 *       return importe === null ? SIN_CIFRAR : formatearImporte(importe).replace(LA_MONEDA, '');
 *     }
 *
 * Era la politica del artboard para una columna cuyo rotulo ya dice «S/» —el importe agrupado y sin
 * el simbolo—, escrita **fuera** de donde viven los formateadores, y dos veces. Las demas columnas
 * «… S/» no la copiaron: eligieron otra. `territorio` y `con-doc` escribian «S/ 80,250.00» bajo
 * «Límite superior S/» y «Total S/», y `coa-cost` el texto crudo, «1250.00», bajo «Costa S/». Cuatro
 * politicas para la misma columna, y cada conector nuevo eligiendo una o copiando la expresion
 * regular una tercera vez.
 *
 * Desde #389 la politica es `formatearImporteEnColumna` —y `formatearImporteSinRedondearEnColumna`
 * para el aporte de un tramo—, que construyen la cifra una vez y **no le ponen** el simbolo en vez de
 * quitarselo despues.
 *
 * <h2>Que se rechaza, fuera de `src/dominio/formato.ts`</h2>
 *
 * <ol>
 *   <li>Una <b>expresion regular literal</b> que nombre el simbolo: `/^S\/\s/`.</li>
 *   <li>Una expresion regular <b>construida</b> desde un texto que lo nombre: `new RegExp('^S/')`.</li>
 *   <li>Un texto que lo nombre pasado a un metodo de cadena que busca o corta:
 *       `.replace('S/ ', '')`, `.split('S/ ')`, `.startsWith('S/')`…</li>
 *   <li>Un `.slice`, `.substring` o `.substr` sobre lo que devuelve un `formatearImporte…`: es
 *       quitar el simbolo sin nombrarlo.</li>
 * </ol>
 *
 * Con el compilador de TypeScript y no con una expresion regular sobre el texto, por lo mismo que
 * `la-cabecera-no-se-escribe-a-mano`: el simbolo aparece en prosa —javadoc que citan
 * «S/ 1,842.60»— y en los rotulos de las definiciones —«Importe S/»—, y ninguno de los dos recorta
 * nada. Un patron de texto los contaria.
 *
 * <h2>El ruido, medido antes de escribirla</h2>
 *
 * **Dos aciertos** en el arbol de antes de #389, que son las dos copias de `LA_MONEDA`, y **cero**
 * despues. No hay nada que exceptuar: si un dia hace falta, se declara aqui con su motivo.
 *
 * <h2>Lo que NO cubre, dicho</h2>
 *
 * Un simbolo guardado en una constante de texto y pasado despues (`const S = 'S/ '; x.replace(S,
 * '')`), porque seguirlo es seguir datos y no sintaxis. Y un conector que no llame a ningun
 * formateador y escriba el texto crudo —el «1250.00» de `coa-cost`—: eso no es recortar nada, y lo
 * caza el barrido de las columnas de soles de `src/datos/conectores.test.ts`, que mira lo que llega
 * a la celda.
 */

/** El unico archivo donde el simbolo se pone o se deja de poner. */
const EL_FORMATO = 'src/dominio/formato.ts';

/** La muestra que la viola a proposito. Ver su cabecera. */
const MUESTRA = 'verificaciones/muestra-de-la-moneda/moneda-recortada-en-un-conector.ts';

/** Los metodos de cadena que buscan o cortan por un texto. */
const BUSCAN_O_CORTAN = new Set([
  'replace',
  'replaceAll',
  'split',
  'startsWith',
  'endsWith',
  'indexOf',
  'lastIndexOf',
  'includes',
]);

/** Los que cortan por posicion. Sobre un importe formateado, es saltarse el simbolo. */
const CORTAN_POR_POSICION = new Set(['slice', 'substring', 'substr']);

/** El simbolo, tal como aparece en un texto y tal como aparece escapado en una expresion regular. */
const NOMBRA_LA_MONEDA = /S\\?\//;

/** Todos los `.ts`/`.tsx` de produccion bajo `src/`, con su ruta relativa a `frontend/`. */
function fuentesDeProduccion(desde = join(RAIZ, 'src')): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    if (statSync(ruta).isDirectory()) return fuentesDeProduccion(ruta);
    if (!/\.tsx?$/.test(entrada) || /\.test\.tsx?$/.test(entrada)) return [];
    return [relative(RAIZ, ruta).split(sep).join('/')];
  });
}

function recorrer(nodo: ts.Node, visita: (n: ts.Node) => void): void {
  visita(nodo);
  nodo.forEachChild((hijo) => {
    recorrer(hijo, visita);
  });
}

/** Si es un texto escrito en el codigo —`'S/ '` o `` `S/ ` ``— que nombra la moneda. */
function textoQueNombraLaMoneda(nodo: ts.Node | undefined): boolean {
  if (nodo === undefined) return false;
  return (
    (ts.isStringLiteral(nodo) || ts.isNoSubstitutionTemplateLiteral(nodo)) &&
    NOMBRA_LA_MONEDA.test(nodo.text)
  );
}

/** Si la expresion es una llamada a un formateador de importes: `formatearImporte…(x)`. */
function esUnImporteFormateado(nodo: ts.Expression): boolean {
  return (
    ts.isCallExpression(nodo) &&
    ts.isIdentifier(nodo.expression) &&
    nodo.expression.text.startsWith('formatearImporte')
  );
}

/**
 * **Donde se recorta la moneda en un archivo**, dado su TEXTO: `ruta:linea — que`.
 *
 * Recibe el texto y no solo la ruta para poder aplicarse tambien al codigo de antes de #389: una
 * regla que nunca se ve fallar no protege nada.
 */
function recortesDeLaMoneda(ruta: string, fuente: string): readonly string[] {
  const arbol = ts.createSourceFile(ruta, fuente, ts.ScriptTarget.ESNext, true, ts.ScriptKind.TSX);
  const aciertos: string[] = [];
  const anotar = (nodo: ts.Node, que: string) => {
    const { line } = arbol.getLineAndCharacterOfPosition(nodo.getStart(arbol));
    aciertos.push(`${ruta}:${String(line + 1)} — ${que}`);
  };

  recorrer(arbol, (nodo) => {
    // (1) `/^S\/\s/`
    if (ts.isRegularExpressionLiteral(nodo) && NOMBRA_LA_MONEDA.test(nodo.text)) {
      anotar(nodo, `expresion regular con la moneda: ${nodo.text}`);
      return;
    }
    // (2) `new RegExp('^S/')` o `RegExp('^S/')`
    if (
      (ts.isNewExpression(nodo) || ts.isCallExpression(nodo)) &&
      ts.isIdentifier(nodo.expression) &&
      nodo.expression.text === 'RegExp' &&
      textoQueNombraLaMoneda(nodo.arguments?.[0])
    ) {
      anotar(nodo, 'expresion regular construida con la moneda');
      return;
    }
    if (!ts.isCallExpression(nodo) || !ts.isPropertyAccessExpression(nodo.expression)) return;
    const metodo = nodo.expression.name.text;
    // (3) `.replace('S/ ', '')`, `.split('S/ ')`…
    if (BUSCAN_O_CORTAN.has(metodo) && nodo.arguments.some((a) => textoQueNombraLaMoneda(a))) {
      anotar(nodo, `.${metodo}() por el texto de la moneda`);
      return;
    }
    // (4) `formatearImporte(x).slice(3)`
    if (CORTAN_POR_POSICION.has(metodo) && esUnImporteFormateado(nodo.expression.expression)) {
      anotar(nodo, `.${metodo}() sobre un importe formateado`);
    }
  });
  return aciertos;
}

const leer = (ruta: string): string => readFileSync(join(RAIZ, ruta), 'utf8');

const BARRIDAS = fuentesDeProduccion().filter((ruta) => ruta !== EL_FORMATO);

/**
 * **Las dos copias tal como estaban antes de #389**, recortadas a lo que esta guarda mira. Si la
 * regla deja de encontrarlas aqui, dejo de morder, y lo que diga del arbol de verdad no vale nada.
 */
const LAS_COPIAS_DE_ANTES = {
  'src/datos/conectores/inicio.ts': `
const LA_MONEDA = /^S\\/\\s/;
function enColumnaDeSoles(importe: ImporteConFecha | null): string {
  return importe === null ? SIN_CIFRAR : formatearImporte(importe.importe).replace(LA_MONEDA, '');
}
`,
  'src/datos/conectores/fiscalizacion.ts': `
const LA_MONEDA = /^S\\/\\s/;
function enColumnaDeSoles(importe: string | null): string {
  return importe === null ? SIN_CIFRAR : formatearImporte(importe).replace(LA_MONEDA, '');
}
`,
} as const;

describe('#389 — el simbolo de la moneda se quita solo en `dominio/formato.ts`', () => {
  it('EL CENTINELA: el barrido tiene arbol delante, y el formato sigue siendo quien pone la moneda', () => {
    // Sin esto, un `src/` que se moviera dejaria el barrido recorriendo la lista vacia y pasando en
    // verde sobre la nada. Y los veintiun conectores tienen que estar dentro de lo barrido.
    expect(BARRIDAS.length, 'no hay fuentes de produccion que barrer').toBeGreaterThan(50);
    expect(BARRIDAS).toEqual(
      expect.arrayContaining([
        'src/datos/conectores.ts',
        'src/datos/conectores/inicio.ts',
        'src/datos/conectores/fiscalizacion.ts',
        'src/datos/conectores/coactiva.ts',
        'src/datos/conectores/consultas.ts',
      ]),
    );
    // La excepcion es UN archivo, y existe: si `formato.ts` se mudara, la excepcion no eximiria a
    // nadie y el sitio nuevo saldria rojo sin decir por que.
    expect(leer(EL_FORMATO)).toContain("const MONEDA = 'S/';");
    expect(leer(EL_FORMATO)).toContain('export function formatearImporteEnColumna(');
  });

  it('ningun archivo de produccion recorta la moneda fuera de `dominio/formato.ts`', () => {
    const aciertos = BARRIDAS.flatMap((ruta) => recortesDeLaMoneda(ruta, leer(ruta)));
    expect(
      aciertos,
      'Se recorta el simbolo de un importe fuera de `dominio/formato.ts`:\n' +
        `${aciertos.map((a) => `  ${a}`).join('\n')}\n\n` +
        '  Es la tercera copia de `LA_MONEDA` (#389). Una columna cuyo rotulo ya dice «S/» se\n' +
        '  escribe con `formatearImporteEnColumna` —o `formatearImporteSinRedondearEnColumna` para\n' +
        '  un intermedio—, que no le ponen el simbolo en vez de quitarselo despues.',
    ).toEqual([]);
  });
});

describe('#389 — la regla muerde', () => {
  it('encuentra las DOS copias de antes de #389, una en cada conector', () => {
    const aciertos = Object.entries(LAS_COPIAS_DE_ANTES).flatMap(([ruta, texto]) =>
      recortesDeLaMoneda(ruta, texto),
    );
    expect(aciertos).toEqual([
      'src/datos/conectores/inicio.ts:2 — expresion regular con la moneda: /^S\\/\\s/',
      'src/datos/conectores/fiscalizacion.ts:2 — expresion regular con la moneda: /^S\\/\\s/',
    ]);
  });

  it('en la muestra senala las seis formas malas, una por una, y ninguna de las cuatro buenas', () => {
    // Las lineas esperadas salen de la propia muestra —las que llevan la marca `VIOLA`— y no se
    // escriben aqui: asi editar la muestra no deja esta lista desfasada en verde.
    const texto = leer(MUESTRA);
    const marcadas = texto
      .split('\n')
      .flatMap((linea, i) => (/\/\/ VIOLA$/.test(linea) ? [`${MUESTRA}:${String(i + 1)}`] : []));
    const senaladas = recortesDeLaMoneda(MUESTRA, texto).map((a) => a.split(' — ')[0]);

    expect(marcadas, 'la muestra ya no trae sus seis formas malas').toHaveLength(6);
    expect(senaladas).toEqual(marcadas);
  });
});
