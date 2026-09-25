// @vitest-environment node
//
// Lee los fuentes de `verificaciones/` del disco y los parsea. No hay DOM que necesitar: lo que se
// vigila es como esta ESCRITA una ruta, y eso no se ve ejecutando la guarda que la lleva — desde
// `frontend/`, que es desde donde se lanza siempre, la ruta mala y la buena leen lo mismo.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, sep } from 'node:path';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { RAIZ } from './artboards.ts';

/**
 * **Ninguna guarda de `verificaciones/` resuelve una ruta contra el `cwd`** (#302).
 *
 * <h2>De que defecto viene</h2>
 *
 * De los dos sitios que arreglo #295: `tailwind-emite-las-clases` componia sus fuentes con
 * `fuentesDe('src/pantallas')`, `fuentesDe('src/piezas')` y `fuentesDe('src/preferencias')`, y
 * `las-peerdependencies-estan` leia `readFileSync('package.json')`. Rutas relativas que Node
 * resuelve contra el directorio de trabajo, en un directorio donde todas las demas derivan su raiz
 * de `import.meta.url`. Funcionaban porque `yarn verificar` se lanza siempre desde `frontend/`.
 *
 * Y lo que habia que temer no era el rojo. Medido en #295 con un arbol senuelo en `/tmp` que tenia
 * un `.tsx` en `src/pantallas`, `src/piezas` y `src/preferencias`: `tailwind-emite-las-clases`
 * pasaba sus cinco pruebas **en verde sin leer ni un archivo del repositorio** —el modo de fallo de
 * #91, medir el arbol equivocado, un nivel mas arriba—. Y `las-peerdependencies-estan`, corrida
 * desde `infrastructure/`, leia el `package.json` de `infrastructure` y daba un rojo que mentia
 * sobre su sujeto: «declarado … en el package.json de este frontend».
 *
 * <h2>Por que una guarda y no «ya esta arreglado»</h2>
 *
 * Porque el censo de #295 —correr la suite con el `cwd` un nivel mas arriba— es un comando que
 * alguien tiene que acordarse de correr. Medido en #302: con las dos versiones de antes de #295
 * puestas de vuelta en el arbol, `vitest run` desde `frontend/` las pasaba **en verde, las ocho
 * pruebas**. Un tercer sitio escrito manana entra igual y se queda.
 *
 * <h2>La regla, entera</h2>
 *
 * El argumento de ruta de `readFileSync`, `readdirSync`, `existsSync`, `statSync` y
 * `writeFileSync` no puede ARRANCAR en un texto relativo. Arrancar es lo que decide contra que se
 * resuelve: un literal suelto (`'src/pantallas'`), una plantilla cuyo primer trozo es texto
 * (`` `src/${x}` ``), una concatenacion cuyo lado izquierdo lo es (`'dist/' + x`) y un
 * `join`/`resolve`/`normalize` de `node:path` cuyo primer argumento lo es (`join('src', x)`, que
 * sigue siendo relativa). `join(RAIZ, 'src/pantallas')` pasa, y un `URL` o una variable tambien:
 * lo que no es texto no se juzga aqui.
 *
 * **Y la ruta que llega por un envoltorio tambien cuenta**, que es la mitad del defecto de #295:
 * en `fuentesDe('src/pantallas')` no hay ningun `readdirSync` con un literal delante —el literal
 * esta en la llamada a `fuentesDe`, y `readdirSync(raiz)` recibe una variable—. Asi que una funcion
 * de este directorio que pasa uno de sus parametros como ruta a una de las cinco se trata como una
 * sexta, y lo mismo la que se lo pasa a esa, hasta que no aparezca ninguna nueva. Sin esto, la
 * guarda no habria visto tres de los cuatro sitios de #295. Y el valor por omision de ese parametro
 * se juzga igual que un argumento: `fuentesDeProduccion(desde = 'src')` se llama sin argumentos, y
 * en ninguna llamada habria un literal que ver.
 *
 * <h2>El ruido, medido antes de escribirla</h2>
 *
 * **Cero aciertos** en el arbol tras #295, que es lo que el issue midio: no hay nada que exceptuar,
 * y la unica exclusion son las dos carpetas de muestras (abajo). Si un dia aparece una ruta
 * relativa legitima —una que se resuelve mas adelante contra una raiz—, se declara AQUI con su
 * motivo, como hacen las demas guardas; no se relaja la regla.
 *
 * <h2>Lo que NO cubre, dicho</h2>
 *
 * Las demas funciones de `node:fs` —`mkdirSync`, `rmSync`, las asincronas—, que el issue no nombra
 * y ninguna guarda de aqui usa con una ruta relativa. Una ruta relativa guardada en una constante y
 * pasada despues (`const R = 'src'; readdirSync(R)`), porque seguirla es seguir datos y no sintaxis
 * —hoy es inocua donde aparece: `leer(ARRANQUE)` de `la-siembra-es-solo-de-desarrollo`, con
 * `ARRANQUE = 'src/arranque.ts'`, pasa por un `leer` que ya hace `join(FRONTEND, ruta)`—. Un
 * envoltorio que llega por una reexportacion (`export { f } from './otro.ts'`), que no se sigue.
 * Y el codigo fuera de `verificaciones/`: las pruebas de `src/` y el arnes de `e2e/` no son de este
 * issue.
 */

const VERIFICACIONES = join(RAIZ, 'verificaciones');

/**
 * Lo que NO se barre, y por que.
 *
 * `muestras/` son las de las prohibiciones de ESLint: violan otras reglas a proposito y no se
 * ejecutan nunca (`vitest.config.ts` las excluye). `muestra-de-rutas/` es la de ESTA guarda: la
 * viola a proposito, y se ejerce abajo, aparte.
 */
const NO_SE_BARRE = new Set(['muestras', 'muestra-de-rutas']);

const MUESTRA = join(VERIFICACIONES, 'muestra-de-rutas', 'rutas-contra-el-cwd.ts');

/** Las cinco que nombra el issue. Su primer argumento es la ruta. */
const LAS_DE_FS = [
  'readFileSync',
  'readdirSync',
  'existsSync',
  'statSync',
  'writeFileSync',
] as const;

/** Las de `node:path` que devuelven una ruta que arranca por donde arranca su primer argumento. */
const LAS_DE_PATH = ['join', 'resolve', 'normalize'] as const;

/** Todos los `.ts`, `.tsx` y `.mjs` de `verificaciones/`, sin las carpetas de muestras. */
function fuentesDeVerificaciones(desde = VERIFICACIONES): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    if (statSync(ruta).isDirectory()) {
      return NO_SE_BARRE.has(entrada) ? [] : fuentesDeVerificaciones(ruta);
    }
    return /\.(?:tsx?|mjs)$/.test(entrada) ? [ruta] : [];
  });
}

const aRelativa = (ruta: string): string => relative(RAIZ, ruta).split(sep).join('/');

function recorrer(nodo: ts.Node, visita: (n: ts.Node) => void): void {
  visita(nodo);
  nodo.forEachChild((hijo) => {
    recorrer(hijo, visita);
  });
}

/**
 * Una funcion con nombre, identificada por el archivo que la declara: `ruta#nombre`.
 *
 * **Por el archivo, y no solo por el nombre**, y no es un detalle: la primera version de esta
 * guarda indexaba los envoltorios por nombre a secas, y dio tres aciertos falsos en
 * `la-siembra-es-solo-de-desarrollo.test.ts` —`leer('Dockerfile')`, `leer('.env.development')`,
 * `leer('package.json')`—, porque ESTE archivo tiene un `leer(ruta)` que reenvia a `readFileSync`
 * y el de alli es otro, que ya resuelve contra `join(FRONTEND, ruta)`. Una llamada se resuelve por
 * lo que el archivo declara o importa.
 */
type Clave = `${string}#${string}`;

/** Un archivo parseado, con los nombres locales de lo que declara y de lo que importa. */
interface Leido {
  readonly ruta: string;
  readonly arbol: ts.SourceFile;
  /** Nombre local -> nombre canonico, para `import { readFileSync as leer }`. */
  readonly deFs: ReadonlyMap<string, string>;
  readonly dePath: ReadonlySet<string>;
  /** `import * as path from 'node:path'` y `import path from 'node:path'`. */
  readonly espaciosDePath: ReadonlySet<string>;
  /** Las funciones con nombre que declara el propio archivo. */
  readonly funciones: readonly {
    readonly nombre: string;
    readonly funcion: ts.SignatureDeclaration;
  }[];
  /** Nombre local -> la funcion de otro archivo de aqui que importa (`import { fuentesDe } from './tailwind.ts'`). */
  readonly importadas: ReadonlyMap<string, Clave>;
}

/** Las funciones con nombre de un archivo: declaradas, o una constante que es una flecha. */
function funcionesDe(arbol: ts.SourceFile): Leido['funciones'] {
  const halladas: { nombre: string; funcion: ts.SignatureDeclaration }[] = [];
  recorrer(arbol, (nodo) => {
    if (ts.isFunctionDeclaration(nodo) && nodo.name !== undefined) {
      halladas.push({ nombre: nodo.name.text, funcion: nodo });
    }
    if (
      ts.isVariableDeclaration(nodo) &&
      ts.isIdentifier(nodo.name) &&
      nodo.initializer !== undefined &&
      (ts.isArrowFunction(nodo.initializer) || ts.isFunctionExpression(nodo.initializer))
    ) {
      halladas.push({ nombre: nodo.name.text, funcion: nodo.initializer });
    }
  });
  return halladas;
}

function parsear(ruta: string): Leido {
  const arbol = ts.createSourceFile(
    ruta,
    readFileSync(ruta, 'utf8'),
    ts.ScriptTarget.ESNext,
    true,
    ruta.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS,
  );
  const deFs = new Map<string, string>(LAS_DE_FS.map((n) => [n, n]));
  const dePath = new Set<string>();
  const espaciosDePath = new Set<string>();
  const importadas = new Map<string, Clave>();

  for (const sentencia of arbol.statements) {
    if (!ts.isImportDeclaration(sentencia) || !ts.isStringLiteral(sentencia.moduleSpecifier)) {
      continue;
    }
    const especificador = sentencia.moduleSpecifier.text;
    const modulo = especificador.replace(/^node:/, '');
    const enlaces = sentencia.importClause?.namedBindings;

    if (especificador.startsWith('.')) {
      if (enlaces === undefined || ts.isNamespaceImport(enlaces)) continue;
      const destino = join(dirname(ruta), especificador);
      for (const e of enlaces.elements) {
        importadas.set(e.name.text, `${destino}#${(e.propertyName ?? e.name).text}`);
      }
      continue;
    }
    if (modulo !== 'fs' && modulo !== 'path') continue;

    const porDefecto = sentencia.importClause?.name;
    if (modulo === 'path' && porDefecto !== undefined) espaciosDePath.add(porDefecto.text);
    if (enlaces === undefined) continue;
    if (ts.isNamespaceImport(enlaces)) {
      if (modulo === 'path') espaciosDePath.add(enlaces.name.text);
      continue;
    }
    for (const e of enlaces.elements) {
      const canonico = (e.propertyName ?? e.name).text;
      if (modulo === 'fs' && (LAS_DE_FS as readonly string[]).includes(canonico)) {
        deFs.set(e.name.text, canonico);
      }
      if (modulo === 'path' && (LAS_DE_PATH as readonly string[]).includes(canonico)) {
        dePath.add(e.name.text);
      }
    }
  }

  return { ruta, arbol, deFs, dePath, espaciosDePath, funciones: funcionesDe(arbol), importadas };
}

/** `readFileSync(…)` o `fs.readFileSync(…)`: una de las cinco, llamese como se llame aqui. */
function esDeFs(llamada: ts.CallExpression, leido: Leido): boolean {
  const quien = llamada.expression;
  if (ts.isIdentifier(quien)) {
    return leido.deFs.has(quien.text) && !leido.funciones.some((f) => f.nombre === quien.text);
  }
  return (
    ts.isPropertyAccessExpression(quien) &&
    (LAS_DE_FS as readonly string[]).includes(quien.name.text)
  );
}

/** La funcion de este directorio a la que se llama, si se sabe cual es. */
function claveDe(llamada: ts.CallExpression, leido: Leido): Clave | undefined {
  const quien = llamada.expression;
  if (!ts.isIdentifier(quien)) return undefined;
  if (leido.funciones.some((f) => f.nombre === quien.text)) return `${leido.ruta}#${quien.text}`;
  return leido.importadas.get(quien.text);
}

function esDePath(llamada: ts.CallExpression, leido: Leido): boolean {
  const quien = llamada.expression;
  if (ts.isIdentifier(quien)) return leido.dePath.has(quien.text);
  return (
    ts.isPropertyAccessExpression(quien) &&
    ts.isIdentifier(quien.expression) &&
    leido.espaciosDePath.has(quien.expression.text) &&
    (LAS_DE_PATH as readonly string[]).includes(quien.name.text)
  );
}

/**
 * Por donde ARRANCA una ruta: el texto de su primer trozo, o la expresion que va primero cuando
 * lo primero no es texto.
 */
type Arranque = { readonly texto: string } | { readonly expresion: ts.Expression };

function arranqueDe(e: ts.Expression, leido: Leido): Arranque {
  if (
    ts.isParenthesizedExpression(e) ||
    ts.isAsExpression(e) ||
    ts.isSatisfiesExpression(e) ||
    ts.isNonNullExpression(e)
  ) {
    return arranqueDe(e.expression, leido);
  }
  if (ts.isStringLiteral(e) || ts.isNoSubstitutionTemplateLiteral(e)) return { texto: e.text };
  if (ts.isTemplateExpression(e)) {
    const primera = e.templateSpans[0];
    return e.head.text === '' && primera !== undefined
      ? arranqueDe(primera.expression, leido)
      : { texto: e.head.text };
  }
  if (ts.isBinaryExpression(e) && e.operatorToken.kind === ts.SyntaxKind.PlusToken) {
    return arranqueDe(e.left, leido);
  }
  const primero = ts.isCallExpression(e) ? e.arguments[0] : undefined;
  if (ts.isCallExpression(e) && esDePath(e, leido) && primero !== undefined) {
    return arranqueDe(primero, leido);
  }
  return { expresion: e };
}

/** Funcion -> posiciones de sus argumentos que acaban siendo una ruta de `node:fs`. */
type Sumideros = Map<Clave, Set<number>>;

/** Las posiciones de ruta de una llamada: `[0]` si es una de las cinco, las suyas si reenvia. */
function posicionesDeRuta(
  llamada: ts.CallExpression,
  leido: Leido,
  sumideros: Sumideros,
): readonly number[] {
  if (esDeFs(llamada, leido)) return [0];
  const clave = claveDe(llamada, leido);
  return clave === undefined ? [] : [...(sumideros.get(clave) ?? [])];
}

/**
 * Los envoltorios: las funciones que pasan uno de sus parametros como ruta a una de las cinco, o a
 * otra funcion que ya lo hace. Se repite hasta que una vuelta no anade nada.
 */
function sumiderosDe(leidos: readonly Leido[]): Sumideros {
  const sumideros: Sumideros = new Map();
  let cambio = true;
  while (cambio) {
    cambio = false;
    for (const leido of leidos) {
      for (const { nombre, funcion } of leido.funciones) {
        const clave: Clave = `${leido.ruta}#${nombre}`;
        const parametros = funcion.parameters.map((p) =>
          ts.isIdentifier(p.name) ? p.name.text : undefined,
        );
        recorrer(funcion, (nodo) => {
          if (!ts.isCallExpression(nodo)) return;
          for (const k of posicionesDeRuta(nodo, leido, sumideros)) {
            const argumento = nodo.arguments[k];
            if (argumento === undefined) continue;
            const arranque = arranqueDe(argumento, leido);
            if (!('expresion' in arranque) || !ts.isIdentifier(arranque.expresion)) continue;
            const i = parametros.indexOf(arranque.expresion.text);
            if (i === -1) continue;
            const ya = sumideros.get(clave) ?? new Set<number>();
            if (!ya.has(i)) {
              ya.add(i);
              sumideros.set(clave, ya);
              cambio = true;
            }
          }
        });
      }
    }
  }
  return sumideros;
}

interface Barrido {
  /** `ruta:linea — llamada`, uno por ruta relativa. */
  readonly aciertos: readonly string[];
  /** Cuantas llamadas a una de las cinco se vieron, sea cual sea su argumento. */
  readonly llamadasAFs: number;
  /** Los envoltorios hallados, como `verificaciones/archivo.ts#nombre`. */
  readonly envoltorios: ReadonlyMap<string, readonly number[]>;
}

function barrer(rutas: readonly string[]): Barrido {
  const leidos = rutas.map(parsear);
  const sumideros = sumiderosDe(leidos);
  const aciertos: string[] = [];
  let llamadasAFs = 0;

  for (const leido of leidos) {
    recorrer(leido.arbol, (nodo) => {
      if (!ts.isCallExpression(nodo)) return;
      if (esDeFs(nodo, leido)) llamadasAFs += 1;
      for (const k of posicionesDeRuta(nodo, leido, sumideros)) {
        const argumento = nodo.arguments[k];
        if (argumento === undefined) continue;
        const arranque = arranqueDe(argumento, leido);
        if (!('texto' in arranque) || isAbsolute(arranque.texto)) continue;
        const { line } = leido.arbol.getLineAndCharacterOfPosition(nodo.getStart());
        const llamada = nodo.getText().split('\n')[0] ?? '';
        aciertos.push(`${aRelativa(leido.ruta)}:${String(line + 1)} — ${llamada}`);
      }
    });

    // Y el valor POR OMISION del parametro de un envoltorio: `fuentesDeProduccion(desde = 'src')`
    // se llama sin argumentos, asi que en ninguna llamada hay un literal que ver.
    for (const { nombre, funcion } of leido.funciones) {
      for (const i of sumideros.get(`${leido.ruta}#${nombre}`) ?? []) {
        const porOmision = funcion.parameters[i]?.initializer;
        if (porOmision === undefined) continue;
        const arranque = arranqueDe(porOmision, leido);
        if (!('texto' in arranque) || isAbsolute(arranque.texto)) continue;
        const parametro = funcion.parameters[i];
        if (parametro === undefined) continue;
        const { line } = leido.arbol.getLineAndCharacterOfPosition(parametro.getStart());
        aciertos.push(
          `${aRelativa(leido.ruta)}:${String(line + 1)} — ${nombre}(${parametro.getText()})`,
        );
      }
    }
  }

  const envoltorios = new Map(
    [...sumideros].map(([clave, posiciones]) => {
      const [ruta = '', nombre = ''] = clave.split('#');
      return [`${aRelativa(ruta)}#${nombre}`, [...posiciones].sort()] as const;
    }),
  );
  return { aciertos, llamadasAFs, envoltorios };
}

const FUENTES = fuentesDeVerificaciones();
const ARBOL = barrer(FUENTES);

describe('#302 — ninguna ruta de `verificaciones/` se resuelve contra el `cwd`', () => {
  it('EL CENTINELA: se barrio el directorio, y se vieron las llamadas y el envoltorio de #295', () => {
    // Sin esto, un cambio de ruta o de extension dejaria el barrido sin archivos —o sin llamadas— y
    // la prueba de abajo felicitaria a un directorio que no ha leido. Medido el 2026-09-25: 63
    // archivos, 159 llamadas a una de las cinco y 22 envoltorios. Los suelos van por debajo, para
    // que borrar una guarda no ponga esto rojo; lo que tienen que cazar es el barrido a cero.
    expect(FUENTES.length, 'no se barrio ni un fuente de `verificaciones/`').toBeGreaterThanOrEqual(
      50,
    );
    expect(ARBOL.llamadasAFs, 'no se vio ni una llamada a `node:fs`').toBeGreaterThanOrEqual(100);
    expect(ARBOL.envoltorios.size, 'no se reconocio ni un envoltorio').toBeGreaterThanOrEqual(15);
    expect(FUENTES.map(aRelativa), 'la guarda no se barre a si misma').toContain(
      'verificaciones/ninguna-ruta-se-resuelve-contra-el-cwd.test.ts',
    );

    // Y el envoltorio del sitio de #295 que no tenia literal a la vista: si `fuentesDe` dejara de
    // reconocerse como envoltorio, la mitad de la regla estaria apagada y esto seguiria en verde.
    expect(
      ARBOL.envoltorios.get('verificaciones/tailwind.ts#fuentesDe'),
      '`fuentesDe` de `tailwind.ts` ya no se reconoce como envoltorio de `readdirSync`',
    ).toEqual([0]);
  });

  it('ninguna ruta arranca en un texto relativo', () => {
    expect(
      ARBOL.aciertos,
      'Estas rutas se resuelven contra el directorio de trabajo:\n' +
        `${ARBOL.aciertos.map((a) => `  ${a}`).join('\n')}\n\n` +
        '  Funcionan mientras la suite se lance desde `frontend/`. Desde otro sitio, o fallan en la\n' +
        '  recoleccion, o —peor— leen el arbol de otro en verde (#295). Derivalas de\n' +
        '  `import.meta.url`: `join(RAIZ, …)` con el `RAIZ` de `artboards.ts`.',
    ).toEqual([]);
  });
});

describe('#302 — la regla muerde: la muestra que la viola', () => {
  const MUESTRA_BARRIDA = barrer([MUESTRA]);

  it('senala las nueve formas malas, una por una, y ninguna de las cinco buenas', () => {
    // La lista entera, y no su tamano: una guarda que marcara TODA llamada a `readFileSync` daria
    // mas de nueve, y una que se saltara los envoltorios daria siete. Las lineas son las de la
    // muestra; si se edita, se reescriben aqui mirandola.
    const donde = MUESTRA_BARRIDA.aciertos.map((a) => a.split(' — ')[0]);
    expect(donde).toEqual(
      [39, 41, 43, 45, 47, 49, 51, 54, 32].map(
        (linea) => `verificaciones/muestra-de-rutas/rutas-contra-el-cwd.ts:${String(linea)}`,
      ),
    );
  });

  it('y los dos envoltorios de la muestra se reconocen como tales', () => {
    const muestra = 'verificaciones/muestra-de-rutas/rutas-contra-el-cwd.ts';
    expect(MUESTRA_BARRIDA.envoltorios.get(`${muestra}#entradasDe`)).toEqual([0]);
    expect(MUESTRA_BARRIDA.envoltorios.get(`${muestra}#entradasPorOmision`)).toEqual([0]);
  });
});
