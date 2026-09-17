// @vitest-environment node
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { CONECTORES } from '../src/datos/conectores.ts';
import { EN_LA_RUTA } from '../src/pantallas/tablas.ts';

/**
 * **Los cuatro sitios de la ruta son los cuatro nombres que el backend admite** (#236, AC2).
 *
 * <h2>Que rompe si no</h2>
 *
 * `EN_LA_RUTA` no es una preferencia de esta interfaz: es el nombre con el que el parametro VIAJA.
 * El interprete de `@kamayuk/ui` lo escribe en la ruta de la hoja, el conector lo lee de ahi y lo
 * pone en la URL, y al otro lado `GuardiaDeParametros.DIALECTO_DE_LA_PAGINACION` decide si ese
 * nombre se admite. Con los dos lados diciendo cosas distintas hay **dos fallos, y ninguno se
 * parece a la causa**:
 *
 *   · si el backend ya no admite el nombre, la peticion vuelve **422 «parametro desconocido»** y
 *     la pantalla ensena una averia por haber pulsado «Siguiente»;
 *   · y si lo admite y lo ignora —que es lo que pasaba antes de #539—, la tabla dibuja la pagina 3
 *     con las filas de la 0, **en verde y sin un solo error**.
 *
 * <h2>Por que este archivo y no una afirmacion dentro de otra guarda</h2>
 *
 * Porque lo que se cruza no es «este conector manda este parametro» sino **el dialecto entero**, y
 * tiene que fallar aunque ninguna hoja lo mande todavia. `la-ruta-de-la-hoja-llega-al-conector`
 * mira lo que cada hoja escribe y lo que su conector declara: los dos lados son de este arbol, asi
 * que renombrarlos a la vez la deja en verde.
 *
 * <h2>De donde sale el otro lado</h2>
 *
 * De `_dialectoDeLaPaginacion` en `docs/50-api/parametros-de-la-api.json`, que `ParametrosDeLaApiTest`
 * escribe **de la constante del guardia** y no de una lista a mano. O sea que esto no compara este
 * arbol consigo mismo: compara la interfaz con el bytecode del backend.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const PARAMETROS = join(AQUI, '../../docs/50-api/parametros-de-la-api.json');

const contrato = JSON.parse(readFileSync(PARAMETROS, 'utf8')) as {
  readonly _dialectoDeLaPaginacion?: readonly string[];
  readonly [operacion: string]: unknown;
};

describe('el dialecto de la paginacion es el del backend (#236)', () => {
  it('EL CENTINELA: el contrato publica el dialecto, y son cuatro nombres', () => {
    // Sin esto, un archivo regenerado por una version que dejara de publicar la clave haria pasar
    // en verde la comprobacion de abajo contra `undefined`.
    expect(
      contrato._dialectoDeLaPaginacion,
      'docs/50-api/parametros-de-la-api.json no publica `_dialectoDeLaPaginacion`.\n\n' +
        '  Lo escribe `ParametrosDeLaApiTest` de `GuardiaDeParametros.DIALECTO_DE_LA_PAGINACION`;\n' +
        '  se regenera con `./gradlew :kamayuk-rentas-aplicacion:test --tests "*ParametrosDeLaApiTest*"\n' +
        '  -Dkamayuk.formas.regenerar=true`.',
    ).toBeDefined();
    expect(contrato._dialectoDeLaPaginacion).toHaveLength(4);
  });

  it('LA IGUALDAD: `EN_LA_RUTA` dice los mismos cuatro nombres, y ni uno mas', () => {
    expect(
      [...Object.values(EN_LA_RUTA)].sort(),
      'El dialecto de la paginacion no dice lo mismo en los dos lados.\n\n' +
        '  `EN_LA_RUTA` (frontend/src/pantallas/tablas.ts) es el nombre con el que el parametro\n' +
        '  VIAJA; `GuardiaDeParametros.DIALECTO_DE_LA_PAGINACION` es el que el backend admite en\n' +
        '  TODA operacion. Divergen: o el backend contesta 422 «parametro desconocido» al pulsar\n' +
        '  «Siguiente», o —peor— lo admite y lo ignora, y la tabla dibuja la pagina 0 con el\n' +
        '  rotulo de la 3, en verde.\n\n' +
        '  Se arregla moviendo LOS DOS: la constante de Java, y esta. Y regenerando\n' +
        '  `docs/50-api/parametros-de-la-api.json` y `docs/50-api/openapi/rentas-v1.yaml`.',
    ).toEqual([...(contrato._dialectoDeLaPaginacion ?? [])].sort());
  });

  it('y las claves de `EN_LA_RUTA` se llaman como su valor: el sitio ES el parametro', () => {
    // `EN_LA_RUTA.sentido === 'sentido'`. Con una clave que no case —`direccion: 'sentido'`—, el
    // codigo que la usa seguiria compilando y diciendo la palabra vieja, que es exactamente lo que
    // pasaba hasta #236: el interprete llamaba `sentidoEnLaRuta` a lo que escribia en `direccion`.
    for (const [clave, valor] of Object.entries(EN_LA_RUTA)) {
      expect(valor, `EN_LA_RUTA.${clave}`).toBe(clave);
    }
  });

  it('y toda hoja que declara la ventana declara los CUATRO, con el nombre nuevo', () => {
    // La ventana se compone en `laVentanaDe`, asi que esto no puede divergir por hoja… mientras
    // nadie escriba los cuatro a mano en un conector. Si alguien lo hace, aqui sale.
    // Sin esta linea el caso pasa EN VERDE cuando el contrato no publica el dialecto: con el
    // conjunto vacio, `delDialecto` sale vacio para toda hoja y no hay nada que comparar. Medido
    // al romperlo (#236): el centinela de arriba lo cazaba, pero ESTE caso decia que todo estaba
    // bien. Una guarda que se apaga sola cuando su entrada falta no protege de nada.
    const dialecto = new Set<string>(contrato._dialectoDeLaPaginacion ?? []);
    expect(dialecto.size, 'el contrato no publico el dialecto: no hay nada que cruzar').toBe(4);
    const mal: string[] = [];
    for (const [clave, conector] of Object.entries(CONECTORES)) {
      const suyos = (conector?.parametros ?? []).map((parametro) => parametro.nombre);
      const delDialecto = suyos.filter((nombre) => dialecto.has(nombre));
      if (delDialecto.length === 0) continue;
      if (delDialecto.length !== dialecto.size) {
        mal.push(`  ${clave} declara ${JSON.stringify(delDialecto)}`);
      }
    }
    expect(
      mal,
      'Hojas que declaran parte del dialecto de la paginacion y parte no:\n' +
        `${mal.join('\n')}\n\n` +
        '  El marco TIRA CON AVISO lo que el destino no declara, asi que el mando que escriba el\n' +
        '  que falta mueve la ruta y nadie vuelve a pedir.',
    ).toEqual([]);
  });
});
