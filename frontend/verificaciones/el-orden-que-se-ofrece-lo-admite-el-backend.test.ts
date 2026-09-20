// @vitest-environment node
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import type { DefinicionDeTabla } from '@kamayuk/ui';

import type { ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { CONECTORES } from '../src/datos/conectores.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';
import { EN_LA_RUTA } from '../src/pantallas/tablas.ts';

/**
 * **El orden que la tabla OFRECE es el que el backend admite, y su primero es el de por omision**
 * (#263).
 *
 * <h2>El modo de fallo, que es mudo</h2>
 *
 * Cuando la ruta no trae un `ordenarPor` de la lista, `laVentanaQueSePide` **no manda el
 * parametro** a proposito —ver su javadoc—, y entonces mandan dos lados que no se hablan: el
 * interprete de `@kamayuk/ui` dibuja como ordenada **el primero de `orden.campos`**
 * (`campoOrdenado`, `MandosDeLaTabla.tsx`), y el backend ordena por su `ORDEN_POR_OMISION`. Si no
 * son el mismo campo, la barra dice «ordenado por Contribuyente» y las filas llegan ordenadas por
 * otra cosa: **en verde, sin un solo error en consola, y con la tabla pareciendo correcta**.
 *
 * Y el otro lado del mismo par: un campo que la tabla ofrece y el backend no admite viaja en la
 * ruta en cuanto alguien pulsa esa cabecera, y `OrdenSeguro.clausula` contesta **422
 * ORDEN_NO_ADMITIDO**. Eso si se ve —la pantalla ensena una averia—, pero se ve **en produccion**
 * y no aqui.
 *
 * <h2>De donde sale el otro lado</h2>
 *
 * De la clave `orden` de `docs/50-api/parametros-de-la-api.json`, que escribe
 * `ParametrosDeLaApiTest` —via `OrdenDeCadaOperacion`— siguiendo la llamada por el **bytecode**
 * hasta el `OrdenSeguro` que la operacion usa de verdad, y leyendo el `ORDEN_POR_OMISION` del
 * cuerpo de ese handler. O sea que esto no compara este arbol consigo mismo: compara la interfaz
 * con el backend.
 *
 * <h2>Por que el cruce se hace por hoja, y no por tabla</h2>
 *
 * Porque el conector declara **la operacion** —`laVentanaDe('GET /…')`— y la definicion declara
 * **el orden**, y lo unico que los ata es la hoja. Asi que el cruce exige que la atadura sea
 * univoca: una hoja que declarase dos ventanas, o dos tablas ordenables, no tiene un par que
 * cruzar y **sale roja** en vez de elegir una de las dos al azar.
 *
 * <h2>Y los dos conjuntos tienen que ser el MISMO</h2>
 *
 * Una guarda que solo recorre las hojas con ventana se apaga sola el dia que alguien quite
 * `laVentanaDe` de un conector: la tabla seguiria ofreciendo su orden, ya sin nadie que lo cruce.
 * Y al reves, una hoja que pide ventana y no declara `orden` ofrece un `?ordenarPor=` que ninguna
 * cabecera puede escribir. Por eso se comparan los dos conjuntos y no uno.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const PARAMETROS = join(AQUI, '../../docs/50-api/parametros-de-la-api.json');

/** Lo que el contrato publica de una operacion paginada que alcanza UNA lista blanca. */
interface OrdenDelContrato {
  readonly porOmision: string;
  readonly admitidos: readonly string[];
}

const contrato = JSON.parse(readFileSync(PARAMETROS, 'utf8')) as Readonly<
  Record<string, { readonly orden?: OrdenDelContrato } | undefined>
>;

const REGENERAR =
  '  Lo escribe `ParametrosDeLaApiTest` de `OrdenDeCadaOperacion`; se regenera con\n' +
  '  `./gradlew :kamayuk-rentas-aplicacion:test --tests "*ParametrosDeLaApiTest*"\n' +
  '  -Dkamayuk.formas.regenerar=true`.';

/** Las operaciones para las que el contrato publica un orden. */
const CON_ORDEN: ReadonlyMap<string, OrdenDelContrato> = new Map(
  Object.entries(contrato).flatMap(([operacion, cuerpo]) =>
    cuerpo?.orden === undefined ? [] : [[operacion, cuerpo.orden] as const],
  ),
);

/** Las hojas cuyo conector declara la ventana, con las operaciones a las que se la pide. */
const VENTANA_POR_HOJA: ReadonlyMap<ClaveDeHoja, readonly string[]> = new Map(
  Object.entries(CONECTORES).flatMap(([hoja, conector]) => {
    const suyas = [
      ...new Set(
        (conector?.parametros ?? [])
          .filter((parametro) => parametro.nombre === EN_LA_RUTA.ordenarPor)
          .map((parametro) => parametro.operacion),
      ),
    ];
    return suyas.length === 0 ? [] : [[hoja as ClaveDeHoja, suyas] as const];
  }),
);

/** Las hojas cuya definicion declara alguna tabla ordenable, con esas tablas. */
const ORDENABLES_POR_HOJA: ReadonlyMap<ClaveDeHoja, readonly DefinicionDeTabla[]> = new Map(
  [...Object.keys(CONECTORES)].flatMap((hoja) => {
    const suyas = pantallaDe(hoja as ClaveDeHoja)
      .bloques.map((bloque) => bloque.tabla)
      .filter((tabla): tabla is DefinicionDeTabla => tabla?.orden !== undefined);
    return suyas.length === 0 ? [] : [[hoja as ClaveDeHoja, suyas] as const];
  }),
);

describe('el orden que se ofrece lo admite el backend (#263)', () => {
  it('EL CENTINELA: el contrato publica el orden de sus operaciones paginadas', () => {
    // Sin esto, un archivo regenerado por una version que dejara de publicar la clave dejaria los
    // casos de abajo cruzando contra `undefined` —o, peor, contra el conjunto vacio— en verde.
    expect(
      CON_ORDEN.size,
      `docs/50-api/parametros-de-la-api.json no publica «orden» de ninguna operacion.\n\n${REGENERAR}`,
    ).toBeGreaterThan(0);
    for (const [operacion, orden] of CON_ORDEN) {
      expect(orden.porOmision, `${operacion}: «orden» sin «porOmision»`).toBeTruthy();
      expect(orden.admitidos.length, `${operacion}: «orden» sin «admitidos»`).toBeGreaterThan(0);
    }
  });

  it('EL OTRO CENTINELA: hay hojas que ofrecen orden, y son las mismas que piden ventana', () => {
    // Sobre el conjunto vacio los dos casos de abajo pasan sin comprobar nada. Y con los dos
    // conjuntos distintos comprobarian de menos: una tabla ordenable cuya hoja no pide ventana
    // ofreceria un orden que no viaja, y una hoja que pide ventana sin tabla ordenable ofreceria
    // un `?ordenarPor=` que ninguna cabecera escribe.
    expect(
      VENTANA_POR_HOJA.size,
      'ningun conector declara la ventana: no hay nada que cruzar',
    ).toBeGreaterThan(0);
    expect(
      [...ORDENABLES_POR_HOJA.keys()].sort(),
      'Las hojas que declaran una tabla ordenable no son las que piden ventana.\n\n' +
        '  El orden lo hace el SERVIDOR: una tabla ordenable cuya hoja no manda `?ordenarPor=`\n' +
        '  dibuja unas cabeceras que mueven la ruta y no mueven las filas; y una hoja que manda el\n' +
        '  parametro sin tabla ordenable no tiene con que escribirlo.',
    ).toEqual([...VENTANA_POR_HOJA.keys()].sort());
  });

  it('LA ATADURA: cada hoja ata UNA operacion con UNA tabla ordenable, y el contrato la publica', () => {
    const mal: string[] = [];
    for (const [hoja, operaciones] of VENTANA_POR_HOJA) {
      const tablas = ORDENABLES_POR_HOJA.get(hoja) ?? [];
      if (operaciones.length !== 1 || tablas.length !== 1) {
        mal.push(
          `  ${hoja}: ${String(operaciones.length)} operacion(es) con ventana y ` +
            `${String(tablas.length)} tabla(s) ordenable(s)`,
        );
        continue;
      }
      if (!CON_ORDEN.has(operaciones[0] ?? '')) {
        mal.push(`  ${hoja}: el contrato no publica «orden» de «${operaciones[0] ?? ''}»`);
      }
    }
    expect(
      mal,
      `Hojas cuyo orden no se puede cruzar con el del backend:\n${mal.join('\n')}\n\n` +
        '  Lo unico que ata la operacion —que declara el conector— con el orden —que declara la\n' +
        '  definicion— es la hoja. Con dos de cualquiera de los dos lados no hay par que cruzar, y\n' +
        '  elegir uno al azar seria inventarse la medida.\n\n' +
        '  Y si lo que falta es la publicacion del contrato: una operacion que alcanza varias\n' +
        '  listas blancas, o ninguna, no publica «orden» a proposito (ver el javadoc de\n' +
        '  `OrdenDeCadaOperacion`). Entonces la tabla no puede ofrecer orden.\n\n' +
        REGENERAR,
    ).toEqual([]);
  });

  it('EL PRIMERO es el `ORDEN_POR_OMISION` de su operacion', () => {
    const mal: string[] = [];
    for (const [hoja, operaciones] of VENTANA_POR_HOJA) {
      const operacion = operaciones[0] ?? '';
      const tabla = (ORDENABLES_POR_HOJA.get(hoja) ?? [])[0];
      const orden = CON_ORDEN.get(operacion);
      if (tabla?.orden === undefined || orden === undefined) continue; // lo dice la atadura
      const primero = tabla.orden.campos[0]?.valor;
      if (primero !== orden.porOmision) {
        mal.push(
          `  ${hoja} · tabla «${tabla.clave ?? ''}» · ${operacion}:\n` +
            `      la tabla dibuja como ordenada «${primero ?? ''}» y el backend ordena por «${orden.porOmision}»`,
        );
      }
    }
    expect(
      mal,
      `Tablas que anuncian un orden que el backend no hace:\n${mal.join('\n')}\n\n` +
        '  Sin `?ordenarPor=` en la ruta, el conector NO manda el parametro (`laVentanaQueSePide`),\n' +
        '  el interprete dibuja como ordenado `orden.campos[0]` y el backend ordena por su\n' +
        '  `ORDEN_POR_OMISION`. Con los dos distintos la barra miente sobre el orden de las filas,\n' +
        '  EN VERDE y sin un solo error en consola.\n\n' +
        '  Se arregla en UN lado: o `campos[0]` de la definicion, o el `ORDEN_POR_OMISION` del\n' +
        '  controlador. Lo que no vale es dejarlos distintos.',
    ).toEqual([]);
  });

  it('y TODO campo que se ofrece esta en la lista blanca de su operacion', () => {
    const mal: string[] = [];
    for (const [hoja, operaciones] of VENTANA_POR_HOJA) {
      const operacion = operaciones[0] ?? '';
      const tabla = (ORDENABLES_POR_HOJA.get(hoja) ?? [])[0];
      const orden = CON_ORDEN.get(operacion);
      if (tabla?.orden === undefined || orden === undefined) continue; // lo dice la atadura
      const admitidos = new Set(orden.admitidos);
      const fuera = tabla.orden.campos
        .map((campo) => campo.valor)
        .filter((valor) => !admitidos.has(valor));
      if (fuera.length > 0) {
        mal.push(
          `  ${hoja} · tabla «${tabla.clave ?? ''}» · ${operacion}: ofrece ${JSON.stringify(fuera)}\n` +
            `      y la operacion admite ${JSON.stringify(orden.admitidos)}`,
        );
      }
    }
    expect(
      mal,
      `Tablas que ofrecen ordenar por un campo que su operacion no admite:\n${mal.join('\n')}\n\n` +
        '  El orden va por lista cerrada: `OrdenSeguro.clausula` contesta 422 ORDEN_NO_ADMITIDO con\n' +
        '  cualquier campo que no este en el `OrdenSeguro.sobre(...)` de su repositorio. O sea que\n' +
        '  la cabecera se dibuja, se pulsa, y la pantalla ensena una averia.',
    ).toEqual([]);
  });
});
