// @vitest-environment node
//
// Compila `e2e/` con el verificador de tipos de TypeScript y pregunta el tipo de cada cuerpo que el
// arnes sirve. No hay DOM que necesitar.

import { readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import ts from 'typescript';
import { beforeAll, describe, expect, it } from 'vitest';

import { RAIZ } from './artboards.ts';

/**
 * **Todo cuerpo que el arnes sirve lleva el tipo de su operacion** (#314).
 *
 * <h2>De que defecto viene</h2>
 *
 * `e2e/la-insignia-no-pinta-verde-lo-parado.spec.ts` servia una corrida escrita como objeto
 * literal. #271 anadio dos campos a `CorridaDelPredial`, el fixture no los tenia, el conector
 * llamo a `formatearEntero(undefined)` y el armazon **no llego a montarse**. El rojo salio como
 * `TimeoutError … waiting for locator('[data-slot="barra-global"]')`, sin nombrar ni el archivo ni
 * el campo — y solo lo vio quien se acordo de correr `yarn e2e` (#313).
 *
 * #312 metio `e2e/` en `tsc` y tipo ESE fixture. Pero `tsc` solo compara lo que lleva tipo: un
 * objeto literal sin anotacion compila con los campos que tenga, y el siguiente fixture que entre
 * asi repite el defecto con `yarn verificar` en verde. Eso es lo que se vigila aqui.
 *
 * <h2>La regla, entera</h2>
 *
 * Todo valor que llega a un `JSON.stringify` o a la opcion `json` de `fulfill` dentro de `e2e/`
 * tiene que tener un tipo **declarado en `src/datos/`** —`lecturas.ts`, o una captura como
 * `seguridadMedida.ts`, que ya lleva el suyo—, directamente o como elemento de una lista. Y no
 * vale llegar ahi con un `as`: una asercion solo exige que los tipos SE SOLAPEN, asi que
 * `{ id: 1 } as CorridaDelPredial` compila con un campo de trece. El cuerpo escrito como texto
 * solo puede ser `{}`, el vacio que acompana a un 404: cualquier otro JSON en una cadena no lo lee
 * ningun compilador.
 *
 * <h2>Por que el verificador de tipos y no una expresion regular</h2>
 *
 * Porque lo que se pregunta es un TIPO, y el texto no lo tiene: `JSON.stringify(CORRIDA)` es
 * correcto o no segun como se declaro `CORRIDA`, que puede estar cuarenta lineas mas arriba o en
 * otro archivo. Y el caso que mas se parece a uno bueno —un envoltorio `(cuerpo: unknown) =>
 * JSON.stringify(cuerpo)`, que es como estaba `e2e/instalacion.ts`— solo se distingue preguntando:
 * el valor tenia tipo, y el cuerpo lo perdio en la frontera del `unknown`.
 *
 * <h2>Lo que NO cubre, dicho</h2>
 *
 * Un cuerpo leido de un archivo, o construido por concatenacion. No hay ninguno, y si aparece no
 * se ve aqui. Tampoco dice que el fixture sea VERDAD —que la cifra sea la medida—: dice que tiene
 * la forma que el conector va a leer, que es lo que se rompio.
 */

const E2E = join(RAIZ, 'e2e');
const DATOS = join(RAIZ, 'src/datos') + sep;
const MUESTRA = join(RAIZ, 'verificaciones/muestra-del-arnes/fixtures-sin-tipo.ts');

/** Un cuerpo que el arnes sirve, con el tipo que el compilador le da. */
interface Servido {
  readonly donde: string;
  readonly tipo: string;
}

interface Revision {
  readonly servidos: readonly Servido[];
  readonly sinTipo: readonly string[];
}

function archivosDe(desde: string): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    if (statSync(ruta).isDirectory()) return archivosDe(ruta);
    return /\.tsx?$/.test(entrada) ? [ruta] : [];
  });
}

/** Las opciones de `tsconfig.json`, para que el programa resuelva igual que `yarn typecheck`. */
function opciones(): ts.CompilerOptions {
  const leido = ts.getParsedCommandLineOfConfigFile(join(RAIZ, 'tsconfig.json'), undefined, {
    ...ts.sys,
    onUnRecoverableConfigFileDiagnostic: (d) => {
      throw new Error(ts.flattenDiagnosticMessageText(d.messageText, '\n'));
    },
  });
  if (leido === undefined) throw new Error('No se pudo leer frontend/tsconfig.json');
  return leido.options;
}

function revisar(archivos: readonly string[]): Revision {
  const programa = ts.createProgram(archivos, opciones());
  const verificador = programa.getTypeChecker();
  const servidos: Servido[] = [];
  const sinTipo: string[] = [];

  /** El nombre del tipo si se declara en `src/datos/`, mirando dentro de las listas. */
  const declaradoEnDatos = (tipo: ts.Type): boolean => {
    if (verificador.isArrayType(tipo)) {
      const [elemento] = verificador.getTypeArguments(tipo as ts.TypeReference);
      return elemento !== undefined && declaradoEnDatos(elemento);
    }
    const simbolo = tipo.aliasSymbol ?? tipo.getSymbol();
    return (simbolo?.declarations ?? []).some((d) =>
      d.getSourceFile().fileName.split('/').join(sep).startsWith(DATOS),
    );
  };

  for (const archivo of archivos) {
    const fuente = programa.getSourceFile(archivo);
    if (fuente === undefined) throw new Error(`El programa no cargo ${archivo}`);
    const donde = (nodo: ts.Node) => {
      const { line } = fuente.getLineAndCharacterOfPosition(nodo.getStart(fuente));
      return `${relative(RAIZ, archivo).split(sep).join('/')}:${line + 1}`;
    };

    const juzgar = (valor: ts.Expression) => {
      let expresion = valor;
      while (ts.isParenthesizedExpression(expresion)) expresion = expresion.expression;
      const texto = expresion.getText(fuente).replace(/\s+/g, ' ').slice(0, 60);
      if (ts.isAsExpression(expresion) || ts.isTypeAssertionExpression(expresion)) {
        sinTipo.push(`${donde(valor)} — «${texto}»: un \`as\` no compara, solo exige que se solapen`);
        return;
      }
      const tipo = verificador.getTypeAtLocation(expresion);
      const nombre = verificador.typeToString(tipo);
      if (declaradoEnDatos(tipo)) servidos.push({ donde: donde(valor), tipo: nombre });
      else sinTipo.push(`${donde(valor)} — «${texto}» es \`${nombre}\`, que no es una forma de src/datos/`);
    };

    const visitar = (nodo: ts.Node): void => {
      if (ts.isCallExpression(nodo)) {
        const llamada = nodo.expression.getText(fuente);
        const [primero] = nodo.arguments;
        if (llamada === 'JSON.stringify' && primero !== undefined) juzgar(primero);
        if (
          ts.isPropertyAccessExpression(nodo.expression) &&
          nodo.expression.name.text === 'fulfill' &&
          primero !== undefined &&
          ts.isObjectLiteralExpression(primero)
        ) {
          for (const propiedad of primero.properties) {
            if (!ts.isPropertyAssignment(propiedad)) continue;
            const clave = propiedad.name.getText(fuente);
            const valor = propiedad.initializer;
            if (clave === 'json') juzgar(valor);
            if (
              clave === 'body' &&
              ts.isStringLiteralLike(valor) &&
              /^\s*[[{]/.test(valor.text) &&
              valor.text.trim() !== '{}'
            ) {
              sinTipo.push(`${donde(valor)} — un JSON escrito como texto no lo lee ningun compilador`);
            }
          }
        }
      }
      ts.forEachChild(nodo, visitar);
    };
    visitar(fuente);
  }
  return { servidos, sinTipo };
}

describe('los fixtures del arnes llevan el tipo de su operacion', () => {
  let arnes: Revision;
  beforeAll(() => {
    arnes = revisar(archivosDe(E2E));
  }, 60_000);

  it('el barrido ve los cuerpos que el arnes sirve (centinela)', () => {
    // Primero que haya algo: una guarda que no encuentra ningun `JSON.stringify` pasa en verde
    // sobre un arnes que no ha mirado. Los siete de hoy, por su tipo; uno nuevo no rompe esto.
    expect(arnes.servidos.map((s) => s.tipo)).toEqual(
      expect.arrayContaining([
        'Paginado<ModuloDelSistema>',
        'Paginado<AccesoDelSistema>',
        'PermisosDeLaSesion',
        'TrabajoParado',
        'CorridaDelPredial',
        'Paginado<ProgramaDeFiscalizacion>',
        'Paginado<FilaDeLaMuestra>',
      ]),
    );
  });

  it('ningun cuerpo servido por `e2e/` se queda sin tipo', () => {
    expect(
      arnes.sinTipo,
      'Un fixture del arnes sin el tipo de su operacion compila con los campos que tenga: si la\n' +
        'operacion crece, el conector lee `undefined` y el rojo sale como un tiempo agotado que no\n' +
        'nombra ni el archivo ni el campo (#313). Declaralo con su tipo de `src/datos/lecturas.ts`:\n' +
        '    const CORRIDA: CorridaDelPredial = { … };\n',
    ).toEqual([]);
  });

  it('la muestra que la viola sale roja, con sus cinco formas', () => {
    const { sinTipo } = revisar([MUESTRA]);
    expect(sinTipo.map((uno) => uno.replace(/ — .*/, ''))).toEqual([
      'verificaciones/muestra-del-arnes/fixtures-sin-tipo.ts:23',
      'verificaciones/muestra-del-arnes/fixtures-sin-tipo.ts:31',
      'verificaciones/muestra-del-arnes/fixtures-sin-tipo.ts:37',
      'verificaciones/muestra-del-arnes/fixtures-sin-tipo.ts:42',
      'verificaciones/muestra-del-arnes/fixtures-sin-tipo.ts:48',
    ]);
  }, 60_000);
});
