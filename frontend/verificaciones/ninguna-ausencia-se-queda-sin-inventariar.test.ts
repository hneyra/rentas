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
import * as elMarco from '../src/i18n/textosDelMarco.ts';

/**
 * **Ninguna frase de las que explican un hueco se queda fuera del inventario del locale** (#246,
 * #283).
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
 * Asi que esto es el **centinela de cobertura**: barre las fuentes buscando la FORMA de lo que se
 * dibuja en un hueco y exige que sus frases esten en el catalogo. No inventa el inventario;
 * comprueba que el inventario no se ha quedado corto, y sale rojo **nombrando el archivo y la
 * linea**.
 *
 * <h2>Y desde #283 barre DOS formas, porque el agujero reaparecio un nivel al lado</h2>
 *
 * Hasta #283 barria solo la `Ausencia` —`enElCampo`, `explicacion` y `tono` juntos—. Ese dia
 * `alFallar` dejo de escribir sus propias frases y paso a dibujar el **peldano** de
 * `api/escalera.ts`, que tiene ocho campos y **cuatro frases**: la palabra del hueco, el titulo,
 * el detalle y el remedio. Ninguna tiene forma de `Ausencia`, asi que el barrido de siempre **no
 * las habria visto** — el defecto exacto que #246 cerro, reapareciendo en el archivo de al lado.
 *
 * Por eso hay dos barridos y un puente entre ellos:
 *
 * · **Las ausencias.** Sus dos frases tienen que ser legibles del codigo y estar en el catalogo.
 * · **Los peldanos.** Toda cadena escrita dentro de un peldano —salvo su `clave`, que es un
 *   identificador y no una frase— tiene que estar en el catalogo, y **ninguna puede llevar un dato
 *   pegado dentro**: una plantilla con `${…}` no puede ser clave de nada.
 * · **El puente.** Una ausencia puede decir que su frase «viene de un peldano» leyendo uno de sus
 *   campos, y entonces no hace falta que sea un literal: la inventaria el otro barrido. Es el
 *   unico atajo que se admite, y esta escrito.
 *
 * <h2>Por que barre la forma y no los nombres</h2>
 *
 * Porque un nombre se elige y una forma la impone el tipo. `Ausencia` es de `@kamayuk/ui` y sus
 * tres campos son obligatorios, asi que cualquier cosa que el interprete pueda dibujar como
 * ausencia los tiene los tres — se llame `SIN_PLACA`, `FALLO` o nada, por estar escrita en linea.
 * Con `Peldano` pasa lo mismo: sus campos los impone su interfaz.
 *
 * <h2>Y el centinela del centinela</h2>
 *
 * El modo de fallo de una guarda asi no es equivocarse: es **no encontrar nada** y felicitar a un
 * arbol que no ha mirado. Si `Ausencia` cambiara de forma, o alguien moviera las frases a un
 * archivo que este barrido no alcanza, la lista saldria vacia y todo verde. Por eso lo primero que
 * se comprueba es que haya cosecha, y de mas de un archivo — y lo mismo para los peldanos.
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

/** Los campos de un literal, por nombre. Lo que no es una asignacion de propiedad no cuenta. */
function camposDe(nodo: ts.ObjectLiteralExpression): ReadonlyMap<string, ts.Expression> {
  const campos = new Map<string, ts.Expression>();
  for (const propiedad of nodo.properties) {
    if (!ts.isPropertyAssignment(propiedad)) continue;
    const nombre = propiedad.name;
    if (ts.isIdentifier(nombre) || ts.isStringLiteral(nombre)) {
      campos.set(nombre.text, propiedad.initializer);
    }
  }
  return campos;
}

/** Los tres campos que `Ausencia` obliga a tener. Es la forma, y por eso no se puede esquivar. */
const LOS_TRES_CAMPOS = ['enElCampo', 'explicacion', 'tono'] as const;

/**
 * Los campos que solo un `Peldano` tiene los cinco juntos. `enElHueco` y `estado` quedan fuera de
 * la huella a proposito: son los dos que #283 anadio, y una huella que los exija no reconoceria un
 * peldano al que alguien le quite uno — que es justo el caso en que hay que salir rojo.
 */
const LA_HUELLA_DEL_PELDANO = ['clave', 'titulo', 'detalle', 'remedio', 'esAveria'] as const;

/** Los campos de un peldano que SE DIBUJAN, o sea que son frases. `clave` y `estado` no lo son. */
const FRASES_DEL_PELDANO = ['enElHueco', 'titulo', 'detalle', 'remedio'] as const;

/**
 * **Las claves que esta expresion puede llegar a producir, o `null` si no se puede saber leyendo.**
 *
 * Acepta el literal y la **concatenacion de literales**, que es como se escribe una frase larga en
 * este arbol. No acepta un dato interpolado dentro: eso no es una clave —seria una cadena distinta
 * cada vez— y ninguna entrada del locale podria casar con ella.
 *
 * Y acepta tres formas mas, que son las que #283 trajo y las tres estan inventariadas en otro
 * sitio: la llamada a `t()` —la frase se arma donde hay `t()`, y su clave es el primer argumento—,
 * el `?:` entre dos claves, y la **lectura de un campo de un peldano**, que el barrido de peldanos
 * de mas abajo comprueba. Una lista vacia es «esto es una frase legitima que se inventaria en otra
 * parte»; `null` es «esto no puede ser clave de nada».
 */
function clavesDe(nodo: ts.Expression): readonly string[] | null {
  if (ts.isStringLiteral(nodo) || ts.isNoSubstitutionTemplateLiteral(nodo)) return [nodo.text];
  if (ts.isParenthesizedExpression(nodo)) return clavesDe(nodo.expression);
  if (ts.isBinaryExpression(nodo) && nodo.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    const izquierda = clavesDe(nodo.left);
    const derecha = clavesDe(nodo.right);
    if (izquierda === null || derecha === null) return null;
    // La concatenacion de dos frases enteras no existe aqui: lo que se concatena es UNA frase
    // partida en lineas, y entonces cada lado trae exactamente una clave.
    const una = izquierda.length === 1 ? izquierda[0] : undefined;
    const otra = derecha.length === 1 ? derecha[0] : undefined;
    if (una === undefined || otra === undefined) return null;
    return [una + otra];
  }
  if (ts.isConditionalExpression(nodo)) {
    const si = clavesDe(nodo.whenTrue);
    const no = clavesDe(nodo.whenFalse);
    return si === null || no === null ? null : [...si, ...no];
  }
  // `t(CLAVE, { … })`: la clave es el primer argumento, y el resto son los datos que se interpolan.
  if (ts.isCallExpression(nodo) && ts.isIdentifier(nodo.expression) && nodo.expression.text === 't') {
    const clave = nodo.arguments[0];
    return clave === undefined ? null : clavesDe(clave);
  }
  // Una constante del marco: se resuelve a su valor, que es la clave de verdad.
  if (ts.isIdentifier(nodo)) {
    const valor: unknown = (elMarco as Readonly<Record<string, unknown>>)[nodo.text];
    return typeof valor === 'string' ? [valor] : null;
  }
  // `peldano.titulo`: lo inventaria el barrido de peldanos, que comprueba el archivo donde vive.
  if (
    ts.isPropertyAccessExpression(nodo) &&
    (FRASES_DEL_PELDANO as readonly string[]).includes(nodo.name.text)
  ) {
    return [];
  }
  return null;
}

interface FraseHallada {
  readonly donde: string;
  /** Las claves que esa frase puede producir, o `null` si no se puede leer. */
  readonly claves: readonly string[] | null;
}

/** Todas las ausencias escritas en el arbol, sean exportadas, locales o en linea. */
function ausenciasEscritas(): readonly FraseHallada[] {
  const halladas: FraseHallada[] = [];
  for (const ruta of fuentesDeProduccion()) {
    const arbol = arbolDe(ruta);
    recorrer(arbol, (nodo) => {
      if (!ts.isObjectLiteralExpression(nodo)) return;
      const campos = camposDe(nodo);
      if (!LOS_TRES_CAMPOS.every((campo) => campos.has(campo))) return;
      const linea = arbol.getLineAndCharacterOfPosition(nodo.getStart(arbol)).line + 1;
      // `tono` no se traduce: es un nombre de tono, no una frase.
      for (const campo of ['enElCampo', 'explicacion'] as const) {
        halladas.push({
          donde: `${ruta}:${String(linea)} (${campo})`,
          claves: clavesDe(campos.get(campo) as ts.Expression),
        });
      }
    });
  }
  return halladas;
}

/** Todos los peldanos escritos en el arbol, con una entrada por frase. */
function peldanosEscritos(): readonly FraseHallada[] {
  const halladas: FraseHallada[] = [];
  for (const ruta of fuentesDeProduccion()) {
    const arbol = arbolDe(ruta);
    recorrer(arbol, (nodo) => {
      if (!ts.isObjectLiteralExpression(nodo)) return;
      const campos = camposDe(nodo);
      if (!LA_HUELLA_DEL_PELDANO.every((campo) => campos.has(campo))) return;
      const linea = arbol.getLineAndCharacterOfPosition(nodo.getStart(arbol)).line + 1;
      for (const campo of FRASES_DEL_PELDANO) {
        const escrito = campos.get(campo);
        if (escrito === undefined) {
          // Un peldano al que le falta una de sus cuatro frases: la forma cambio y hay que mirarla.
          halladas.push({ donde: `${ruta}:${String(linea)} (${campo}, QUE NO ESTA)`, claves: null });
          continue;
        }
        halladas.push({
          donde: `${ruta}:${String(linea)} (${campo})`,
          claves: clavesEscritasDentro(escrito),
        });
      }
    });
  }
  return halladas;
}

/**
 * **Las cadenas escritas dentro de la frase de un peldano.**
 *
 * `detalle` no es un literal sino `loQueDijo(fallo, «el respaldo»)`: lo que se lee es lo que el
 * backend dijo, que es DATO y no se traduce, **y si no dijo nada, el respaldo** — que si es una
 * frase de este arbol y tiene que estar en el locale. Asi que aqui no se exige que la expresion
 * entera sea una clave: se recogen las cadenas que lleve dentro, vengan solas o de argumento.
 *
 * Lo que NO se admite es una plantilla con un dato pegado —`` `… ${estado}` ``—: eso no puede ser
 * clave de nada, y es el defecto que #283 saco de dos peldanos. Devuelve `null` y sale rojo.
 */
function clavesEscritasDentro(nodo: ts.Expression): readonly string[] | null {
  // Una frase escrita entera: el literal, o la concatenacion de literales con que se parte una
  // frase larga en este arbol. Se lee con lo mismo que las ausencias, para que las dos cuenten
  // «`'a' + 'b'`» como UNA clave y no como dos — que es lo que pasaba al medir #283.
  const enteras = clavesDe(nodo);
  if (enteras !== null) return enteras;
  if (ts.isCallExpression(nodo)) {
    const claves: string[] = [];
    for (const argumento of nodo.arguments) {
      const del = clavesDe(argumento);
      if (del !== null) {
        claves.push(...del);
        continue;
      }
      // Un argumento que no es una frase —`fallo`— no aporta ninguna clave y no es un defecto.
      // Uno que pega un dato DENTRO de una frase si lo es: ver el 404 y la averia de #283.
      let pegado = false;
      recorrer(argumento, (n) => {
        if (ts.isTemplateExpression(n)) pegado = true;
      });
      if (pegado) return null;
    }
    return claves;
  }
  return null;
}

const CATALOGO = new Set(catalogoDeClaves());

/** El rojo de «esto no puede ser clave», con su motivo entero. */
function noSePuedeLeer(halladas: readonly FraseHallada[]): readonly string[] {
  return halladas.filter((f) => f.claves === null).map((f) => f.donde);
}

/** El rojo de «esto es una clave y el inventario no la tiene», con el archivo y la frase. */
function fueraDelCatalogo(halladas: readonly FraseHallada[]): readonly string[] {
  return halladas
    .flatMap((f) => (f.claves ?? []).map((clave) => [f.donde, clave] as const))
    .filter(([, clave]) => !CATALOGO.has(clave))
    .map(([donde, clave]) => `  ${donde} — «${clave}»`);
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
    // Dos frases por ausencia, y no menos de doce ausencias.
    expect(halladas.length).toBeGreaterThanOrEqual(24);
    expect(archivos.size).toBeGreaterThanOrEqual(3);
  });

  it('todas las frases de una ausencia se pueden leer del codigo', () => {
    expect(
      noSePuedeLeer(halladas),
      'Hay ausencias cuyas frases no son literales, asi que NO PUEDEN ser claves: la cadena que\n' +
        '  llegaria al interprete seria distinta cada vez y ninguna entrada del locale casaria con\n' +
        '  ella. Es lo que le pasaba a `alFallar` hasta #246. El dato entra por interpolacion, y la\n' +
        '  frase se arma donde hay `t()`: ver `FRASE_DEL_PELDANO`. Y si la frase viene de un\n' +
        '  peldano de `api/escalera.ts`, leer su campo vale: lo inventaria el barrido de abajo.',
    ).toEqual([]);
  });

  it('y todas estan en el catalogo de claves', () => {
    const fuera = fueraDelCatalogo(halladas);
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

/**
 * **Y ningun peldano de la escalera se queda sin inventariar** (#283).
 *
 * Las cuatro frases de un peldano se dibujan igual que las dos de una ausencia —`enElHueco` en el
 * hueco del campo, las otras tres en la explicacion— y hasta #283 **ninguna guarda las miraba**,
 * porque no tienen forma de `Ausencia`. Es el agujero de #246 un archivo mas alla.
 */
describe('ningun peldano de la escalera se queda sin inventariar', () => {
  const halladas = peldanosEscritos();

  it('EL CENTINELA: el barrido encuentra los peldanos, y en el archivo que los tiene', () => {
    const archivos = new Set(halladas.map((p) => p.donde.split(':')[0]));
    expect(
      [...archivos],
      'El barrido se quedo sin cosecha: o `Peldano` cambio de forma, o los peldanos se mudaron a\n' +
        '  un archivo que este recorrido no alcanza. Sin esto, «todos estan inventariados» pasaria\n' +
        '  en verde sobre la nada.',
    ).toContain('src/api/escalera.ts');
    // Siete peldanos, ocho literales —a `averia` se llega de dos maneras— y cuatro frases cada uno.
    expect(halladas.length).toBeGreaterThanOrEqual(32);
  });

  it('ninguna frase de un peldano lleva un dato pegado dentro', () => {
    expect(
      noSePuedeLeer(halladas),
      'Hay frases de un peldano con un dato dentro —una plantilla con `${…}`, o un campo que ya no\n' +
        '  esta—, y una cadena asi NO PUEDE ser clave: es distinta en cada fallo y ninguna entrada\n' +
        '  del locale casaria con ella. Es lo que le pasaba al 404 —que metia la operacion— y a la\n' +
        '  averia —que metia el estado— hasta #283. El dato sale de la frase y entra por\n' +
        '  interpolacion donde hay `t()`: ver `Peldano.estado` y `FRASE_DEL_PELDANO_CON_ESTADO`.',
    ).toEqual([]);
  });

  it('y todas estan en el catalogo de claves', () => {
    const fuera = fueraDelCatalogo(halladas);
    expect(
      fuera,
      'Hay frases de un peldano que la pantalla dibuja y el inventario del locale no conoce:\n' +
        `${fuera.join('\n')}\n\n` +
        '  En un segundo idioma salen en castellano, y la guarda del locale no lo ve: compara el\n' +
        '  locale contra el catalogo, y el catalogo tampoco las tiene. Se cierra en\n' +
        '  `i18n/catalogo-de-claves.ts`, que deriva estas frases LLAMANDO a `peldanoDe` con un\n' +
        '  fallo por peldano —ver `LOS_FALLOS_DE_LA_ESCALERA`—, y regenerando el locale.',
    ).toEqual([]);
  });
});
