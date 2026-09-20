// @vitest-environment node
//
// Lee las fuentes de los conectores del disco, y ademas los ejercita. No hay DOM que necesitar.

import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { CONECTORES } from '../src/datos/conectores.ts';
import { loQueDijoElServidor } from '../src/datos/laVentana.ts';
import type { Paginado } from '../src/datos/lecturas.ts';
import { CLAVES_DE_HOJA, type ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { tablasDe } from '../src/pantallas/bloques.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';
import { hayMasDe, paginasDe } from '../src/pantallas/tablas.ts';

/**
 * **El total que una tabla ensena es el que la OPERACION publica, jamas uno contado aqui** (#172
 * AC3, #187 AC4).
 *
 * <h2>Las dos mitades de la misma regla, y hay que saber distinguirlas</h2>
 *
 * `conectores.ts` prohibe **calcular un agregado que la operacion no publica**: `coa-panel`
 * pregunta «con REC notificada» y «con medida cautelar», y contarlos sobre la pagina que llego
 * daria un numero **indistinguible de uno real**.
 *
 * #172 pide lo contrario: que el total que la operacion **si** publica —`totalElementos`, 1 842
 * giros, 84 182 movimientos, 188 internamientos— **deje de tirarse**. Hasta entonces el encabezado
 * contaba las filas que tenia delante y decia «20 registros» donde el artboard dice «20 de 1 842».
 *
 * Las dos cosas se parecen tanto que la siguiente persona usaria esto para lo que la regla
 * prohibe, y por eso la diferencia no se deja escrita en un javadoc: se vigila. Un `.length` donde
 * va un `totalElementos` **no produce ningun sintoma** —sale un numero, de la forma correcta, en
 * el sitio correcto— y encima coincide con el bueno siempre que la lista quepa entera en la
 * pagina, que es justo el caso en que se prueba a mano.
 *
 * <h2>Por que un escaner de fuentes y ademas un recorrido</h2>
 *
 * El recorrido dice que HOY el numero es el publicado. El escaner dice que **no se puede escribir
 * de la otra forma**, que es lo que protege al conector numero dieciocho. Es la misma pareja que
 * `conectores.ts` usa para la regla hermana.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const CONECTORES_DIR = join(AQUI, '../src/datos/conectores');

/**
 * Los conectores de produccion, con su texto.
 *
 * **Fuera los `*DeMuestra.ts`**, y no es una excepcion comoda: son <b>respuestas de `curl` a la
 * instalacion</b>, copiadas tal cual. Su `totalElementos: 84` es lo que la instalacion contesto, o
 * sea justo lo que hay que conservar; corregirlo ahi seria falsificar la medida. Lo que esta
 * guarda vigila es el conector, que es quien decide que numero llega a la pantalla.
 */
function fuentesDeLosConectores(): readonly { readonly archivo: string; readonly texto: string }[] {
  return readdirSync(CONECTORES_DIR)
    .filter(
      (entrada) =>
        entrada.endsWith('.ts') && !entrada.includes('.test.') && !entrada.endsWith('DeMuestra.ts'),
    )
    .map((entrada) => ({
      archivo: `src/datos/conectores/${entrada}`,
      texto: readFileSync(join(CONECTORES_DIR, entrada), 'utf8'),
    }));
}

/** Lo que se le asigna a `totalElementos:` en un conector, una aparicion por linea. */
const ASIGNACION = /^\s*totalElementos:\s*(.+?),?\s*$/gm;

describe('el total de una tabla sale del envoltorio, y no de una cuenta (#172, AC3)', () => {
  it('EL CENTINELA: hay conectores, y alguno entrega un total', () => {
    const fuentes = fuentesDeLosConectores();
    expect(fuentes.length, 'no se leyo ni un conector').toBeGreaterThan(5);
    const conTotal = fuentes.filter(({ texto }) => /totalElementos:/.test(texto));
    expect(conTotal.length, 'ningun conector entrega un total publicado').toBeGreaterThan(0);
  });

  it('ninguna asignacion de `totalElementos` cuenta filas: solo lee el campo de la respuesta', () => {
    const contados: string[] = [];
    for (const { archivo, texto } of fuentesDeLosConectores()) {
      for (const casado of texto.matchAll(ASIGNACION)) {
        const derecha = (casado[1] ?? '').trim();
        // Lo unico admitido es leer el campo del envoltorio: `<loQueLlego>.totalElementos`.
        if (!/^[A-Za-z_$][\w$]*(\?)?\.totalElementos$/.test(derecha)) {
          contados.push(`  ${archivo}: totalElementos: ${derecha}`);
        }
      }
    }
    expect(
      contados,
      'Un conector compone el total de una tabla en vez de leerlo del envoltorio:\n' +
        `${contados.join('\n')}\n\n` +
        '  Lo unico que puede ir ahi es el `totalElementos` que la operacion publica. Una cuenta\n' +
        '  sobre la pagina —`contenido.length`, `filas.length`— da un numero de la forma correcta,\n' +
        '  en el sitio correcto, **y falso**: «20 de 20» sobre un catalogo de 1 842 giros. Y\n' +
        '  coincide con el bueno siempre que la lista quepa entera, que es como se prueba a mano.\n\n' +
        '  La regla hermana esta en `conectores.ts`: no se calcula un agregado que la operacion no\n' +
        '  publica. Esta es la otra mitad — el que SI publica no se recalcula.',
    ).toEqual([]);
  });

  it('y `hayMas` y `paginas` tampoco se cuentan: los dice el servidor', () => {
    const contados: string[] = [];
    for (const { archivo, texto } of fuentesDeLosConectores()) {
      for (const casado of texto.matchAll(/^\s*(hayMas|totalPaginas):\s*(.+?),?\s*$/gm)) {
        contados.push(`  ${archivo}: ${casado[1] ?? ''}: ${casado[2] ?? ''}`);
      }
    }
    expect(
      contados,
      'Un conector compone el `hayMas` o el `totalPaginas` de una tabla:\n' +
        `${contados.join('\n')}\n\n` +
        '  Los dos salen del envoltorio por `loQueDijoElServidor`, y no de aqui. Con el tope\n' +
        '  alcanzado exacto, contar las filas recibidas dice que no hay pagina siguiente **justo\n' +
        '  cuando la hay**, que es el unico caso en que equivocarse cuesta algo.',
    ).toEqual([]);
  });
});

/**
 * **Una tabla paginada en servidor tiene quien le publique lo que el servidor dijo** (#228).
 *
 * <h2>El hueco que lo trae, medido con una rotura</h2>
 *
 * `la-ruta-de-la-hoja-llega-al-conector.test.ts` comprueba que los nombres de `hayMas` y `paginas`
 * **se deriven** de la clave de la tabla, que es la mitad que impide que se escriban mal. La otra
 * mitad no la vigilaba nadie: que el conector los **ponga**. Medido quitando
 * `nombrados: loQueDijoElServidor('muestra-del-programa', muestra)` de `FIS_PROG` — el unico rojo
 * salio de una prueba escrita en ese mismo PR, o sea que el conector **diecinueve** no habria
 * tenido quien se lo dijera.
 *
 * Y el sintoma es mudo y permanente: sin el nombrado, el interprete no encuentra `hayMas`, lee eso
 * como «no hay pagina siguiente» y **«Siguiente» sale impedido para siempre** sobre una relacion de
 * 84 predios — con la barra diciendo «Pagina 1 de undefined» y sin un solo error.
 *
 * Es un escaner de fuentes y no un recorrido a proposito, por lo mismo que la regla de arriba: el
 * recorrido diria que HOY estan; esto dice que **no se puede escribir de la otra forma**.
 */
describe('toda tabla paginada en servidor publica lo que el SERVIDOR dijo (#228)', () => {
  /** Las tablas con `clave` que paginan en servidor, con la hoja que las dibuja. */
  const paginadas = CLAVES_DE_HOJA.flatMap((hoja: ClaveDeHoja) =>
    tablasDe(pantallaDe(hoja)).flatMap((tabla) =>
      tabla.clave === undefined || tabla.paginacion?.en !== 'servidor'
        ? []
        : [{ hoja, clave: tabla.clave }],
    ),
  );

  it('EL CENTINELA: hay tablas que paginan en servidor, y todas tienen conector', () => {
    // Sin esto, lo de abajo recorreria una lista vacia y pasaria en verde sobre la nada.
    expect(paginadas.length, 'ninguna tabla pagina en servidor').toBeGreaterThan(0);
    const sinConector = paginadas.filter(({ hoja }) => CONECTORES[hoja] === undefined);
    expect(
      sinConector.map(({ hoja }) => `  ${hoja}`),
      'Una hoja declara una tabla paginada en SERVIDOR y no tiene conector: los mandos se\n' +
        '  dibujarian sobre una pantalla que no pide nada.',
    ).toEqual([]);
  });

  it('el conector de cada una llama a `loQueDijoElServidor` con la clave de SU tabla', () => {
    const fuentes = fuentesDeLosConectores();
    const mudas: string[] = [];
    for (const { hoja, clave } of paginadas) {
      const lallama = fuentes.some(({ texto }) =>
        texto.includes(`loQueDijoElServidor('${clave}'`),
      );
      if (!lallama) mudas.push(`  ${hoja} · tabla «${clave}»`);
    }
    expect(
      mudas,
      'Una tabla pagina en servidor y su conector no publica lo que el servidor dijo:\n' +
        `${mudas.join('\n')}\n\n` +
        '  `paginacion.hayMas` y `paginacion.paginas` son NOMBRES de `nombrados`, no datos: el\n' +
        '  interprete los busca ahi. Sin ponerlos, lee la ausencia como «no hay pagina siguiente»\n' +
        '  y **«Siguiente» sale impedido para siempre**, con la barra diciendo «Pagina 1 de\n' +
        '  undefined» y sin un solo error. Se ponen con `loQueDijoElServidor(<clave>, pagina)`,\n' +
        '  que copia el envoltorio tal cual y no cuenta nada.',
    ).toEqual([]);
  });
});

describe('y medido sobre el reparto, no solo sobre el texto', () => {
  /** Una pagina con MENOS filas que el total: es lo que separa el publicado de la cuenta. */
  function ventana<T>(contenido: readonly T[], totalElementos: number): Paginado<T> {
    return {
      contenido,
      pagina: 0,
      tamano: 20,
      totalElementos,
      totalPaginas: Math.ceil(totalElementos / 20),
      hayMas: true,
    };
  }

  it('`aut-cat` dice 1 842 sobre una pagina de dos: el catalogo entero, no lo que llego', () => {
    const giro = (codigo: string) => ({
      codigo,
      descripcion: 'Venta al por menor',
      seccion: 'G',
      riesgoItse: 'BAJO',
    });
    const reparto = CONECTORES['aut-cat']?.repartir(
      ventana([giro('A-0111-01'), giro('A-0111-02')], 1842) as never,
    );

    expect(reparto?.tablas?.get('giros-ciiu')?.filas).toHaveLength(2);
    // Con `contenido.length` esto diria 2, y la pantalla afirmaria que el catalogo CIIU son dos
    // giros. El artboard escribe «4 de 1,842» justamente porque la tabla es una ventana.
    expect(reparto?.tablas?.get('giros-ciiu')?.totalElementos).toBe(1842);
  });

  it('y lo que viaja es el NUMERO: la frase «20 de 1 842» la arma quien tiene `t()` delante', () => {
    // Un «de» escrito en un conector llegaria al DOM en castellano en cualquier idioma (#103). El
    // conector entrega dato; `useDatosDeLaHoja` lo convierte en la frase del saco.
    const reparto = CONECTORES['aut-cat']?.repartir(ventana([], 1842) as never);
    expect(typeof reparto?.tablas?.get('giros-ciiu')?.totalElementos).toBe('number');
  });

  it('`loQueDijoElServidor` copia el envoltorio tal cual, y no deduce nada', () => {
    // El caso que importa: veinte filas de veinte, y el servidor dice que HAY mas. Contando las
    // filas recibidas no se podria saber.
    const dicho = loQueDijoElServidor('movimientos', {
      contenido: new Array<number>(20).fill(0),
      pagina: 0,
      tamano: 20,
      totalElementos: 84182,
      totalPaginas: 4210,
      hayMas: true,
    });

    expect(dicho.get(hayMasDe('movimientos'))).toBe(true);
    expect(dicho.get(paginasDe('movimientos'))).toBe('4210');
  });

  it('y con el tope alcanzado y SIN mas paginas, dice que no: no lo decide el tamano', () => {
    const dicho = loQueDijoElServidor('movimientos', {
      contenido: new Array<number>(20).fill(0),
      pagina: 0,
      tamano: 20,
      totalElementos: 20,
      totalPaginas: 1,
      hayMas: false,
    });

    expect(dicho.get(hayMasDe('movimientos'))).toBe(false);
    expect(dicho.get(paginasDe('movimientos'))).toBe('1');
  });

  it('`coa-panel` sigue SIN contar sus cuatro recuentos, que es la regla hermana', () => {
    // La que esta prueba no puede relajar. `totalElementos` llena UN campo —«expedientes
    // abiertos», que es literalmente lo que la operacion cuenta— y los otros cuatro preguntan
    // otra cosa: «con REC notificada», «con medida cautelar». Contarlos sobre la pagina daria un
    // numero indistinguible de uno real.
    const reparto = CONECTORES['coa-panel']?.repartir(ventana([{}, {}], 388) as never);

    expect(reparto?.valores.get('0|1')).toBe('388');
    expect(reparto?.valores.size).toBe(1);
    expect(reparto?.noPublicados.size).toBe(4);
  });
});
