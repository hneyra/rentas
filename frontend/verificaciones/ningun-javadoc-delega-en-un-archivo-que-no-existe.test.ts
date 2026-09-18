// @vitest-environment node
//
// Lee el DISCO y nada mas: los fuentes de `src/` y la lista de archivos que hay en `frontend/`.
// No importa ni un modulo del arbol, porque lo que mide es lo que los comentarios DICEN y no lo
// que el codigo hace.

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * **Ningun javadoc delega su justificacion en un archivo que no existe** (#255).
 *
 * <h2>De que defecto viene, medido</h2>
 *
 * Tres sitios de `src/` citaban `secciones/determinacion.ts` como **donde esta la medida o el
 * razonamiento** de lo que afirmaban, y ese archivo salio del arbol con la V6 en #90. El mas caro
 * era el de `DeterminacionDeAlcabala`: decia que la regla 9 impide dibujar sus dos importes
 * «medido y razonado» en un archivo que no se puede abrir, de modo que quien llegara a conectar la
 * hoja se encontraba una prohibicion que **no podia comprobar y tampoco levantar**. Las dos
 * salidas eran malas: creersela sin verla, o rehacer la medida desde cero.
 *
 * Y no rompe nada: compila, las pruebas pasan, y lo que se lee es una afirmacion con su respaldo
 * aparente. Es exactamente lo que `CLAUDE.md` distingue —un comentario que dice de que defecto
 * viene una guarda **es el motivo por el que el codigo de al lado es como es**; uno que manda a un
 * archivo que no existe deja el motivo sin sujeto—.
 *
 * <h2>Por que DELEGACION y no «toda ruta citada»</h2>
 *
 * Porque la regla ancha se mide y **no se sostiene**. Barriendo todas las citas a un `*.ts(x)` en
 * los comentarios de `src/` salen **235**; exigiendo que todas resuelvan, **22** dan rojo y solo
 * **4** son el defecto. Las otras 18 son prosa deliberada que nombra lo que YA NO esta —«Hubo un
 * segundo arbol —`src/marco/arbol.ts`— y ya no esta», «Vivia en `api/proxy.ts`, que salio con la
 * V6 (#90)»—, que es la misma clase de comentario que `CLAUDE.md` protege cuando nombra el
 * monolito. Una guarda con 18 rojos sobre codigo bueno se acaba desactivando.
 *
 * Lo que separa las dos cosas es el **verbo**: «lo comprueba X», «lo vigila X», «medido en X»,
 * «ver X» prometen que la medida esta AHI; «vivia en X», «salio con la V6» cuentan donde estuvo.
 * Medido con esa regla: **64 citas delegadas** y **cero falsos rojos** —ninguna cita a la libreria
 * hermana es una delegacion, asi que esto no necesita el clon de `kamayuk-lib` para correr—.
 *
 * <h2>Lo que esto NO comprueba, y se dice</h2>
 *
 * Que lo que hay en el archivo citado sea de verdad la medida que se promete. Eso no lo lee una
 * maquina: lo lee la revision. Aqui se cierra la mitad que si es mecanica —que el sujeto exista—,
 * que es la que se pierde en silencio cuando un archivo se retira.
 *
 * Y una cita **sin barra** —«`conectores.ts`»— se resuelve por sufijo contra el arbol entero, asi
 * que un nombre suelto casa con cualquier archivo que se llame igual. Es deliberado: exigir la
 * ruta completa en cada javadoc daria rojos sobre prosa correcta.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');

/**
 * **El verbo que convierte una cita en una delegacion.**
 *
 * Cuatro formas, y las cuatro salen de la prosa que ya hay en el arbol:
 *
 *   · «lo comprueba `X`», «lo vigila `X`», «lo prueba `X`», «lo mide `X`»;
 *   · «medido en `X`», «razonado en `X`», «escrito en `X`», «se comprueba en `X`»;
 *   · «Ver `X`»;
 *   · y la vuelta, «`X` comprueba campo a campo», que es como lo escribe `lecturas.ts`.
 */
const DELEGA =
  /(?:lo |la |las |los )?(?:comprueban?|vigilan?|prueban?|miden?|mide)\s+`([^`]+\.tsx?)`|(?:medido|razonado|escrito|se comprueba|se mide|se prueba|se vigila)\s+(?:y \w+\s+)?en\s+`([^`]+\.tsx?)`|\b[Vv]er\s+`([^`]+\.tsx?)`|`([^`]+\.tsx?)`(?:\*\*)?,?\s+(?:lo |que )?(?:comprueba|vigila|prueba|mide)\b/g;

/**
 * Todo lo que hay bajo `frontend/`, menos `node_modules`. Es contra esto que una cita resuelve.
 *
 * Con `readdirSync` recursivo y no con `globSync`, que en Node 22 sigue siendo experimental y
 * escupe un `ExperimentalWarning` por corrida: una guarda que ensucia la salida de `yarn
 * verificar` se acaba leyendo como ruido, y este archivo no necesita patrones para nada.
 */
function archivosDelArbol(desde = '.'): ReadonlySet<string> {
  const salida = new Set<string>();
  for (const entrada of readdirSync(join(FRONTEND, desde), { withFileTypes: true })) {
    if (entrada.name === 'node_modules' || entrada.name.startsWith('.')) continue;
    const relativa = desde === '.' ? entrada.name : `${desde}/${entrada.name}`;
    if (entrada.isDirectory()) {
      for (const dentro of archivosDelArbol(relativa)) salida.add(dentro);
    } else {
      salida.add(relativa);
    }
  }
  return salida;
}

/** Una cita resuelve si algun archivo real termina con ella, en frontera de segmento. */
function resuelve(cita: string, reales: ReadonlySet<string>): boolean {
  const limpia = cita.replace(/^\.\//, '');
  for (const real of reales) if (real === limpia || real.endsWith(`/${limpia}`)) return true;
  return false;
}

interface Delegacion {
  readonly archivo: string;
  readonly linea: number;
  readonly cita: string;
  readonly frase: string;
}

/**
 * Las delegaciones de todos los comentarios de `src/`.
 *
 * El barrido es por **bloque de comentario** y no por linea, porque el verbo y la cita se separan
 * al ajustar el margen: `laVentana.ts` escribe «Lo vigila\n * `verificaciones/…`». Leyendo linea a
 * linea, esa —que era una de las rotas— no se veia.
 */
function delegaciones(): readonly Delegacion[] {
  const salida: Delegacion[] = [];
  for (const rel of [...archivosDelArbol('src')].filter((uno) => /\.tsx?$/.test(uno))) {
    const lineas = readFileSync(join(FRONTEND, rel), 'utf8').split('\n');
    let bloque: string[] | null = null;
    let inicio = 0;
    const cerrar = (): void => {
      if (bloque === null) return;
      const texto = bloque.join(' ').replace(/\s+/g, ' ');
      for (const encontrado of texto.matchAll(DELEGA)) {
        const cita = encontrado[1] ?? encontrado[2] ?? encontrado[3] ?? encontrado[4] ?? '';
        salida.push({ archivo: rel, linea: inicio, cita, frase: encontrado[0] });
      }
      bloque = null;
    };
    lineas.forEach((linea, i) => {
      if (/^\s*(\/\/|\*|\/\*)/.test(linea)) {
        if (bloque === null) {
          bloque = [];
          inicio = i + 1;
        }
        bloque.push(linea.replace(/^\s*(\/\/|\*\/|\/\*\*?|\*)/, ''));
      } else {
        cerrar();
      }
    });
    cerrar();
  }
  return salida;
}

describe('ningun javadoc delega en un archivo que no existe (#255)', () => {
  it('EL CENTINELA: hay delegaciones que comprobar, y el barrido las ve', () => {
    // Sin esto, el dia que el patron deje de casar —un margen distinto, un verbo nuevo— esto
    // pasaria sobre la lista vacia y seguiria en verde sin comprobar nada, que es como una
    // barrera se apaga sin que nadie la borre.
    const todas = delegaciones();
    expect(todas.length).toBeGreaterThanOrEqual(40);
    // Y una concreta que se sabe buena, para que el centinela no se conforme con el numero.
    expect(todas.map((una) => una.cita)).toContain('verificaciones/camino-a-la-api.test.ts');
  });

  it('EL CENTINELA: el resolvedor sabe decir que no', () => {
    // Un `resuelve` que dijera que si a todo dejaria la prueba de abajo en verde para siempre.
    const reales = archivosDelArbol();
    expect(resuelve('src/datos/lecturas.ts', reales)).toBe(true);
    expect(resuelve('secciones/determinacion.ts', reales)).toBe(false);
  });

  it('y todas resuelven a un archivo de este arbol', () => {
    const reales = archivosDelArbol();
    const rotas = delegaciones().filter((una) => !resuelve(una.cita, reales));

    expect(
      rotas.map((una) => `  ${una.archivo}:${una.linea}  «${una.frase}»`),
      'UN JAVADOC DELEGA EN UN ARCHIVO QUE NO EXISTE:\n' +
        `${rotas.map((una) => `  ${una.archivo}:${una.linea}  «${una.frase}»`).join('\n')}\n\n` +
        '  Ese comentario afirma algo y dice que la medida o el razonamiento estan en ese\n' +
        '  archivo. Sin el archivo, la afirmacion se queda sin sujeto: quien la lea solo puede\n' +
        '  creersela sin verla o rehacer la medida desde cero. Fue el defecto de las tres citas\n' +
        '  a `secciones/determinacion.ts`, que salio con la V6 en #90 y sobrevivio en la prosa.\n\n' +
        '  Tres salidas, y las tres valen: apuntar a donde SI esta la medida en este arbol;\n' +
        '  hacer la medida que falta; o reescribir la afirmacion para que se sostenga sola.\n' +
        '  Inventar otra referencia que tampoco se pueda abrir, no.\n\n' +
        '  Y si lo que quieres es contar donde ESTUVO algo —«vivia en `api/proxy.ts`, que salio\n' +
        '  con la V6»—, eso no es una delegacion y esta guarda no lo mira: lo que la dispara es\n' +
        '  el verbo que promete que la medida esta ahi.',
    ).toEqual([]);
  });
});
