// @vitest-environment node
//
// Lee el arbol del disco y lo parsea. Montar las 40 pantallas no serviria, y es justo el motivo de
// que esta guarda exista: las frases de un 401, de un 404 o de una sesion caida **no se dibujan**
// en un montaje sin doble de `fetch`, asi que `todo-el-texto-se-traduce` nunca las ve pasar.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { RAIZ } from './artboards.ts';
import { catalogoDeClaves } from '../src/i18n/catalogo-de-claves.ts';

/**
 * **Ninguna `Ausencia` del arbol se queda fuera del inventario del locale** (#246).
 *
 * <h2>De que defecto viene, y por que no basta con derivar</h2>
 *
 * `i18n/catalogo-de-claves.ts` listaba las ausencias **a mano**, una importacion por constante, y
 * el agujero mordio tres veces sin producir un solo rojo: #215 encontro cinco de
 * `useDatosDeLaHoja.ts` sin listar, #237 anadio tres mas y tuvo que acordarse, y al medir #246
 * seguian fuera las **cinco frases de `alFallar`** y **`SIN_PARAMETROS_DEL_SORTEO`**. Ninguna de
 * las guardas lo veia: la del locale compara el locale contra el catalogo, y el catalogo tampoco
 * las tenia.
 *
 * #246 pasa a **derivarlas** —de lo que los modulos de frases exportan y de lo que los conectores
 * declaran—, y eso cubre lo corriente. Pero derivar por la forma tiene un limite exacto y conviene
 * escribirlo: **solo ve lo que es un valor exportado**. Una `Ausencia` escrita dentro de una
 * funcion —que es como estaban las de `alFallar`— o declarada en un modulo que la derivacion no
 * recorre sigue siendo invisible, y el olvido volveria a no tener rojo.
 *
 * Asi que esto es el **centinela de cobertura**: barre las fuentes buscando la FORMA de una
 * ausencia —`enElCampo`, `explicacion` y `tono` juntos— y exige que sus dos frases esten en el
 * catalogo. No inventa el inventario; comprueba que el inventario no se ha quedado corto, y sale
 * rojo **nombrando el archivo y la linea**.
 *
 * <h2>Por que barre la forma y no los nombres</h2>
 *
 * Porque un nombre se elige y una forma la impone el tipo. `Ausencia` es de `@kamayuk/ui` y sus
 * tres campos son obligatorios, asi que cualquier cosa que el interprete pueda dibujar como
 * ausencia los tiene los tres — se llame `SIN_PLACA`, `FALLO` o nada, por estar escrita en linea.
 *
 * <h2>Y el centinela del centinela</h2>
 *
 * El modo de fallo de una guarda asi no es equivocarse: es **no encontrar nada** y felicitar a un
 * arbol que no ha mirado. Si `Ausencia` cambiara de forma, o alguien moviera las frases a un
 * archivo que este barrido no alcanza, la lista saldria vacia y todo verde. Por eso lo primero que
 * se comprueba es que haya cosecha, y de mas de un archivo.
 */

/** Todos los `.ts`/`.tsx` de produccion bajo `src/`, con su ruta relativa a `frontend/`. */
function fuentesDeProduccion(desde = join(RAIZ, 'src')): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    if (statSync(ruta).isDirectory()) return fuentesDeProduccion(ruta);
    if (!/\.tsx?$/.test(entrada) || /\.test\.tsx?$/.test(entrada)) return [];
    return [relative(RAIZ, ruta).split(sep).join('/')];
  });
}

function arbolDe(ruta: string): ts.SourceFile {
  return ts.createSourceFile(
    ruta,
    readFileSync(join(RAIZ, ruta), 'utf8'),
    ts.ScriptTarget.ESNext,
    true,
    ts.ScriptKind.TSX,
  );
}

function recorrer(nodo: ts.Node, visita: (n: ts.Node) => void): void {
  visita(nodo);
  nodo.forEachChild((hijo) => {
    recorrer(hijo, visita);
  });
}

/** Los tres campos que `Ausencia` obliga a tener. Es la forma, y por eso no se puede esquivar. */
const LOS_TRES_CAMPOS = ['enElCampo', 'explicacion', 'tono'] as const;

/**
 * El texto de una frase escrita, o `null` si no se puede saber leyendo.
 *
 * Acepta el literal y la **concatenacion de literales**, que es como se escribe una frase larga en
 * este arbol. No acepta un dato interpolado dentro: eso no es una clave —seria una cadena distinta
 * cada vez— y ninguna entrada del locale podria casar con ella. Ver `FRASE_DEL_FALLO`.
 */
function frase(nodo: ts.Expression): string | null {
  if (ts.isStringLiteral(nodo) || ts.isNoSubstitutionTemplateLiteral(nodo)) return nodo.text;
  if (ts.isParenthesizedExpression(nodo)) return frase(nodo.expression);
  if (ts.isBinaryExpression(nodo) && nodo.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    const izquierda = frase(nodo.left);
    const derecha = frase(nodo.right);
    return izquierda === null || derecha === null ? null : izquierda + derecha;
  }
  return null;
}

interface AusenciaHallada {
  readonly donde: string;
  /** El texto de cada uno de los dos campos que se traducen, o `null` si no se puede leer. */
  readonly textos: readonly (string | null)[];
}

/** Todas las ausencias escritas en el arbol, sean exportadas, locales o en linea. */
function ausenciasEscritas(): readonly AusenciaHallada[] {
  const halladas: AusenciaHallada[] = [];
  for (const ruta of fuentesDeProduccion()) {
    const arbol = arbolDe(ruta);
    recorrer(arbol, (nodo) => {
      if (!ts.isObjectLiteralExpression(nodo)) return;
      const campos = new Map<string, ts.Expression>();
      for (const propiedad of nodo.properties) {
        if (!ts.isPropertyAssignment(propiedad)) continue;
        const nombre = propiedad.name;
        if (ts.isIdentifier(nombre) || ts.isStringLiteral(nombre)) {
          campos.set(nombre.text, propiedad.initializer);
        }
      }
      if (!LOS_TRES_CAMPOS.every((campo) => campos.has(campo))) return;
      const linea = arbol.getLineAndCharacterOfPosition(nodo.getStart(arbol)).line + 1;
      halladas.push({
        donde: `${ruta}:${String(linea)}`,
        // `tono` no se traduce: es un nombre de tono, no una frase.
        textos: [frase(campos.get('enElCampo') as ts.Expression), frase(campos.get('explicacion') as ts.Expression)],
      });
    });
  }
  return halladas;
}

describe('ninguna ausencia se queda sin inventariar', () => {
  const halladas = ausenciasEscritas();

  it('EL CENTINELA: el barrido encuentra ausencias, y en mas de un archivo', () => {
    const archivos = new Set(halladas.map((a) => a.donde.split(':')[0]));
    expect(
      { cuantas: halladas.length, archivos: archivos.size },
      'El barrido se quedo sin cosecha: o `Ausencia` cambio de forma, o las frases se mudaron a un\n' +
        '  sitio que este recorrido no alcanza. Sin esto, «todas estan inventariadas» pasaria en\n' +
        '  verde sobre la nada.',
    ).toEqual({ cuantas: halladas.length, archivos: archivos.size });
    expect(halladas.length).toBeGreaterThanOrEqual(12);
    expect(archivos.size).toBeGreaterThanOrEqual(3);
  });

  it('todas las frases de una ausencia se pueden leer del codigo', () => {
    const ilegibles = halladas.filter((a) => a.textos.includes(null)).map((a) => a.donde);
    expect(
      ilegibles,
      'Hay ausencias cuyas frases no son literales, asi que NO PUEDEN ser claves: la cadena que\n' +
        '  llegaria al interprete seria distinta cada vez y ninguna entrada del locale casaria con\n' +
        '  ella. Es lo que le pasaba a `alFallar` hasta #246. El dato entra por interpolacion, y la\n' +
        '  frase se arma donde hay `t()`: ver `FRASE_DEL_FALLO`.',
    ).toEqual([]);
  });

  it('y todas estan en el catalogo de claves', () => {
    const inventariadas = new Set(catalogoDeClaves());
    const fuera = halladas
      .flatMap((a) => a.textos.filter((t): t is string => t !== null).map((t) => [a.donde, t]))
      .filter(([, texto]) => !inventariadas.has(texto as string))
      .map(([donde, texto]) => `  ${String(donde)} — «${String(texto)}»`);
    expect(
      fuera,
      'Hay ausencias que el interprete traduce y el inventario del locale no conoce:\n' +
        `${fuera.join('\n')}\n\n` +
        '  En un segundo idioma esas frases salen en castellano, y la guarda del locale no lo ve:\n' +
        '  compara el locale contra el catalogo, y el catalogo tampoco las tiene. Se cierra\n' +
        '  declarandolas donde la derivacion de `i18n/catalogo-de-claves.ts` las alcance —exportadas\n' +
        '  en su modulo de frases, o en el conector que las dice—, y regenerando el locale.',
    ).toEqual([]);
  });
});
