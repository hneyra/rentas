// @vitest-environment node
//
// Lee el contrato y el codigo del BACKEND, los dos del disco. No hay DOM que necesitar.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import type { DefinicionDeTabla } from '@kamayuk/ui';

import { CONECTORES } from '../src/datos/conectores.ts';
import { YA_SERVIDAS } from '../src/datos/servidas.ts';
import { CATALOGO } from '../src/catalogo.ts';
import { CLAVES_DE_HOJA, type ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';
import { EN_LA_RUTA, hayMasDe, paginasDe } from '../src/pantallas/tablas.ts';

/**
 * **La cadena entera de lo que la hoja lleva en su ruta, eslabon por eslabon** (#172, #186, #187).
 *
 * <h2>Por que hace falta una guarda y no basta con leer el diff</h2>
 *
 * Porque la cadena tiene **cinco eslabones en cuatro archivos** y romper cualquiera de ellos deja
 * la pantalla **en verde, dibujando la pagina 0 con el rotulo «Pagina 3»**:
 *
 * <ol>
 *   <li>la definicion declara `paginacion.enLaRuta` y el interprete escribe ahi;</li>
 *   <li>el conector declara ese mismo sitio en `Conector.parametros`;</li>
 *   <li>`catalogo.ts` lo deriva al `enLaRuta` del destino — y el marco <b>tira con aviso</b> lo que
 *       un destino no declara, o sea que sin esto el parametro no llega ni a la ruta;</li>
 *   <li>`useDatosDeLaHoja` lo mete en la llave de la consulta — sin eso no se vuelve a pedir;</li>
 *   <li>el contrato del backend publica ese parametro — mandar uno que no publica es construir
 *       sobre un nombre que nada de este repositorio comprueba (#26).</li>
 * </ol>
 *
 * Ninguno de los cinco produce un error al romperse. El primero y el tercero dan **una paginacion
 * que no pagina**, que es peor que ninguna: el mando existe, se pulsa, la direccion cambia y las
 * filas son las mismas.
 *
 * <h2>Y por que esta prueba lee el codigo JAVA del backend</h2>
 *
 * Porque **la lista blanca del orden no esta en el contrato**: `parametros-de-la-api.json` publica
 * que existe `?ordenarPor=`, no que valores admite —y `OrdenSeguro` rechaza con **422
 * ORDEN_NO_ADMITIDO** cualquiera que no este en la suya—. El backend vive en este mismo
 * repositorio, asi que la lista se lee de donde esta declarada en vez de copiarse aqui: copiada,
 * el dia que el backend le quite una columna esto seguiria en verde y la pantalla ofreceria un
 * orden que contesta 422. Que el contrato deberia publicarla es otro issue, y esta abierto.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');
const RAIZ = join(FRONTEND, '..');
const PARAMETROS = join(RAIZ, 'docs/50-api/parametros-de-la-api.json');
const BACKEND = join(RAIZ, 'backend');

interface ParametrosDeUnaOperacion {
  readonly obligatorios: readonly string[];
  readonly opcionales: readonly string[];
}

const contrato = JSON.parse(readFileSync(PARAMETROS, 'utf8')) as Record<
  string,
  ParametrosDeUnaOperacion
>;

/** Si el contrato de la PETICION publica ese parametro para esa operacion. */
function loPublica(operacion: string, parametro: string): boolean {
  const suyos = contrato[operacion];
  return suyos !== undefined && [...suyos.obligatorios, ...suyos.opcionales].includes(parametro);
}

/** Las tablas de una hoja que llevan `clave`, con ella. */
function tablasDe(clave: ClaveDeHoja): readonly (DefinicionDeTabla & { readonly clave: string })[] {
  return pantallaDe(clave).bloques.flatMap((bloque) => {
    const tabla = bloque.tabla;
    return tabla?.clave === undefined
      ? []
      : [tabla as DefinicionDeTabla & { readonly clave: string }];
  });
}

/** Los sitios de la ruta que las tablas de una hoja ESCRIBEN. */
function sitiosQueEscribeLaHoja(clave: ClaveDeHoja): readonly string[] {
  return tablasDe(clave).flatMap((tabla) => [
    ...(tabla.paginacion === undefined ? [] : [tabla.paginacion.enLaRuta]),
    ...(tabla.paginacion?.tamanoEnLaRuta === undefined ? [] : [tabla.paginacion.tamanoEnLaRuta]),
    ...(tabla.orden === undefined ? [] : [tabla.orden.enLaRuta, tabla.orden.sentidoEnLaRuta]),
  ]);
}

describe('lo que un conector declara en la ruta, lo publica el contrato (#172, AC1)', () => {
  it('EL CENTINELA: hay conectores que declaran parametros, y el contrato se pudo leer', () => {
    // Sin esto, todo lo de abajo recorreria una lista vacia y pasaria en verde sobre un sistema
    // que no manda ni un parametro.
    const conParametros = Object.values(CONECTORES).filter(
      (conector) => (conector?.parametros ?? []).length > 0,
    );
    expect(conParametros.length, 'ningun conector declara parametros').toBeGreaterThan(0);
    expect(Object.keys(contrato).length, 'el contrato de la peticion llego vacio').toBeGreaterThan(
      50,
    );
  });

  it('cada parametro declarado lo publica SU operacion, y no otra', () => {
    const inventados: string[] = [];
    for (const [hoja, conector] of Object.entries(CONECTORES)) {
      for (const parametro of conector?.parametros ?? []) {
        if (!loPublica(parametro.operacion, parametro.nombre)) {
          inventados.push(`  ${hoja} manda «${parametro.nombre}» a «${parametro.operacion}»`);
        }
      }
    }
    expect(
      inventados,
      'Un conector manda un parametro que el contrato de la PETICION no publica:\n' +
        `${inventados.join('\n')}\n\n` +
        '  `parametros-de-la-api.json` sale de la FIRMA de cada controlador. Un nombre que no\n' +
        '  esta ahi no lo puede comprobar nada de este repositorio: sale a la red y vuelve con un\n' +
        '  422 que se confunde con un error de negocio — es lo que #26 midio con `/rentas/predios`.',
    ).toEqual([]);
  });

  it('y la operacion a la que viaja esta SERVIDA: no se declara un parametro de una ruta apagada', () => {
    const servidas = new Set(YA_SERVIDAS.map((o) => `${o.metodo} ${o.ruta}`));
    const apagadas: string[] = [];
    for (const [hoja, conector] of Object.entries(CONECTORES)) {
      for (const parametro of conector?.parametros ?? []) {
        if (!servidas.has(parametro.operacion)) {
          apagadas.push(`  ${hoja} · ${parametro.operacion}`);
        }
      }
    }
    expect(apagadas, `Parametros declarados para operaciones que no se sirven:\n${apagadas.join('\n')}`).toEqual([]);
  });
});

describe('el catalogo DERIVA del conector lo que la hoja lleva en la ruta (#186, AC1)', () => {
  it('el destino declara exactamente los parametros de su conector, ni uno mas ni uno menos', () => {
    // Derivarlo es lo que impide el fallo mudo: con una lista paralela, el marco ignoraria con
    // aviso el `?pagina=` que el mando acaba de escribir y la tabla dibujaria la pagina 0.
    const descuadres: string[] = [];
    for (const modulo of CATALOGO) {
      for (const destino of modulo.destinos) {
        const delConector = (CONECTORES[destino.clave as ClaveDeHoja]?.parametros ?? []).map(
          (p) => p.nombre,
        );
        const delCatalogo = [...(destino.enLaRuta?.parametros ?? [])];
        if (JSON.stringify(delConector) !== JSON.stringify(delCatalogo)) {
          descuadres.push(
            `  ${destino.clave}: conector ${JSON.stringify(delConector)} vs catalogo ${JSON.stringify(delCatalogo)}`,
          );
        }
      }
    }
    expect(descuadres, `El catalogo y el conector no dicen lo mismo:\n${descuadres.join('\n')}`).toEqual([]);
  });
});

describe('una tabla que mueve la ruta tiene quien la lea (#186, AC1; #187, AC3)', () => {
  it('EL CENTINELA: hay tablas que declaran `paginacion` en servidor', () => {
    const paginadas = CLAVES_DE_HOJA.flatMap((clave) =>
      tablasDe(clave).filter((tabla) => tabla.paginacion !== undefined),
    );
    expect(paginadas.length, 'ninguna tabla declara paginacion').toBeGreaterThan(0);
  });

  it('todo sitio que una tabla ESCRIBE lo declara el conector de su hoja, o nadie lo pide', () => {
    const huerfanos: string[] = [];
    for (const clave of CLAVES_DE_HOJA) {
      const declarados = new Set(
        (CONECTORES[clave]?.parametros ?? []).map((parametro) => parametro.nombre),
      );
      for (const sitio of sitiosQueEscribeLaHoja(clave)) {
        if (!declarados.has(sitio)) huerfanos.push(`  ${clave} escribe «${sitio}» y no lo declara`);
      }
    }
    expect(
      huerfanos,
      'Una tabla escribe un sitio de la ruta que su conector no lee:\n' +
        `${huerfanos.join('\n')}\n\n` +
        '  El mando se dibuja, se pulsa, la direccion cambia y **nadie vuelve a pedir**: la tabla\n' +
        '  sigue dibujando la pagina 0 con el rotulo de la 3. Una paginacion que no pagina es\n' +
        '  peor que ninguna, porque parece que funciona.',
    ).toEqual([]);
  });

  it('y al reves: una hoja que declara la ventana tiene una tabla que la dibuja', () => {
    // Sin esto, un conector podria mandar `?pagina=` que nadie puede mover: una ventana fija sin
    // mandos, que es lo que habia antes de #186 y lo que este issue quita.
    const sinMandos: string[] = [];
    for (const clave of CLAVES_DE_HOJA) {
      const declara = (CONECTORES[clave]?.parametros ?? []).some(
        (parametro) => parametro.nombre === EN_LA_RUTA.pagina,
      );
      const dibuja = tablasDe(clave).some((tabla) => tabla.paginacion !== undefined);
      if (declara && !dibuja) sinMandos.push(`  ${clave}`);
    }
    expect(sinMandos, `Hojas que piden una pagina que nadie puede mover:\n${sinMandos.join('\n')}`).toEqual([]);
  });

  it('una hoja pagina UNA tabla: dos compartirian el sitio y se moverian juntas', () => {
    const conDos = CLAVES_DE_HOJA.filter(
      (clave) => tablasDe(clave).filter((tabla) => tabla.paginacion !== undefined).length > 1,
    );
    expect(
      conDos,
      'Dos tablas de la misma hoja declaran paginacion, y las dos leen `?pagina=`:\n' +
        `${conDos.join(', ')}\n\n` +
        '  Pulsar «Siguiente» en una moveria las dos. Lo que hay que decidir es como se llama el\n' +
        '  sitio de la segunda — ver `pantallas/tablas.ts`.',
    ).toEqual([]);
  });

  it('`hayMas` y `paginas` se nombran DERIVADOS de la tabla, y el conector los publica', () => {
    // Son nombres de `DatosDeLaPantalla.nombrados` y no datos: escritos a mano en la definicion y
    // en el conector, un nombre que no case deja `hayMas` sin valor y el interprete lee eso como
    // «no hay pagina siguiente». El boton sale impedido para siempre, sin un solo error.
    const malNombrados: string[] = [];
    for (const clave of CLAVES_DE_HOJA) {
      for (const tabla of tablasDe(clave)) {
        const paginacion = tabla.paginacion;
        if (paginacion === undefined || paginacion.en !== 'servidor') continue;
        if (paginacion.hayMas !== hayMasDe(tabla.clave)) {
          malNombrados.push(`  ${clave}/${tabla.clave}: hayMas «${paginacion.hayMas}»`);
        }
        if (paginacion.paginas !== paginasDe(tabla.clave)) {
          malNombrados.push(`  ${clave}/${tabla.clave}: paginas «${String(paginacion.paginas)}»`);
        }
      }
    }
    expect(malNombrados, `Nombres que no salen de la clave de la tabla:\n${malNombrados.join('\n')}`).toEqual([]);
  });
});

/**
 * **La lista blanca del orden, leida del backend de este mismo repositorio.**
 *
 * `repositorio` declara que columnas admite `ORDER BY` —y `OrdenSeguro.sobre` admite ademas su
 * `camelCase`—; `controlador` declara por cual ordena cuando no se manda `?ordenarPor=`.
 *
 * **El primero de `orden.campos` tiene que ser ese**: sin campo en la ruta, el interprete dibuja
 * el primero (`campoOrdenado`) y el conector **no manda nada**, asi que el que ordena es el del
 * backend. Si no coincidieran, la barra diria «ordenado por X» sobre filas ordenadas por Y.
 */
const ORDEN_DEL_BACKEND: Readonly<
  Record<string, { readonly repositorio: string; readonly constante: string; readonly controlador: string }>
> = {
  'GET /licencias/ciiu': {
    repositorio: 'kamayuk-rentas-licencias/src/main/java/kamayuk/rentas/licencias/infraestructura/CiiuRepositoryJdbc.java',
    constante: 'ORDEN',
    controlador: 'kamayuk-rentas-licencias/src/main/java/kamayuk/rentas/licencias/infraestructura/web/CiiuController.java',
  },
  'GET /seguridad/auditoria': {
    repositorio: 'kamayuk-rentas-seguridad/src/main/java/kamayuk/rentas/seguridad/infraestructura/SesionRepositoryJdbc.java',
    constante: 'ORDEN_AUDITORIA',
    controlador: 'kamayuk-rentas-seguridad/src/main/java/kamayuk/rentas/seguridad/infraestructura/web/SesionController.java',
  },
  'GET /fiscalizacion/programas/{id}/muestra': {
    repositorio: 'kamayuk-rentas-fiscalizacion/src/main/java/kamayuk/rentas/fiscalizacion/infraestructura/MuestraDelProgramaRepositoryJdbc.java',
    constante: 'ORDEN',
    controlador: 'kamayuk-rentas-fiscalizacion/src/main/java/kamayuk/rentas/fiscalizacion/infraestructura/web/MuestraController.java',
  },
  'GET /transito/internamientos': {
    repositorio: 'kamayuk-rentas-sanciones/src/main/java/kamayuk/rentas/sanciones/infraestructura/InternamientoRepositoryJdbc.java',
    constante: 'ORDEN',
    controlador: 'kamayuk-rentas-sanciones/src/main/java/kamayuk/rentas/sanciones/infraestructura/web/InternamientosController.java',
  },
};

/** `"fecha_ingreso"` -> `"fechaIngreso"`, como lo hace `OrdenSeguro.sobre`. */
const aCamelCase = (columna: string): string =>
  columna.replace(/_([a-z0-9])/g, (_todo, letra: string) => letra.toUpperCase());

/**
 * **Los campos que esa operacion admite en `?ordenarPor=`, leidos de su repositorio.**
 *
 * <h2>Se lee la CADENA entera, y no solo `sobre(...)` — es el hallazgo de #228</h2>
 *
 * Hasta este issue esto leia los argumentos de `OrdenSeguro.sobre(...)` y anadia su `camelCase`.
 * Eso era la lista blanca de `OrdenSeguro` **hasta #546**, que le anadio `publicandoComo(campo,
 * columna)`: declara con que nombre publica el RECURSO una columna cuyo `camelCase` no es el campo
 * que sale por HTTP, y **retira el `camelCase` automatico de esa columna** —dejarlo dejaria los dos
 * nombres vivos, que era el defecto de partida—.
 *
 * O sea que con la lectura vieja esta guarda se equivocaba **en las dos direcciones a la vez** sobre
 * toda operacion que lo use: daba por bueno el nombre que el backend RECHAZA y por malo el que
 * ADMITE. Medido con la muestra de `fis-prog`, que es la primera que llega aqui con
 * `publicandoComo("sector", "sector_codigo")`: ofrecer `?ordenarPor=sectorCodigo` pasaba en
 * **verde**, y `OrdenDeLaDeteccionFronteraTest` del backend prueba del otro lado que eso es un
 * **422** —«sectorCodigo es el camelCase automatico que publicandoComo retira, y es el nombre que
 * ninguna fila lleva»—. Un desplegable que se mueve y rompe la tabla, con la guarda en verde.
 *
 * `desempatandoPor` y `conNulosAlFinal` **no cambian la lista** —el primero anade una columna de
 * desempate que el cliente no pide y el segundo declara anulable una ya declarada—, asi que no se
 * leen: lo que hace falta es no confundirlos con `publicandoComo`, y por eso se lee la cadena hasta
 * el `;` y se buscan las llamadas por su nombre en vez de contar parentesis.
 */
function camposAdmitidos(operacion: string): readonly string[] {
  const donde = ORDEN_DEL_BACKEND[operacion];
  if (donde === undefined) {
    throw new Error(
      `Una tabla ofrece ordenar por «${operacion}» y aqui no esta escrito donde vive su lista ` +
        'blanca. Se anade a `ORDEN_DEL_BACKEND` con el repositorio que la declara: sin eso, esta ' +
        'guarda no puede decir si el orden que se ofrece contesta 422.',
    );
  }
  const fuente = readFileSync(join(BACKEND, donde.repositorio), 'utf8');
  // La declaracion ENTERA, hasta su `;`: `sobre(...)` y lo que se le encadene detras.
  const declaracion = new RegExp(`OrdenSeguro\\s+${donde.constante}\\s*=\\s*([^;]*);`, 's').exec(
    fuente,
  )?.[1];
  const sobre = declaracion === undefined ? null : /OrdenSeguro\.sobre\(([^)]*)\)/s.exec(declaracion);
  if (declaracion === undefined || sobre?.[1] === undefined) {
    throw new Error(
      `No se encontro «${donde.constante} = OrdenSeguro.sobre(...)» en ${donde.repositorio}.\n` +
        '  O la constante cambio de nombre, o el orden dejo de ir por lista blanca. Las dos cosas\n' +
        '  hay que mirarlas: esta guarda no puede pasar en verde sin haber leido nada.',
    );
  }

  const columnas = [...sobre[1].matchAll(/"([a-z0-9_]+)"/gi)].map((uno) => uno[1] ?? '');
  const admitidos = new Set([...columnas, ...columnas.map(aCamelCase)]);
  // Y lo que `publicandoComo` hace, en el mismo orden que el Java: quita el `camelCase` automatico
  // de la columna renombrada y pone el nombre que el recurso publica. La columna cruda se queda.
  for (const renombre of declaracion.matchAll(
    /\.publicandoComo\(\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\)/gs,
  )) {
    const campo = renombre[1] ?? '';
    const columna = renombre[2] ?? '';
    admitidos.delete(aCamelCase(columna));
    admitidos.add(campo);
  }
  return [...admitidos];
}

/** Por que campo ordena esa operacion cuando no se manda `?ordenarPor=`. */
function ordenPorOmision(operacion: string): string {
  const donde = ORDEN_DEL_BACKEND[operacion];
  if (donde === undefined) throw new Error(`Falta la fuente del orden de «${operacion}»`);
  const fuente = readFileSync(join(BACKEND, donde.controlador), 'utf8');
  const constante = /String ORDEN_POR_OMISION\s*=\s*"([^"]+)"/.exec(fuente)?.[1];
  const enLinea = /aPaginacion\("([^"]+)"\)/.exec(fuente)?.[1];
  const porOmision = constante ?? enLinea;
  if (porOmision === undefined) {
    throw new Error(
      `No se encontro el orden por omision en ${donde.controlador}.\n` +
        '  Sin el, no se puede saber si lo que la barra anuncia es lo que el servidor ordena.',
    );
  }
  return porOmision;
}

describe('el orden que se OFRECE lo admite el backend (#186, AC2)', () => {
  /** Las tablas que ofrecen ordenar, con la operacion a la que viaja su `?ordenarPor=`. */
  const queOrdenan = CLAVES_DE_HOJA.flatMap((clave) =>
    tablasDe(clave)
      .filter((tabla) => tabla.orden !== undefined)
      .map((tabla) => ({
        clave,
        tabla,
        operacion:
          (CONECTORES[clave]?.parametros ?? []).find(
            (parametro) => parametro.nombre === EN_LA_RUTA.ordenarPor,
          )?.operacion ?? '',
      })),
  );

  it('EL CENTINELA: hay tablas que ofrecen orden, y el backend se pudo leer', () => {
    expect(queOrdenan.length, 'ninguna tabla declara `orden`').toBeGreaterThan(0);
    // Y que la lectura del backend no devuelve una lista vacia, que es como esto pasaria en verde
    // sin haber comprobado nada: una lista vacia no contiene ningun campo… ni lo contradice.
    for (const { operacion } of queOrdenan) {
      expect(camposAdmitidos(operacion).length, operacion).toBeGreaterThan(1);
    }
  });

  it('EL CENTINELA DE LA LECTURA: `publicandoComo` se lee, y no se regala el nombre interno', () => {
    // El caso que #228 midio, y que hasta entonces esta guarda daba por bueno **en verde**:
    // `MuestraDelProgramaRepositoryJdbc.ORDEN` lleva `.publicandoComo("sector", "sector_codigo")`,
    // que RETIRA el `camelCase` automatico. Con la lectura vieja —solo los argumentos de
    // `sobre(...)`— esta guarda admitia `sectorCodigo`, que el backend contesta con **422**
    // (`OrdenDeLaDeteccionFronteraTest`), y rechazaba `sector`, que es el que SI admite.
    //
    // Se afirma sobre una operacion concreta y no «alguna con publicandoComo»: una lista vacia no
    // contiene ningun campo… ni lo contradice, y esto tiene que poder ponerse rojo.
    const admitidos = camposAdmitidos('GET /fiscalizacion/programas/{id}/muestra');

    expect(admitidos, 'el nombre que la fila publica').toContain('sector');
    expect(
      admitidos,
      'El lector dejo de honrar `publicandoComo`: «sectorCodigo» es el camelCase automatico que\n' +
        '  esa llamada RETIRA, y pedirlo es un 422 ORDEN_NO_ADMITIDO. Dos nombres vivos para la\n' +
        '  misma columna en la misma operacion es el defecto que #546 cerro en el backend.',
    ).not.toContain('sectorCodigo');
    // La columna cruda se queda admitida, como dice el javadoc de `publicandoComo`.
    expect(admitidos).toContain('sector_codigo');
    expect(admitidos).toContain('codRefCatastral');
  });

  it('cada campo que se ofrece esta en la lista blanca de su repositorio', () => {
    const rechazados: string[] = [];
    for (const { clave, tabla, operacion } of queOrdenan) {
      const admitidos = camposAdmitidos(operacion);
      for (const campo of tabla.orden?.campos ?? []) {
        if (!admitidos.includes(campo.valor)) {
          rechazados.push(`  ${clave}/${tabla.clave} ofrece «${campo.valor}» y ${operacion} no lo admite`);
        }
      }
    }
    expect(
      rechazados,
      'Una tabla ofrece ordenar por un campo que el backend rechaza:\n' +
        `${rechazados.join('\n')}\n\n` +
        '  `OrdenSeguro` contesta **422 ORDEN_NO_ADMITIDO**, y la pantalla lo dibuja como una\n' +
        '  averia suya. El desplegable se mueve y la tabla se rompe.',
    ).toEqual([]);
  });

  it('y el PRIMERO es el orden por omision del controlador: la barra no puede mentir', () => {
    // Sin `?ordenarPor=` en la ruta el interprete anuncia `campos[0]` y el conector no manda nada,
    // asi que ordena el backend por el suyo. Si no coincidieran, la barra diria «ordenado por
    // Actividad» sobre filas ordenadas por codigo — y nadie tiene forma de notarlo.
    const descuadres: string[] = [];
    for (const { clave, tabla, operacion } of queOrdenan) {
      const primero = tabla.orden?.campos[0]?.valor ?? '';
      const porOmision = ordenPorOmision(operacion);
      if (primero !== porOmision && aCamelCase(porOmision) !== primero) {
        descuadres.push(`  ${clave}/${tabla.clave}: ofrece «${primero}» y ${operacion} ordena por «${porOmision}»`);
      }
    }
    expect(descuadres, `El primer campo no es el orden por omision:\n${descuadres.join('\n')}`).toEqual([]);
  });

  it('y los dos sentidos son los del backend: `ASCENDENTE` y `DESCENDENTE`', () => {
    // Son dato y no una constante de la libreria porque un sistema escribe `asc` y otro
    // `ASCENDENTE`. El de aqui es el de `Paginacion.Direccion`, y mandar el otro es un 422.
    for (const { clave, tabla } of queOrdenan) {
      expect(tabla.orden?.ascendente, `${clave}/${tabla.clave}`).toBe('ASCENDENTE');
      expect(tabla.orden?.descendente, `${clave}/${tabla.clave}`).toBe('DESCENDENTE');
    }
  });
});
