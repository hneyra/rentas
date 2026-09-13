// @vitest-environment node
//
// Lee el arbol del disco y lo parsea. No hay DOM que necesitar, y montar la aplicacion no serviria:
// lo que se vigila es que el CODIGO no vuelva a tener un `al: () => {}`, y una funcion vacia
// montada se comporta **exactamente igual** que una que hace lo suyo mal — no hay nada que mirar.

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative, sep } from 'node:path';

import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { RAIZ } from './artboards.ts';

/**
 * **Ningun boton del armazon se queda mudo** (#115, AC3).
 *
 * <h2>De que defecto viene</h2>
 *
 * De tres opciones del menu de sesion escritas asi:
 *
 *     { rotulo: t('Mi perfil'), al: () => {} },
 *     { rotulo: t('Cambiar la contrasena'), al: () => {} },
 *     { rotulo: t('Preferencias'), al: () => {} },
 *
 * Se pulsaban y **no pasaba nada**. Ni una peticion, ni un aviso, ni un error en la consola: el
 * menu se cerraba y la pantalla seguia igual. Es el peor sintoma que una interfaz puede dar,
 * porque no se distingue de una averia — y este repositorio ya tiene escrito el criterio contrario
 * en `aplicacion.tsx`: «un boton que no dice nada al pulsarlo se lee como una pantalla rota; uno
 * que dice lo que hace —y lo que no— se lee como una pantalla a medio conectar, que es lo que es».
 *
 * <h2>Por que una guarda y no «ya esta arreglado»</h2>
 *
 * Porque `al: () => {}` **compila, pasa el lint y pasa las 557 pruebas**. Es la forma mas comoda
 * de satisfacer al compilador cuando se anade una opcion nueva al menu, y nada de lo que ya existe
 * en este arbol la ve. La reincidencia no cuesta trabajo: cuesta una linea.
 *
 * <h2>Y por que el centinela va PRIMERO</h2>
 *
 * Porque el modo de fallo de una guarda como esta no es que se equivoque: es que **no encuentre
 * nada** y pase en verde. Si alguien mueve el montaje del armazon a otro archivo, o `opcionesDeSesion`
 * pasa a construirse en una funcion aparte, el barrido se queda sin lista que mirar y esta prueba
 * felicita a un menu que no ha visto. Asi que lo primero que se comprueba es que haya algo: que un
 * archivo de produccion —uno, y se dice cual— monte el menu, y que del menu salgan las cuatro
 * opciones de V8 con sus cuatro nombres.
 */

/** Las cuatro de V8, en el orden del artboard. El menu no puede quedarse corto sin decirlo. */
const LAS_CUATRO_DE_V8 = [
  'Mi perfil',
  'Cambiar la contrasena',
  'Preferencias',
  'Cerrar sesion',
] as const;

/** Todos los `.ts`/`.tsx` de produccion bajo `src/`, con su ruta relativa a `frontend/`. */
function fuentesDeProduccion(desde = join(RAIZ, 'src')): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    if (statSync(ruta).isDirectory()) return fuentesDeProduccion(ruta);
    if (!/\.tsx?$/.test(entrada) || /\.test\.tsx?$/.test(entrada)) return [];
    return [relative(RAIZ, ruta).split(sep).join('/')];
  });
}

/** Quien monta el menu de sesion. Se busca por el nombre del atributo, no por la ruta. */
const MONTAN_EL_MENU = fuentesDeProduccion().filter((ruta) =>
  /\bopcionesDeSesion\b/.test(readFileSync(join(RAIZ, ruta), 'utf8')),
);

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

/**
 * Una funcion que no hace nada: `() => {}`, `function () {}` y sus parientes con cuerpo vacio.
 *
 * `() => undefined` y `() => null` **no** cuentan, y es a proposito: tampoco hacen nada, pero
 * nadie las escribe por descuido. Lo que se persigue es la forma que el compilador invita a
 * escribir, que es la del cuerpo vacio.
 */
function noHaceNada(nodo: ts.Node): boolean {
  if (!ts.isArrowFunction(nodo) && !ts.isFunctionExpression(nodo)) return false;
  return ts.isBlock(nodo.body) && nodo.body.statements.length === 0;
}

interface OpcionLeida {
  readonly rotulo: string;
  /** El `al` tal como esta escrito, o `undefined` si la opcion no declara ninguno. */
  readonly al: ts.Expression | undefined;
}

/** Las opciones del `opcionesDeSesion={[…]}` de un archivo, leidas del arbol de sintaxis. */
function opcionesDelMenu(ruta: string): readonly OpcionLeida[] {
  const arbol = arbolDe(ruta);
  const leidas: OpcionLeida[] = [];

  recorrer(arbol, (nodo) => {
    if (!ts.isJsxAttribute(nodo) || nodo.name.getText() !== 'opcionesDeSesion') return;
    const valor = nodo.initializer;
    if (valor === undefined || !ts.isJsxExpression(valor)) return;
    const lista = valor.expression;
    if (lista === undefined || !ts.isArrayLiteralExpression(lista)) return;

    for (const elemento of lista.elements) {
      if (!ts.isObjectLiteralExpression(elemento)) continue;
      const propiedad = (nombre: string) =>
        elemento.properties.find(
          (p): p is ts.PropertyAssignment =>
            ts.isPropertyAssignment(p) && p.name.getText() === nombre,
        );
      // El rotulo viene como `t('Mi perfil')`: lo que interesa es la cadena de dentro, que es la
      // clave del castellano y lo que el artboard escribe.
      const rotulo = propiedad('rotulo')?.initializer.getText() ?? '';
      const entrecomillado = /'([^']*)'/.exec(rotulo)?.[1];
      leidas.push({ rotulo: entrecomillado ?? rotulo, al: propiedad('al')?.initializer });
    }
  });

  return leidas;
}

describe('#115 — ninguna opcion del menu de sesion se queda muda', () => {
  it('EL CENTINELA: un archivo de produccion monta el menu, y del menu salen las cuatro de V8', () => {
    // Sin esto, mover el montaje o construir la lista en otra funcion dejaria a las dos pruebas de
    // abajo barriendo una lista vacia — que es como se pasa en verde sin haber mirado nada.
    expect(
      MONTAN_EL_MENU,
      'nadie monta `opcionesDeSesion` en `src/`: el barrido no tiene menu que mirar',
    ).toEqual(['src/aplicacion.tsx']);

    const rotulos = opcionesDelMenu('src/aplicacion.tsx').map((o) => o.rotulo);
    expect(
      rotulos,
      'las opciones del menu no se pudieron leer del arbol de sintaxis, o ya no son las de V8',
    ).toEqual([...LAS_CUATRO_DE_V8]);
  });

  it('y cada una declara su `al`: una opcion sin accion no se puede pulsar', () => {
    const sinAccion = opcionesDelMenu('src/aplicacion.tsx')
      .filter((o) => o.al === undefined)
      .map((o) => o.rotulo);

    expect(sinAccion, `Opciones del menu sin «al»: ${sinAccion.join(', ')}`).toEqual([]);
  });

  it('ninguna de las cuatro es un `() => {}`', () => {
    const mudas = opcionesDelMenu('src/aplicacion.tsx')
      .filter((o) => o.al !== undefined && noHaceNada(o.al))
      .map((o) => `  «${o.rotulo}»`);

    expect(
      mudas,
      'Estas opciones del menu de sesion no hacen nada al pulsarlas:\n' +
        `${mudas.join('\n')}\n\n` +
        '  Un boton que no dice nada al pulsarlo se lee como una pantalla ROTA. O hace lo suyo, o\n' +
        '  dice donde se hace —como «Mi perfil» y «Cambiar la contrasena», que llevan a la consola\n' +
        '  de cuenta del emisor—, pero no se queda callado. Ver `aplicacion.tsx` y #115.',
    ).toEqual([]);
  });

  it('y la salida elegida es honesta: el contrato NO publica perfil ni contrasena', () => {
    // La otra mitad de la decision del AC2, y la que impide que alguien «complete» esto con un
    // formulario: el contrato de este backend no publica ni una operacion de clave o de perfil.
    // Dibujarla aqui seria prometer una escritura que ningun backend de este repositorio puede
    // atender — y por eso las dos opciones llevan al emisor en vez de a una pantalla propia.
    // Esta prueba caduca sola el dia que alguna operacion lo publique: entonces sale roja.
    const formas = JSON.parse(
      readFileSync(join(RAIZ, '../docs/50-api/formas-de-la-api.json'), 'utf8'),
    ) as Record<string, unknown>;
    const candidatas = Object.keys(formas).filter((clave) =>
      /contrasena|password|credencial|mi-perfil/i.test(clave),
    );

    expect(candidatas).toEqual([]);
  });

  it('y TAMPOCO lo es ninguna otra funcion de la costura: ni una accion, ni un cierre', () => {
    // La red que recoge lo que las tres de arriba no miran: `acciones`, `alCerrar`, `alVerAvisos`
    // y cualquier otra devolucion de llamada que se le pase al armazon. El defecto es el mismo
    // —algo que se pulsa y no pasa nada—, y la forma en el codigo tambien.
    const arbol = arbolDe('src/aplicacion.tsx');
    const vacias: string[] = [];
    recorrer(arbol, (nodo) => {
      if (!noHaceNada(nodo)) return;
      const { line } = arbol.getLineAndCharacterOfPosition(nodo.getStart());
      vacias.push(`  src/aplicacion.tsx:${String(line + 1)} — ${nodo.getText()}`);
    });

    expect(
      vacias,
      'Hay funciones vacias en la costura del armazon:\n' +
        `${vacias.join('\n')}\n\n` +
        '  Lo que se le pasa al armazon se PULSA. Una devolucion de llamada vacia es un control\n' +
        '  que no responde, y eso no se ve como «todavia no»: se ve como una averia.',
    ).toEqual([]);
  });
});
