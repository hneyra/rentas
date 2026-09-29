// @vitest-environment node
//
// Parsea el arnes, y lo que el arnes alcanza, con el compilador de TypeScript y sin verificador de
// tipos: lo que se vigila es como esta ESCRITO cada `import`, que es lo que decide si Node carga el
// modulo. No hay DOM que necesitar.

import { existsSync, readFileSync, readdirSync, realpathSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { CLAVES_DE_HOJA } from '../src/pantallas/arbol.ts';
import { RAIZ } from './artboards.ts';

/**
 * **Lo que el arnes de Playwright carga en Node solo importa TIPOS de `@kamayuk/*`** (#643).
 *
 * <h2>De que defecto viene</h2>
 *
 * `e2e/los-cuarenta.spec.ts` importa `src/pantallas/definiciones/index.ts` para declarar sus
 * caminos, y Playwright lo carga **en Node**, con todo lo que alcanza. En #635, la parte de #172
 * escribio en `definiciones/transito.ts`:
 *
 *     import { EL_SUJETO, type DefinicionDePantalla as Pantalla } from '@kamayuk/ui';
 *
 * Un VALOR de `@kamayuk/ui` carga su indice entero, hasta `shadcn/boton.tsx`, que importa
 * `class-variance-authority`. La libreria llega por un `link:` al clon hermano, Node sigue el
 * enlace y resuelve esas dependencias subiendo por el arbol DEL CLON — que en la CI se clona y no
 * se instala. El arnes no llego a cargar ni un camino:
 *
 *     Error: Cannot find package 'class-variance-authority' imported from
 *       …/kamayuk-lib/paquetes/ui/shadcn/boton.tsx
 *     Total: 0 tests in 0 files
 *
 * `yarn verificar` estaba en verde: `tsc` resuelve con `preserveSymlinks` (#88) y Vite con
 * `resolve.dedupe`, los dos contra ESTE `node_modules`. Solo Node a secas sube por el clon, y a
 * Node solo lo lanza `yarn e2e`. #635 lo arreglo con una constante atada por el tipo
 * (`satisfies typeof import('@kamayuk/ui').EL_SUJETO`); esto es lo que lo caza la proxima vez.
 *
 * <h2>La regla, entera</h2>
 *
 * Desde cada raiz del arnes —`playwright.config.ts`, que Playwright carga antes que nada, y todo
 * `e2e/`— se sigue cada referencia que es una RUTA y carga su modulo: un `import` de valor o de
 * efecto, un `export … from` y un `import()` con el especificador escrito, sea relativo, absoluto
 * o una URL `file:` —medido con Node 24.21, las tres cargan el archivo igual—. Y en todo lo
 * alcanzado, una referencia a `@kamayuk/*` sale roja si no es solo de tipos, con el archivo, la
 * linea, el especificador, lo que trae como valor y el camino por el que el arnes llega hasta ella.
 *
 * Tambien sale roja una ruta que **no resuelve**, o que resuelve **fuera de `frontend/`**: la
 * primera deja el recorrido sin saber que se carga, y la segunda llega a un arbol cuyas
 * dependencias resuelven contra otro `node_modules` —el clon hermano alcanzado por su ruta, sea
 * relativa o absoluta, en vez de por `@kamayuk/*` es el mismo defecto por otra puerta—. Hoy no hay
 * ninguna de las dos.
 *
 * <h2>Solo de tipos es `import type` o `export type`, y nada mas</h2>
 *
 * La regla sigue a `verbatimModuleSyntax`, que es lo que declara `tsconfig.base.json`: se borra
 * entera la declaracion marcada con `type`, y de una lista se borran solo los especificadores que
 * lo llevan. Asi que `import { type X } from 'x'`, con `type` en TODOS, se queda en
 * `import {} from 'x'`, y `export { type X } from 'x'` en `export {} from 'x'`: las dos CARGAN el
 * modulo. Cuentan como carga —rojas si son de `@kamayuk/*`, y seguidas si son rutas—, y el
 * remedio que dice el rojo es `import type` o `export type`.
 *
 * **No sigue a lo que hace HOY el cargador del arnes, y es a proposito.** Medido en el arnes, cada
 * forma escrita en `transito.ts` y `npx playwright test --list` con el clon sin `node_modules`:
 * cargan la libreria (0 caminos) el valor entre tipos de #635, `import {} from`,
 * `export { X } from` y `export {} from`; y no la cargan (81 caminos) `import type`,
 * `import type * as`, `export type { X } from` y `export type * from` —y tampoco
 * `import { type X }` ni `export { type X } from`, porque Playwright 1.63 transpila con el
 * `preset-typescript` de Babel, que por omision (`onlyRemoveTypeImports: false`) las borra
 * enteras—. Pero medido con el cargador nativo de Node 24.21, que quita los tipos con la semantica
 * de `verbatimModuleSyntax`: `import { type T } from './a.ts'` y `export { type T } from './a.ts'`
 * CARGAN `a.ts`, e `import type` no. Una guarda que siguiera a Babel dejaria pasar en verde, el
 * dia que Playwright cambie de transpilador, justo lo que carga la libreria. Y hoy no cuesta nada:
 * medido el 2026-09-29, en los 199 archivos de codigo de `frontend/` que no son la muestra no
 * habia ni una lista asi.
 *
 * **Y por eso no se siguen las rutas de `import type`**, y no es una omision: esas las borra
 * cualquier cargador. Medido en el arbol: `e2e/el-403-del-catalogo.spec.ts` importa con
 * `import type` un tipo de `src/datos/useCatalogoPermitido.ts`, que llega por `src/catalogo.ts` a
 * `import { ICONOS } from '@kamayuk/ui'`, y el arnes carga sus caminos. Siguiendo tambien los
 * `import type`, esta guarda daria ese rojo, y seria falso.
 *
 * <h2>Por que TODO `@kamayuk/*`, y no solo los paquetes que hoy tienen dependencias</h2>
 *
 * Medido: `import { sumarImportes } from '@kamayuk/formato'` en `transito.ts` carga con el clon
 * sin dependencias —81 caminos—, porque `formato` no tiene ninguna. Pero eso lo decide otro
 * repositorio, y el dia que cambie aqui solo se veria en la CI, como «Total: 0 tests in 0 files».
 * Lo que el arnes necesita de la libreria son sus TIPOS, y un tipo se ata sin cargar nada:
 * `import type`, o `typeof import(…)`.
 *
 * <h2>Lo que NO se hace, y por que</h2>
 *
 * Instalar las dependencias de la libreria en la CI. Pondria verde el arnes y enmascararia lo mismo
 * que `preserveSymlinks` destapo en #88: el consumidor resolviendo contra un arbol que no es el
 * suyo.
 *
 * <h2>Lo que NO cubre, dicho</h2>
 *
 * Un `import()` cuyo especificador no es un literal —una plantilla con sustituciones, una
 * variable—, porque seguirlo es seguir datos y no sintaxis. Un especificador que no es ni ruta ni
 * paquete —`#…`, los `imports` de `package.json`, que este no declara; o una URL `data:`—: el
 * recorrido no lo sigue, y hoy no hay ninguno. `require` y `createRequire`: el arnes es ESM, y el
 * unico `createRequire` alcanzado —el de `verificaciones/especificadores.ts`— solo RESUELVE rutas
 * con `require.resolve`, que no carga nada. Y lo que un paquete de `node_modules` que no es de la
 * libreria haga dentro: ese resuelve contra este arbol, que si lo tiene instalado.
 */

/** La raiz del frontend por su ruta REAL: lo alcanzado se compara con ella tras seguir enlaces. */
const FRONTEND = realpathSync(RAIZ);
const E2E = join(FRONTEND, 'e2e');
const CONFIGURACION = join(FRONTEND, 'playwright.config.ts');
const DEFINICIONES = join(FRONTEND, 'src/pantallas/definiciones');
const INDICE = join(DEFINICIONES, 'index.ts');
const MUESTRA = join(FRONTEND, 'verificaciones/muestra-del-grafo-del-arnes');

/** Lo que Node carga como codigo, y Playwright transpila. Lo demas —JSON, CSS— es una hoja. */
const CODIGO = /\.(?:[cm]?[jt]s|[jt]sx)$/;

const DE_LA_LIBRERIA = /^@kamayuk\//;

/** Como se prueba una ruta sin extension, en el orden en que se prueba. */
const EXTENSIONES = ['.ts', '.tsx', '.mts', '.js', '.mjs'] as const;

const aRelativa = (ruta: string): string => relative(FRONTEND, ruta).split(sep).join('/');

function codigoDe(desde: string): readonly string[] {
  return readdirSync(desde)
    .flatMap((entrada) => {
      const ruta = join(desde, entrada);
      if (statSync(ruta).isDirectory()) return codigoDe(ruta);
      return CODIGO.test(entrada) ? [ruta] : [];
    })
    .sort();
}

/** Un `import`, un `export … from` o un `import()` de un archivo. */
interface Referencia {
  readonly linea: number;
  readonly especificador: string;
  /**
   * Lo que hace que Node cargue el modulo, dicho como se escribio —`EL_SUJETO`, `* as ui`,
   * `de efecto`—, o `undefined` si es SOLO de tipos: un `import type` o un `export type`, que se
   * borran enteros con cualquier cargador. Y nada mas lo es: ver la cabecera.
   */
  readonly valor: string | undefined;
}

type Especificadores = ts.NodeArray<ts.ImportSpecifier> | ts.NodeArray<ts.ExportSpecifier>;

/** Los nombres de valor de una lista de especificadores: los que no llevan `type` delante. */
const deValor = (especificadores: Especificadores): readonly string[] =>
  especificadores.filter((e) => !e.isTypeOnly).map((e) => (e.propertyName ?? e.name).text);

/**
 * Lo que carga una lista NO vacia, sin nada al lado: sus nombres de valor. Y si todos llevan
 * `type`, la declaracion NO se borra: con `verbatimModuleSyntax` se borran los especificadores y
 * queda `import {} from 'x'`, que carga. El remedio es marcar la declaracion, no los nombres.
 */
function deLaLista(palabra: 'import' | 'export', especificadores: Especificadores): string {
  const valores = deValor(especificadores);
  if (valores.length > 0) return valores.join(', ');
  const escrita = especificadores.map((e) => e.getText()).join(', ');
  return (
    `de efecto (\`${palabra} { ${escrita} }\` se queda en \`${palabra} {}\` con ` +
    `verbatimModuleSyntax: escribe \`${palabra} type\`)`
  );
}

function loQueImporta(clausula: ts.ImportClause | undefined): string | undefined {
  // `import 'x'` no trae nombres, y justo por eso se carga: es su unica razon de ser.
  if (clausula === undefined) return 'de efecto';
  // Lo UNICO que se deja pasar: `import type`, que se borra entero con cualquier cargador.
  if (clausula.isTypeOnly) return undefined;
  const enlaces = clausula.namedBindings;
  if (clausula.name === undefined && enlaces !== undefined && ts.isNamedImports(enlaces)) {
    // `import {} from 'x'` se queda como `import 'x'`. Medido: carga la libreria entera.
    if (enlaces.elements.length === 0) return 'de efecto (`{}`)';
    return deLaLista('import', enlaces.elements);
  }
  const valores: string[] = [];
  if (clausula.name !== undefined) valores.push(`${clausula.name.text} (por omision)`);
  if (enlaces !== undefined && ts.isNamespaceImport(enlaces)) {
    valores.push(`* as ${enlaces.name.text}`);
  }
  // `import X, { type Y }`: carga por `X`, y de la lista solo cuentan los nombres de valor.
  if (enlaces !== undefined && ts.isNamedImports(enlaces)) {
    valores.push(...deValor(enlaces.elements));
  }
  // Con `X` o con `* as` nunca queda vacia; y si quedara, lo que no es `import type` carga.
  return valores.length === 0 ? 'de efecto' : valores.join(', ');
}

function loQueReexporta(declaracion: ts.ExportDeclaration): string | undefined {
  // Lo UNICO que se deja pasar: `export type`, como `import type`.
  if (declaracion.isTypeOnly) return undefined;
  const clausula = declaracion.exportClause;
  if (clausula === undefined) return 'export *';
  if (ts.isNamespaceExport(clausula)) return `export * as ${clausula.name.text}`;
  // `export {} from 'x'`, como `import {}`. Medido: tambien carga.
  if (clausula.elements.length === 0) return 'de efecto (`export {}`)';
  return deLaLista('export', clausula.elements);
}

function parsear(ruta: string): ts.SourceFile {
  const tipo = /\.[jt]sx$/.test(ruta)
    ? ts.ScriptKind.TSX
    : /\.[cm]?js$/.test(ruta)
      ? ts.ScriptKind.JS
      : ts.ScriptKind.TS;
  return ts.createSourceFile(ruta, readFileSync(ruta, 'utf8'), ts.ScriptTarget.ESNext, true, tipo);
}

function referenciasDe(arbol: ts.SourceFile): readonly Referencia[] {
  const halladas: Referencia[] = [];
  const lineaDe = (nodo: ts.Node): number =>
    arbol.getLineAndCharacterOfPosition(nodo.getStart(arbol)).line + 1;

  const visitar = (nodo: ts.Node): void => {
    if (ts.isImportDeclaration(nodo) && ts.isStringLiteral(nodo.moduleSpecifier)) {
      halladas.push({
        linea: lineaDe(nodo),
        especificador: nodo.moduleSpecifier.text,
        valor: loQueImporta(nodo.importClause),
      });
    }
    if (
      ts.isExportDeclaration(nodo) &&
      nodo.moduleSpecifier !== undefined &&
      ts.isStringLiteral(nodo.moduleSpecifier)
    ) {
      halladas.push({
        linea: lineaDe(nodo),
        especificador: nodo.moduleSpecifier.text,
        valor: loQueReexporta(nodo),
      });
    }
    // `import('x')` es una llamada; `typeof import('x')` es un TIPO (`ImportTypeNode`) y no pasa
    // por aqui, que es justo lo que lo deja pasar.
    if (ts.isCallExpression(nodo) && nodo.expression.kind === ts.SyntaxKind.ImportKeyword) {
      const [argumento] = nodo.arguments;
      if (argumento !== undefined && ts.isStringLiteralLike(argumento)) {
        halladas.push({
          linea: lineaDe(nodo),
          especificador: argumento.text,
          valor: 'import() dinamico',
        });
      }
    }
    ts.forEachChild(nodo, visitar);
  };
  visitar(arbol);
  return halladas;
}

/**
 * Lo que Node resuelve como RUTA y no contra `node_modules`: un relativo, una ruta absoluta o una
 * URL `file:`. Medido con Node 24.21: `import '/…/a.ts'` e `import 'file:///…/a.ts'` cargan `a.ts`
 * igual que `import './a.ts'`, asi que el clon hermano por su ruta entra por las tres puertas.
 */
const ES_RUTA = /^(?:\.|\/|file:)/;

/**
 * El archivo al que apunta una ruta, por su ruta real; o `undefined` si no hay ninguno. Una URL
 * `file:` Node la toma entera y sin base —`new URL(especificador)`—, asi que es absoluta siempre.
 */
function resolverRuta(desde: string, especificador: string): string | undefined {
  let base: string;
  if (especificador.startsWith('file:')) {
    try {
      base = fileURLToPath(new URL(especificador));
    } catch {
      return undefined;
    }
  } else {
    base = resolve(dirname(desde), especificador);
  }
  const candidatos = [
    base,
    ...EXTENSIONES.map((extension) => `${base}${extension}`),
    // `./x.js` escrito para un `x.ts`, que TypeScript admite: sin esto, un «no resuelve» falso.
    ...(base.endsWith('.js') ? [base.replace(/\.js$/, '.ts'), base.replace(/\.js$/, '.tsx')] : []),
    ...EXTENSIONES.map((extension) => join(base, `index${extension}`)),
  ];
  const hallado = candidatos.find((c) => existsSync(c) && statSync(c).isFile());
  return hallado === undefined ? undefined : realpathSync(hallado);
}

/** Una referencia que rompe la regla, con el camino por el que el arnes la alcanza. */
interface Rojo {
  /** `ruta:linea — 'especificador' <por que>`. */
  readonly que: string;
  readonly camino: readonly string[];
}

interface Recorrido {
  /** Cada archivo alcanzado, relativo a `frontend/`. */
  readonly alcanzados: ReadonlySet<string>;
  readonly rojos: readonly Rojo[];
  /** Las referencias a `@kamayuk/*` que eran solo de tipos: las que la regla deja pasar. */
  readonly soloDeTipos: number;
}

function recorrer(raices: readonly string[]): Recorrido {
  const alcanzados = new Map<string, readonly string[]>();
  const rojos: Rojo[] = [];
  let soloDeTipos = 0;
  const pendientes = raices.map((raiz) => ({
    ruta: realpathSync(raiz),
    camino: [aRelativa(raiz)],
  }));

  for (let siguiente = pendientes.shift(); siguiente; siguiente = pendientes.shift()) {
    const { ruta, camino } = siguiente;
    if (alcanzados.has(ruta)) continue;
    alcanzados.set(ruta, camino);
    if (!CODIGO.test(ruta)) continue;

    for (const { linea, especificador, valor } of referenciasDe(parsear(ruta))) {
      const donde = `${aRelativa(ruta)}:${String(linea)} — '${especificador}'`;
      if (DE_LA_LIBRERIA.test(especificador)) {
        if (valor === undefined) soloDeTipos += 1;
        else rojos.push({ que: `${donde} carga la libreria: ${valor}`, camino });
        continue;
      }
      // Lo que no es una ruta —`node:fs`, `@playwright/test`— resuelve contra este `node_modules`,
      // que si esta instalado. Y una ruta de `import type` no la carga nadie: no se sigue.
      if (!ES_RUTA.test(especificador) || valor === undefined) continue;
      const destino = resolverRuta(ruta, especificador);
      if (destino === undefined) {
        rojos.push({ que: `${donde} no resuelve: el recorrido no sabe que carga`, camino });
      } else if (!destino.startsWith(FRONTEND + sep)) {
        rojos.push({ que: `${donde} sale de frontend/ (${destino})`, camino });
      } else {
        pendientes.push({ ruta: destino, camino: [...camino, aRelativa(destino)] });
      }
    }
  }
  return { alcanzados: new Set([...alcanzados.keys()].map(aRelativa)), rojos, soloDeTipos };
}

/**
 * Las claves de pantalla que declaran los archivos de `definiciones/` alcanzados: las propiedades
 * de cada `export const X = { 'ini-panel': …, … } satisfies …`. Es el centinela de abajo.
 */
function pantallasAlcanzadas(alcanzados: ReadonlySet<string>): ReadonlySet<string> {
  const claves = new Set<string>();
  for (const relativa of alcanzados) {
    const ruta = join(FRONTEND, relativa);
    if (!ruta.startsWith(DEFINICIONES + sep)) continue;
    for (const sentencia of parsear(ruta).statements) {
      if (!ts.isVariableStatement(sentencia)) continue;
      if (!sentencia.modifiers?.some((m) => m.kind === ts.SyntaxKind.ExportKeyword)) continue;
      for (const { initializer } of sentencia.declarationList.declarations) {
        let valor = initializer;
        while (
          valor !== undefined &&
          (ts.isSatisfiesExpression(valor) || ts.isAsExpression(valor))
        ) {
          valor = valor.expression;
        }
        if (valor === undefined || !ts.isObjectLiteralExpression(valor)) continue;
        for (const propiedad of valor.properties) {
          if (!ts.isPropertyAssignment(propiedad)) continue;
          if (ts.isStringLiteral(propiedad.name) || ts.isIdentifier(propiedad.name)) {
            claves.add(propiedad.name.text);
          }
        }
      }
    }
  }
  return claves;
}

const mostrar = (rojos: readonly Rojo[]): string =>
  rojos.map(({ que, camino }) => `  ${que}\n      lo carga: ${camino.join(' → ')}`).join('\n');

const ARNES = recorrer([CONFIGURACION, ...codigoDe(E2E)]);

describe('#643 — lo que el arnes carga en Node solo importa tipos de `@kamayuk/*`', () => {
  it('EL CENTINELA: el recorrido llega a `definiciones/index.ts` y a todas las pantallas', () => {
    // Sin esto, un recorrido que no siguiera nada —o que dejara de resolver los `.ts`— se quedaria
    // en las raices, no veria ni un `@kamayuk/*` y la prueba de abajo felicitaria a un grafo que
    // no ha mirado. Las pantallas se cuentan por `CLAVES_DE_HOJA`, que sale del arbol y cuya
    // cuenta —cuarenta— vigila `pantallas-del-artboard.test.ts`: aqui no se escribe un 40.
    expect([...ARNES.alcanzados], 'el recorrido no llego al indice de las pantallas').toContain(
      aRelativa(INDICE),
    );
    const alcanzadas = pantallasAlcanzadas(ARNES.alcanzados);
    expect(
      CLAVES_DE_HOJA.filter((clave) => !alcanzadas.has(clave)),
      'el recorrido no llego a los archivos que declaran estas pantallas',
    ).toEqual([]);
    // Y que se hayan LEIDO referencias a la libreria: las definiciones importan sus tipos de
    // `@kamayuk/ui`. Medido el 2026-09-29: 14, en 39 archivos alcanzados. El suelo va por debajo,
    // para que quitar una no ponga esto rojo; lo que tiene que cazar es el barrido a cero.
    expect(ARNES.soloDeTipos, 'no se vio ni un `import type` de la libreria').toBeGreaterThan(9);
  });

  it('ninguna referencia a `@kamayuk/*` que el arnes alcanza carga un valor', () => {
    expect(
      ARNES.rojos.map((rojo) => rojo.que),
      'El arnes de Playwright carga estos archivos en Node, y Node sigue el `link:` hasta el\n' +
        'clon hermano, que en la CI no tiene dependencias: un valor de `@kamayuk/*` puede dejar\n' +
        'la corrida en «Total: 0 tests in 0 files» (#635), y `yarn verificar` no lo ve.\n' +
        `${mostrar(ARNES.rojos)}\n\n` +
        '  Importa solo el tipo —`import type { … }`, y no `import { type … }`, que con\n' +
        '  `verbatimModuleSyntax` se queda en `import {}` y carga— y, si hace falta el valor,\n' +
        '  atalo por el tipo sin cargarlo, como `definiciones/transito.ts` desde #635:\n' +
        "    const EL_SUJETO = 'sujeto' satisfies typeof import('@kamayuk/ui').EL_SUJETO;",
    ).toEqual([]);
  });
});

describe('#643 — la regla muerde: la muestra que la viola', () => {
  const MUESTRA_RECORRIDA = recorrer([join(MUESTRA, 'raiz.ts')]);
  const muestra = aRelativa(MUESTRA);
  /** Lo que la forma (10) alcanza fuera de `frontend/`: el `.nvmrc` de la raiz del repositorio. */
  const FUERA = resolve(FRONTEND, '..', '.nvmrc');

  it('senala cada forma mala, con archivo, linea y especificador, y ninguna de las buenas', () => {
    // La lista entera, y no su tamano: una guarda que marcara todo `@kamayuk/*` daria mas —las
    // tres buenas de `raiz.ts` y la de `solo-por-tipo.ts`—, y una que no siguiera los relativos,
    // tres. Las lineas son las de la muestra; si se edita, se reescriben aqui mirandola.
    const noResuelve = 'no resuelve: el recorrido no sabe que carga';
    const clon = 'no-existe-en-ningun-puesto/kamayuk-lib/paquetes/ui/index.ts';
    expect(MUESTRA_RECORRIDA.rojos.map((rojo) => rojo.que)).toEqual([
      `${muestra}/raiz.ts:27 — '@kamayuk/ui' carga la libreria: EL_SUJETO`,
      `${muestra}/raiz.ts:31 — '@kamayuk/ui' carga la libreria: de efecto ` +
        '(`import { type DatoConNombre, type TonoDeInsignia }` se queda en `import {}` ' +
        'con verbatimModuleSyntax: escribe `import type`)',
      `${muestra}/raiz.ts:38 — '@kamayuk/shell' carga la libreria: de efecto ` +
        '(`export { type Catalogo }` se queda en `export {}` ' +
        'con verbatimModuleSyntax: escribe `export type`)',
      `${muestra}/alcanzado.ts:8 — '@kamayuk/ui' carga la libreria: * as ui`,
      `${muestra}/alcanzado.ts:10 — '@kamayuk/shell' carga la libreria: de efecto`,
      `${muestra}/alcanzado.ts:12 — '@kamayuk/ui' carga la libreria: de efecto (\`{}\`)`,
      `${muestra}/alcanzado.ts:15 — '@kamayuk/formato' carga la libreria: formato (por omision)`,
      `${muestra}/alcanzado.ts:17 — '@kamayuk/formato' carga la libreria: sumarImportes`,
      `${muestra}/alcanzado.ts:19 — './no-existe.ts' ${noResuelve}`,
      `${muestra}/alcanzado.ts:23 — '../../../.nvmrc' sale de frontend/ (${FUERA})`,
      `${muestra}/alcanzado.ts:28 — '/${clon}' ${noResuelve}`,
      `${muestra}/alcanzado.ts:30 — 'file:///${clon}' ${noResuelve}`,
      `${muestra}/alcanzado.ts:33 — '@kamayuk/ui' carga la libreria: EL_SUJETO`,
      `${muestra}/alcanzado.ts:35 — '@kamayuk/formato' carga la libreria: export *`,
      `${muestra}/alcanzado.ts:36 — '@kamayuk/api' carga la libreria: export * as api`,
      `${muestra}/alcanzado.ts:38 — '@kamayuk/ui' carga la libreria: de efecto (\`export {}\`)`,
      `${muestra}/alcanzado.ts:45 — '@kamayuk/ui' carga la libreria: import() dinamico`,
      `${muestra}/por-tipos-entre-llaves.ts:9 — '@kamayuk/formato' carga la libreria: ` +
        'sumarImportes',
      `${muestra}/por-reexportacion.ts:7 — '@kamayuk/formato' carga la libreria: mismosCentimos`,
    ]);
  });

  it('y el camino de cada rojo empieza en la raiz: por ahi lo carga el arnes', () => {
    expect(MUESTRA_RECORRIDA.rojos.at(-1)?.camino).toEqual([
      `${muestra}/raiz.ts`,
      `${muestra}/alcanzado.ts`,
      `${muestra}/por-reexportacion.ts`,
    ]);
  });

  it('lo que solo se importa con `import type` no se recorre', () => {
    expect([...MUESTRA_RECORRIDA.alcanzados]).not.toContain(`${muestra}/solo-por-tipo.ts`);
    // Tres referencias: (a), (b) y (d). La (e) no es una referencia sino un tipo, y la (c) es
    // relativa. Las (2) y (3) llevan `type` en cada especificador y no cuentan: cargan.
    expect(MUESTRA_RECORRIDA.soloDeTipos, 'las formas buenas de `raiz.ts`').toBe(3);
  });

  it('pero con `type` en cada especificador SI: `verbatimModuleSyntax` lo deja cargando', () => {
    expect(
      MUESTRA_RECORRIDA.rojos.find((rojo) => rojo.que.startsWith(`${muestra}/por-tipos-entre`))
        ?.camino,
    ).toEqual([`${muestra}/raiz.ts`, `${muestra}/por-tipos-entre-llaves.ts`]);
  });

  it('una ruta absoluta y una URL `file:` se resuelven como un relativo, y se ve si salen', () => {
    // Las (11) y (12) de la muestra no existen en ningun puesto —una ruta del puesto no se puede
    // escribir alli—, asi que ahi solo se ve que no se saltan. Que se RESUELVEN, y que lo resuelto
    // es lo que se compara con `frontend/`, se mide aqui con el archivo de fuera de la (10).
    const desde = join(MUESTRA, 'raiz.ts');
    expect(resolverRuta(desde, FUERA)).toBe(FUERA);
    expect(resolverRuta(desde, pathToFileURL(FUERA).href)).toBe(FUERA);
  });
});
