// @vitest-environment node
//
// Lee el contrato del disco. No hay DOM que necesitar.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import type { DefinicionDeTabla } from '@kamayuk/ui';

import { CONECTORES } from '../src/datos/conectores.ts';
import { YA_SERVIDAS } from '../src/datos/servidas.ts';
import { CATALOGO } from '../src/catalogo.ts';
import { CLAVES_DE_HOJA, type ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { bloquesDe } from '../src/pantallas/bloques.ts';
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
 * <h2>La lista blanca del orden sale del CONTRATO, y hasta #227 no podia</h2>
 *
 * Hasta ese issue el contrato publicaba que existe `?ordenarPor=` y no que valores admite, asi que
 * esta guarda abria los `.java` del backend —`ORDEN_DEL_BACKEND`, con la ruta del repositorio y el
 * nombre de su constante— para comprobar que el orden que una tabla ofrece no contesta **422
 * ORDEN_NO_ADMITIDO**. Funcionaba, pero ataba una guarda del frontend a rutas de archivo del
 * backend: un acoplamiento al reves, que no sobreviviria a separar `rentas-web`.
 *
 * Desde #227 lo publica `parametros-de-la-api.json`, por operacion y **derivado**: el backend
 * recorre el bytecode desde cada handler hasta el `OrdenSeguro` que su paginacion alcanza, y
 * escribe `orden: { porOmision, admitidos }`. Esta guarda lee eso y nada mas. Una operacion que
 * ofrezca orden y no lo publique es roja aqui, no un verde silencioso.
 *
 * **Y con eso se cae sola la trampa de #228.** Aquella lectura sacaba los argumentos de
 * `OrdenSeguro.sobre(...)` con una expresion regular, y por eso se le escapaba `publicandoComo`
 * —que declara con que nombre publica el recurso una columna y **retira el `camelCase` automatico
 * de esa columna**—: la guarda daba por bueno el nombre que el backend RECHAZA y por malo el que
 * ADMITE. El contrato no puede equivocarse ahi porque no interpreta la cadena: llama a
 * `OrdenSeguro.camposAdmitidos()`, que es el mapa que el propio `clausula(...)` consulta. El
 * centinela de aquel issue se conserva abajo, leyendo el contrato.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');
const RAIZ = join(FRONTEND, '..');
const PARAMETROS = join(RAIZ, 'docs/50-api/parametros-de-la-api.json');

interface OrdenDeUnaOperacion {
  /** Por que campo ordena el backend cuando no se manda `?ordenarPor=`. */
  readonly porOmision: string;
  /** Los que `?ordenarPor=` admite; cualquier otro es 422 ORDEN_NO_ADMITIDO. */
  readonly admitidos: readonly string[];
}

interface ParametrosDeUnaOperacion {
  readonly obligatorios: readonly string[];
  readonly opcionales: readonly string[];
  readonly orden?: OrdenDeUnaOperacion;
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
  return bloquesDe(pantallaDe(clave)).flatMap((bloque) => {
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
 * `"fecha_ingreso"` -> `"fechaIngreso"`, como lo hace `OrdenSeguro.sobre`.
 *
 * Hace falta para el orden POR OMISION: el contrato publica el literal que el controlador escribe,
 * y unos escriben el campo del recurso —`fechaVisita`— y otros la columna —`codigo_contribuyente`—.
 * Traducirlo en el backend seria publicar un nombre que su controlador no dice.
 */
const aCamelCase = (columna: string): string =>
  columna.replace(/_([a-z0-9])/g, (_todo, letra: string) => letra.toUpperCase());

/**
 * Lo que el contrato publica del orden de esa operacion, o revienta diciendo que falta.
 *
 * `admitidos` trae los dos nombres de cada columna —el `camelCase` que el recurso publica y el
 * `snake_case` de la tabla—, porque `OrdenSeguro.sobre` admite los dos y el contrato dice lo que
 * el servidor acepta, no lo que prefeririamos que aceptara.
 */
function ordenDelBackend(operacion: string): OrdenDeUnaOperacion {
  const orden = contrato[operacion]?.orden;
  if (orden === undefined) {
    throw new Error(
      `Una tabla ofrece ordenar y «${operacion}» no publica su lista blanca en\n` +
        '  `docs/50-api/parametros-de-la-api.json`. Eso significa una de dos, y las dos hay que\n' +
        '  mirarlas: o la operacion pagina sin que `?ordenarPor=` mande en su ORDER BY —esta\n' +
        '  declarada en `ParametrosDeLaApiTest#SIN_ORDEN_PEDIBLE`, y entonces ofrecer orden aqui\n' +
        '  seria dibujar un mando que no mueve las filas—, o el contrato se quedo sin regenerar.',
    );
  }
  return orden;
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

  it('EL CENTINELA: hay tablas que ofrecen orden, y el contrato publica su lista', () => {
    expect(queOrdenan.length, 'ninguna tabla declara `orden`').toBeGreaterThan(0);
    // Y que la lista que el contrato publica no viene vacia, que es como esto pasaria en verde
    // sin haber comprobado nada: una lista vacia no contiene ningun campo… ni lo contradice.
    for (const { operacion } of queOrdenan) {
      expect(ordenDelBackend(operacion).admitidos.length, operacion).toBeGreaterThan(1);
    }
  });

  it('EL CENTINELA DE #228: `publicandoComo` llega al contrato, y el nombre interno NO', () => {
    // El caso que #228 midio, y que la lectura por expresion regular daba por bueno **en verde**:
    // `MuestraDelProgramaRepositoryJdbc.ORDEN` lleva `.publicandoComo("sector", "sector_codigo")`,
    // que RETIRA el `camelCase` automatico. Pedir `?ordenarPor=sectorCodigo` es un **422**
    // (`OrdenDeLaDeteccionFronteraTest` lo prueba del otro lado), y `sector` es el que si admite.
    //
    // Se afirma sobre una operacion concreta y no «alguna con publicandoComo»: una lista vacia no
    // contiene ningun campo… ni lo contradice, y esto tiene que poder ponerse rojo.
    const admitidos = ordenDelBackend('GET /fiscalizacion/programas/{id}/muestra').admitidos;

    expect(admitidos, 'el nombre que la fila publica').toContain('sector');
    expect(
      admitidos,
      'El contrato regala el nombre interno: «sectorCodigo» es el camelCase automatico que\n' +
        '  `publicandoComo` RETIRA, y pedirlo es un 422 ORDEN_NO_ADMITIDO. Dos nombres vivos para\n' +
        '  la misma columna en la misma operacion es el defecto que #546 cerro en el backend.',
    ).not.toContain('sectorCodigo');
    // La columna cruda se queda admitida, como dice el javadoc de `publicandoComo`.
    expect(admitidos).toContain('sector_codigo');
    expect(admitidos).toContain('codRefCatastral');
  });

  it('cada campo que se ofrece esta en la lista blanca que el contrato publica', () => {
    const rechazados: string[] = [];
    for (const { clave, tabla, operacion } of queOrdenan) {
      const admitidos = ordenDelBackend(operacion).admitidos;
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

  it('y el PRIMERO es el orden por omision que el contrato publica: la barra no puede mentir', () => {
    // Sin `?ordenarPor=` en la ruta el interprete anuncia `campos[0]` y el conector no manda nada,
    // asi que ordena el backend por el suyo. Si no coincidieran, la barra diria «ordenado por
    // Actividad» sobre filas ordenadas por codigo — y nadie tiene forma de notarlo.
    const descuadres: string[] = [];
    for (const { clave, tabla, operacion } of queOrdenan) {
      const primero = tabla.orden?.campos[0]?.valor ?? '';
      const porOmision = ordenDelBackend(operacion).porOmision;
      if (primero !== porOmision && aCamelCase(porOmision) !== primero) {
        descuadres.push(`  ${clave}/${tabla.clave}: ofrece «${primero}» y ${operacion} ordena por «${porOmision}»`);
      }
    }
    expect(descuadres, `El primer campo no es el orden por omision:\n${descuadres.join('\n')}`).toEqual([]);
  });

  it('y los dos sentidos son los del backend: `ASCENDENTE` y `DESCENDENTE`', () => {
    // Son dato y no una constante de la libreria porque un sistema escribe `asc` y otro
    // `ASCENDENTE`. El de aqui es el de `Paginacion.Sentido`, y mandar el otro es un 422.
    for (const { clave, tabla } of queOrdenan) {
      expect(tabla.orden?.ascendente, `${clave}/${tabla.clave}`).toBe('ASCENDENTE');
      expect(tabla.orden?.descendente, `${clave}/${tabla.clave}`).toBe('DESCENDENTE');
    }
  });
});
