import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * El andamiaje no se afloja solo.
 *
 * Las tres cosas que este archivo vigila no son gustos: son las que hacen que TODO lo
 * demas verifique algo.
 *
 *   · Sin `strict` y sus dos companeras, el compilador deja pasar el `undefined` que
 *     luego se muestra al ciudadano como «NaN».
 *   · Si `verificar` deja de encadenar una de las tres, el PR sigue saliendo verde y ya no
 *     dice lo mismo. Es un cambio de una linea y nadie lo revisa dos veces.
 *   · Si el workflow pierde su filtro o su comando, la CI del frontend deja de existir sin
 *     que ningun archivo se borre.
 *
 * Las tres se caen en silencio, que es el motivo por el que se comprueban.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const RAIZ = join(AQUI, '..');
const REPOSITORIO = join(RAIZ, '..');

const leer = (ruta: string) => readFileSync(ruta, 'utf8');

describe('el compilador es tan estricto como el issue pide', () => {
  const opciones = JSON.parse(leer(join(RAIZ, 'tsconfig.base.json'))).compilerOptions as Record<
    string,
    unknown
  >;

  it.each([
    ['strict', 'sin el, el resto de banderas no significan nada'],
    [
      'noUncheckedIndexedAccess',
      'sin el, `cuotas[0].total` compila y revienta con la lista vacia',
    ],
    ['verbatimModuleSyntax', 'sin el, un `import type` se cuela en el bundle y arrastra el modulo'],
  ])('%s esta encendido — %s', (bandera) => {
    expect(opciones[bandera]).toBe(true);
  });
});

describe('«yarn verificar» encadena las tres comprobaciones', () => {
  const scripts = JSON.parse(leer(join(RAIZ, 'package.json'))).scripts as Record<string, string>;

  it.each(['lint', 'typecheck', 'test'])('llama a «yarn %s»', (comprobacion) => {
    expect(
      scripts['verificar'],
      `«verificar» dejo de llamar a «${comprobacion}». Una cadena a la que le falta un\n` +
        'eslabon sigue saliendo verde, y eso es peor que no tenerla.',
    ).toContain(`yarn ${comprobacion}`);
  });

  it('«build» produce un bundle de verdad, no un alias del typecheck', () => {
    expect(scripts['build']).toBe('vite build');
  });
});

/**
 * Donde vive el workflow del frontend.
 *
 * Hasta F-1 vivia en `frontend/ci/frontend.yml` y no aqui, porque el token de aquella sesion
 * no tenia el alcance `workflow` y GitHub rechazaba la rama entera. Eso se cerro: el
 * `git mv` esta hecho y el archivo esta instalado, asi que la prueba mira UN sitio y no dos.
 *
 * Lo que verifica sigue siendo el CONTENIDO —el filtro, la orden y el candado—, y que el
 * archivo EXISTA: si alguien lo borra, sale rojo aqui antes de que nadie note que los PR
 * del frontend dejaron de verificarse.
 */
const SITIO_DEL_WORKFLOW = '.github/workflows/frontend.yml';

describe('el frontend tiene su propia CI', () => {
  const ruta = join(REPOSITORIO, SITIO_DEL_WORKFLOW);
  const encontrado = existsSync(ruta) ? ruta : undefined;

  it('el workflow esta instalado', () => {
    expect(
      encontrado,
      `No hay workflow del frontend en «${SITIO_DEL_WORKFLOW}».\n` +
        'Sin el, «yarn verificar» solo se ejecuta en la maquina de quien lo escribe.',
    ).toBeDefined();
  });

  // Sin el archivo, `workflow` es la cadena vacia y NO una excepcion. Leerlo a secas
  // reventaba el modulo entero con un `ENOENT` durante la recoleccion: los doce casos de
  // este archivo desaparecian y el rojo hablaba de `readFileSync`, no de la CI que falta.
  const workflow = encontrado === undefined ? '' : leer(encontrado);

  it('se dispara solo con lo suyo', () => {
    // El filtro `paths` es lo contrario del criterio de `publicar-imagenes.yml`, y a
    // proposito: alli todo commit de `main` tiene que tener sus imagenes, asi que no hay
    // filtro. Aqui no se despliega nada, y un cambio del backend no tiene por que gastar
    // una instalacion de npm.
    expect(workflow).toMatch(/paths:\s*\[?"?frontend\/\*\*/);
  });

  it('el workflow es tambien lo suyo: un cambio en el se verifica a si mismo', () => {
    expect(workflow).toContain('.github/workflows/frontend.yml');
  });

  it('ejecuta la misma orden que se ejecuta en local', () => {
    // Si la CI corriera `yarn lint && yarn test` por su cuenta, «verde en CI» y «verde en
    // mi maquina» dejarian de ser la misma afirmacion en cuanto una de las dos cambie.
    expect(workflow).toContain('yarn verificar');
  });

  it('instala con el candado, no con lo que haya hoy en el registro', () => {
    expect(workflow).toContain('--frozen-lockfile');
  });
});
