// @vitest-environment node
//
// Lee el arbol del disco y lo parsea. No hay DOM que necesitar: lo que se vigila es que el CODIGO no
// vuelva a escribir la entidad o la cuenta de la barra, y eso se ve en el texto, no en la pantalla.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { RAIZ } from './artboards.ts';

/**
 * **La entidad y la cuenta de la barra no se escriben a mano** (#356).
 *
 * <h2>De que defecto viene</h2>
 *
 * De esto, en `aplicacion.tsx` desde `623a968` (#90) hasta #356:
 *
 *     const ENTIDAD = 'Municipalidad Distrital de Catacaos';
 *     …
 *     entidad={t(ENTIDAD)}
 *     cuenta={{ nombre: 'J. Cardenas Vega', iniciales: 'JC', nota: t(ENTIDAD) }}
 *
 * Cualquier cuenta de cualquier municipalidad veia a Catacaos y a una persona que no existe en las
 * cuarenta pantallas. Y nada lo paraba: compila, pasa el lint, y la guarda del i18n **consagraba**
 * los dos nombres en su `NO_ES_TEXTO`. `camino-a-la-api` solo impide importar `sesionMedida.ts`,
 * no escribir el literal.
 *
 * <h2>Que se rechaza</h2>
 *
 * **Cualquier literal de cadena** dentro de lo que se le pasa al `<Armazon>` como `entidad`, o como
 * `nombre` o `iniciales` de su `cuenta` — tambien dentro de un `t('…')`, y tambien a traves de una
 * constante del mismo archivo, que es exactamente como estaba escrito. Lo que tiene que llegar ahi
 * es lo que devuelve `useCabeceraDeLaSesion()`, que no tiene ninguno.
 *
 * Es la misma forma que la de `al: () => {}` de #115 (`ninguna-opcion-del-menu-se-queda-muda`): el
 * compilador de TypeScript, no una expresion regular, porque `cuenta={{ nombre: … }}` se puede
 * escribir de muchas maneras y un patron de texto se queda con una.
 *
 * <h2>Y el centinela va PRIMERO</h2>
 *
 * El modo de fallo de una guarda asi no es equivocarse: es **no encontrar nada** y pasar en verde.
 * Si el montaje del armazon se mueve de archivo, el barrido se queda sin atributos que mirar. Por
 * eso se exige que un archivo de produccion —uno, y se dice cual— monte el `<Armazon>` con los dos
 * atributos, y que la regla, aplicada al texto de antes de #356, **lo encuentre**.
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

function recorrer(nodo: ts.Node, visita: (n: ts.Node) => void): void {
  visita(nodo);
  nodo.forEachChild((hijo) => {
    recorrer(hijo, visita);
  });
}

/** Lo que dice la barra, leido del `<Armazon>`: cada atributo con los literales que lleva dentro. */
interface CabeceraLeida {
  /** Cuantos `<Armazon>` hay en el archivo. */
  readonly armazones: number;
  /** Los atributos que se encontraron, para el centinela. */
  readonly atributos: readonly string[];
  /** `entidad`, `cuenta.nombre` o `cuenta.iniciales`, con el literal que lleva. */
  readonly literales: readonly string[];
}

/**
 * **Los literales de la cabecera de un archivo**, dado su TEXTO.
 *
 * Recibe el texto y no la ruta para poder aplicarse tambien a la muestra de antes de #356: una regla
 * que nunca se ve fallar no protege nada.
 */
function leerLaCabecera(fuente: string): CabeceraLeida {
  const arbol = ts.createSourceFile('costura.tsx', fuente, ts.ScriptTarget.ESNext, true, ts.ScriptKind.TSX);

  // Las constantes del archivo, por nombre: `entidad={t(ENTIDAD)}` era un literal con un rodeo.
  const constantes = new Map<string, ts.Expression>();
  recorrer(arbol, (nodo) => {
    if (ts.isVariableDeclaration(nodo) && ts.isIdentifier(nodo.name) && nodo.initializer) {
      constantes.set(nodo.name.text, nodo.initializer);
    }
  });

  /** Los literales de cadena de una expresion, entrando por las constantes del archivo. */
  function literalesDe(expresion: ts.Node, vistas: ReadonlySet<string> = new Set()): string[] {
    if (ts.isStringLiteral(expresion) || ts.isNoSubstitutionTemplateLiteral(expresion)) {
      return [expresion.text];
    }
    if (ts.isTemplateExpression(expresion)) {
      const fijo = [expresion.head.text, ...expresion.templateSpans.map((s) => s.literal.text)]
        .join('')
        .trim();
      return [...(fijo === '' ? [] : [fijo]), ...expresion.templateSpans.flatMap((s) => literalesDe(s.expression, vistas))];
    }
    if (ts.isIdentifier(expresion)) {
      const valor = constantes.get(expresion.text);
      if (valor === undefined || vistas.has(expresion.text)) return [];
      return literalesDe(valor, new Set([...vistas, expresion.text]));
    }
    // En `cabecera.entidad` el nombre de la propiedad es un identificador, y no una constante: solo
    // se entra por el objeto.
    if (ts.isPropertyAccessExpression(expresion)) return literalesDe(expresion.expression, vistas);
    const dentro: string[] = [];
    expresion.forEachChild((hijo) => {
      dentro.push(...literalesDe(hijo, vistas));
    });
    return dentro;
  }

  /** El objeto que se le pasa como `cuenta`, si es un objeto escrito; entrando por constantes. */
  function objetoDe(expresion: ts.Expression): ts.ObjectLiteralExpression | null {
    if (ts.isObjectLiteralExpression(expresion)) return expresion;
    if (ts.isParenthesizedExpression(expresion)) return objetoDe(expresion.expression);
    if (ts.isIdentifier(expresion)) {
      const valor = constantes.get(expresion.text);
      return valor === undefined ? null : objetoDe(valor);
    }
    return null;
  }

  let armazones = 0;
  const atributos: string[] = [];
  const literales: string[] = [];

  recorrer(arbol, (nodo) => {
    if (!ts.isJsxSelfClosingElement(nodo) && !ts.isJsxOpeningElement(nodo)) return;
    if (nodo.tagName.getText() !== 'Armazon') return;
    armazones += 1;

    for (const atributo of nodo.attributes.properties) {
      if (!ts.isJsxAttribute(atributo)) continue;
      const nombre = atributo.name.getText();
      const valor = atributo.initializer;
      if (valor === undefined) continue;
      const expresion = ts.isJsxExpression(valor) ? valor.expression : valor;
      if (expresion === undefined) continue;

      if (nombre === 'entidad') {
        atributos.push('entidad');
        literales.push(...literalesDe(expresion).map((l) => `entidad: «${l}»`));
      }
      if (nombre === 'cuenta') {
        atributos.push('cuenta');
        const objeto = objetoDe(expresion);
        for (const propiedad of objeto?.properties ?? []) {
          if (!ts.isPropertyAssignment(propiedad)) continue;
          const clave = propiedad.name.getText();
          if (clave !== 'nombre' && clave !== 'iniciales') continue;
          literales.push(...literalesDe(propiedad.initializer).map((l) => `cuenta.${clave}: «${l}»`));
        }
      }
    }
  });

  return { armazones, atributos, literales };
}

/**
 * **La costura tal como estaba antes de #356**, recortada a lo que esta guarda mira. Es la muestra
 * que la regla tiene que rechazar: si deja de encontrar los dos nombres aqui, la regla dejo de
 * morder, y lo que diga del archivo de verdad no vale nada.
 */
const COSTURA_DE_ANTES = `
const ENTIDAD = 'Municipalidad Distrital de Catacaos';
function ArmazonDelSistema() {
  return (
    <Armazon
      titulo={t('Rentas')}
      entidad={t(ENTIDAD)}
      escudo={<img src={escudo} alt="" width={28} height={28} />}
      cuenta={{ nombre: 'J. Cardenas Vega', iniciales: 'JC', nota: t(ENTIDAD) }}
    />
  );
}
`;

/**
 * Quien monta el armazon. Se busca por el ELEMENTO en el arbol de sintaxis, no por el texto: dos
 * javadoc de `src/` nombran `<Armazon>` en prosa, y un patron sobre el texto los contaba como
 * montajes.
 */
const MONTAN_EL_ARMAZON = fuentesDeProduccion().filter(
  (ruta) => leerLaCabecera(readFileSync(join(RAIZ, ruta), 'utf8')).armazones > 0,
);

describe('#356 — la entidad y la cuenta de la barra no se escriben a mano', () => {
  it('EL CENTINELA: un archivo de produccion monta el `<Armazon>`, y con los dos atributos', () => {
    expect(
      MONTAN_EL_ARMAZON,
      'nadie monta `<Armazon>` en `src/`: el barrido no tiene cabecera que mirar',
    ).toEqual(['src/aplicacion.tsx']);

    const leida = leerLaCabecera(readFileSync(join(RAIZ, 'src/aplicacion.tsx'), 'utf8'));
    expect(leida.armazones).toBe(1);
    expect(
      [...leida.atributos].sort(),
      'no se pudieron leer `entidad` y `cuenta` del `<Armazon>`: el barrido miraria nada',
    ).toEqual(['cuenta', 'entidad']);
  });

  it('EL CENTINELA de la regla: la costura de antes de #356 SI sale, con sus tres literales', () => {
    // Sin esto, una regla que no encontrara nunca nada —un `getText()` que cambia, una rama que no
    // se recorre— dejaria la prueba de abajo en verde para siempre.
    expect(leerLaCabecera(COSTURA_DE_ANTES).literales).toEqual([
      'entidad: «Municipalidad Distrital de Catacaos»',
      'cuenta.nombre: «J. Cardenas Vega»',
      'cuenta.iniciales: «JC»',
    ]);
  });

  it('y `aplicacion.tsx` no le pasa al armazon ni una entidad ni una cuenta escritas', () => {
    const { literales } = leerLaCabecera(readFileSync(join(RAIZ, 'src/aplicacion.tsx'), 'utf8'));

    expect(
      literales,
      'La barra lleva texto escrito a mano donde tiene que decir de quien son las cifras:\n' +
        `${literales.map((l) => `  ${l}`).join('\n')}\n\n` +
        '  La entidad y la cuenta son de la SESION: las da `useCabeceraDeLaSesion()` desde\n' +
        '  `GET /seguridad/sesion` y `/seguridad/sesion/municipalidad`. Un nombre escrito aqui se\n' +
        '  lo ensena a TODAS las cuentas de TODAS las municipalidades. Ver #356.',
    ).toEqual([]);
  });

  it('y ningun archivo de produccion importa el escudo de Catacaos', () => {
    // Ninguna operacion publica el escudo de una municipalidad, asi que la barra lleva el neutro de
    // la libreria. El de Catacaos es del artboard y se queda en `diseno/`, fuera de la interfaz.
    // Se busca el IMPORT y no la mencion: `aplicacion.tsx` lo nombra en su javadoc para decir por
    // que no lo usa, y una guarda que no distingue las dos acaba prohibiendo explicarlo.
    const loImportan = fuentesDeProduccion().filter((ruta) =>
      /(?:from|import\()\s*'[^']*escudo-catacaos[^']*'/.test(readFileSync(join(RAIZ, ruta), 'utf8')),
    );
    expect(loImportan).toEqual([]);
  });
});
