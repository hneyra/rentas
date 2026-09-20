// @vitest-environment node
//
// Lee `lecturas.ts` del disco y lo parsea con el compilador de TypeScript, y lee el contrato. No
// hay DOM que necesitar.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import ts from 'typescript';
import { describe, expect, it } from 'vitest';

import { YA_SERVIDAS } from '../src/datos/servidas.ts';
import {
  fuentesDeLosConectores,
  modulosDelArbol,
  modulosQueElRepartoImporta,
} from './los-conectores-del-arbol.ts';

/**
 * **Una lectura declara TODO lo que su operacion publica** (#239).
 *
 * <h2>El hueco que lo trae, y cuanto duro</h2>
 *
 * `GET /fiscalizacion/actas` publica `areaDeclarada`, `usoDeclarado` y `diferenciaDeArea` desde
 * #191 y `contribuyente` y `codContribuyente` desde #216. `ActaDeFiscalizacion` declaraba **trece
 * campos y ninguno de los cinco**, de modo que `contrasteDelActa` dibujaba la raya en «Declarado» y
 * «Diferencia» con la nota «Ninguna operacion del contrato publica este dato» — que **ya no era
 * verdad**. Y el javadoc de `FIS_ACTAS` lo afirmaba por escrito.
 *
 * Ese defecto **no rompe nada**: la pantalla se pinta, las pruebas pasan, y lo que se lee es una
 * mentira con formato —un hueco que manda a arreglar un backend que ya lo arreglo—. Solo se ve
 * comparando las dos listas, que es lo que hace esto.
 *
 * <h2>Por que el cruce es por TIPO y no por conector</h2>
 *
 * Porque lo que se queda viejo es el **tipo**: mientras el campo no este declarado, el conector no
 * puede leerlo aunque quiera —no compila—, asi que el tipo es el sitio donde el desfase empieza.
 * Que un campo declarado llegue a una celda es otra cosa y no siempre es lo correcto: la regla de
 * `conectores.ts` prohibe calcular un agregado sobre la pagina que llego aunque los sumandos esten.
 *
 * <h2>La tabla se escribe A MANO, y es a proposito</h2>
 *
 * Derivarla del javadoc de cada interfaz —«de `GET /fiscalizacion/actas`»— se probo y **da falsos
 * rojos**: los tipos anidados heredan la mencion del de arriba y salen comparados contra la forma
 * entera. Y derivarla de los conectores la haria pasar diga lo que diga. Escrita, un tipo nuevo que
 * nadie anada aqui lo caza el centinela de abajo, que exige que la tabla cubra **todas** las
 * lecturas que los conectores piden.
 *
 * <h2>Pero la lista de CONECTORES no se escribe a mano, y aqui esta por que (#277)</h2>
 *
 * Lo estuvo, y se quedo vieja: enumeraba siete modulos y le faltaba `valores`, que existe desde
 * que se separo `val-tip`. **Nadie se entero**, porque el unico tipo que `valores.ts` pide
 * —`PrescripcionDeclarada`— lo pide tambien `coactiva.ts`, que si estaba: el centinela seguia
 * cuadrando sobre un conjunto al que le faltaba un archivo entero. Eso es lo peor que le puede
 * pasar a esta guarda, porque el centinela de abajo es **todo** lo que la ata al arbol: si un
 * conector no se lee, ni sus tipos entran en la comparacion ni su ausencia se nombra.
 *
 * Asi que el conjunto sale del disco (`los-conectores-del-arbol.ts`), y **con su propio
 * centinela**: la derivacion no puede encoger en silencio, porque se cruza con la que el reparto
 * declara en sus `import`.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');
const FORMAS = join(FRONTEND, '../docs/50-api/formas-de-la-api.json');
const LECTURAS = join(FRONTEND, 'src/datos/lecturas.ts');

/**
 * **Que tipo de `lecturas.ts` es la forma de que operacion del contrato.**
 *
 * Una entrada por lectura que algun conector pide. Las de una operacion paginada se comparan contra
 * `contenido[0]`, que es la forma de una fila.
 */
const LA_FORMA_DE: Readonly<Record<string, string>> = {
  ActaDeFiscalizacion: 'GET /fiscalizacion/actas',
  ConstanciaDeNoAdeudo: 'GET /consultas/constancias/no-adeudo',
  CorridaDelPredial: 'GET /rentas/predial/corridas/ultima',
  DeterminacionGuardada: 'GET /rentas/predial/determinaciones',
  DeudaConBeneficio: 'GET /consultas/deudas-con-beneficio',
  DeudaEnCoactiva: 'GET /coactiva/deudas',
  EmbudoDelPrograma: 'GET /fiscalizacion/programas/{id}/embudo',
  ExpedienteDeLaPapeleta: 'GET /transito/papeletas/{numero}/actos',
  FichaUnificada: 'GET /consultas/unificada',
  FilaDeLaMuestra: 'GET /fiscalizacion/programas/{id}/muestra',
  GiroCiiu: 'GET /licencias/ciiu',
  IndicadorDeRecaudacion: 'GET /indicadores/recaudacion',
  InternamientoEnDeposito: 'GET /transito/internamientos',
  LicenciaDeFuncionamiento: 'GET /licencias/funcionamiento',
  LiquidacionDeCostas: 'GET /coactiva/liquidaciones-costas',
  MovimientoDeLaBitacora: 'GET /seguridad/auditoria',
  PapeletaDeTransito: 'GET /transito/papeletas',
  PrescripcionDeclarada: 'GET /coactiva/prescripcion',
  ProcesoDelExpediente: 'GET /coactiva/expedientes/{numero}/proceso',
  ProgramaDeFiscalizacion: 'GET /fiscalizacion/programas',
  ResolucionDeDeterminacion: 'GET /fiscalizacion/resoluciones/{numero}',
  ResolucionEnLaRelacion: 'GET /fiscalizacion/resoluciones',
  ResumenDePapeletas: 'GET /transito/reportes/resumen-papeletas',
  TrabajoParado: 'GET /indicadores/trabajo-parado',
  VehiculoServido: 'GET /rentas/vehiculos/{placa}',
};

/**
 * **Lo que un tipo NO declara a proposito**, con su motivo.
 *
 * Son dos, y las dos son «lo que ninguna pantalla lee todavia», nunca «lo que se nos paso». Una
 * excepcion sin motivo escrito es como esta guarda se acaba vaciando.
 */
const DECLARADO_QUE_NO_SE_DECLARA: Readonly<Record<string, readonly string[]>> = {
  // Las seis secciones paginadas de la ficha unificada. El contrato las publica y `con-panel` **no
  // tiene ni una tabla** donde dibujarlas: declarar aqui seis tipos de fila seria escribir la forma
  // de lo que ninguna pantalla lee. Esta escrito en el javadoc del propio tipo.
  FichaUnificada: [
    'deudasPendientes',
    'pagosRealizados',
    'altasYBajas',
    'fraccionamientos',
    'valores',
    'declaracionesJuradas',
  ],
  // `coa-panel` dibuja **un** campo de cinco, y los otros cuatro dicen «no publicado» porque
  // contarlos sobre la pagina que llego daria un numero indistinguible de uno real (la regla de
  // `conectores.ts`). Los tres campos de aqui son justamente los que invitarian a esa cuenta:
  // `ultimaActuacion.acto` daria «con REC notificada» y «con medida cautelar» sobre veinte filas de
  // cientos. Declararlos seria poner el sumando delante de quien tiene prohibido sumarlo.
  DeudaEnCoactiva: ['tributos', 'ultimaActuacion', 'beneficios'],
};

/** Las propiedades declaradas por cada `export interface` de `lecturas.ts`. */
function camposDeclarados(): ReadonlyMap<string, readonly string[]> {
  const fuente = readFileSync(LECTURAS, 'utf8');
  const arbol = ts.createSourceFile('lecturas.ts', fuente, ts.ScriptTarget.Latest, true);
  const salida = new Map<string, readonly string[]>();
  for (const sentencia of arbol.statements) {
    if (!ts.isInterfaceDeclaration(sentencia)) continue;
    salida.set(
      sentencia.name.text,
      sentencia.members.filter(ts.isPropertySignature).map((uno) => uno.name.getText(arbol)),
    );
  }
  return salida;
}

/** La forma de UNA respuesta: la del objeto, o la de una fila si la operacion pagina. */
function formaDe(clave: string): Readonly<Record<string, unknown>> {
  const contrato = JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, unknown>;
  const suya = contrato[clave];
  if (suya === undefined) throw new Error(`El contrato no publica «${clave}»`);
  const forma = suya as Record<string, unknown>;
  const contenido = forma['contenido'];
  if (Array.isArray(contenido)) return contenido[0] as Record<string, unknown>;
  return forma;
}

/** Los tipos que los conectores piden, por las cuatro puertas que hay para pedirlos. */
function tiposQueSePiden(): readonly string[] {
  const nombres = new Set<string>();
  for (const archivo of fuentesDeLosConectores()) {
    const texto = readFileSync(archivo, 'utf8');
    for (const uno of texto.matchAll(/\bpedir(?:UnoOVacio|Uno|Pagina|Lista)<(\w+)>/g)) {
      nombres.add(uno[1] ?? '');
    }
  }
  return [...nombres].sort();
}

describe('una lectura declara lo que su operacion publica (#239)', () => {
  it('EL CENTINELA DEL CONJUNTO: los conectores salen del disco, y no encogen en silencio', () => {
    // Este va primero porque los otros tres se miden SOBRE el: un conjunto que encoge los deja
    // comparando de menos y en verde, que es lo que paso con `valores` entre #230 y #277.
    //
    // Las dos derivaciones son independientes —una lee el directorio, la otra los `import` del
    // reparto— y por eso el cruce muerde por los dos lados: un modulo que el filtro deje de casar
    // desaparece de la primera, y uno que nadie ate a una hoja no aparece en la segunda.
    const delArbol = modulosDelArbol();
    expect(delArbol.length, 'el arbol de conectores vino vacio: no hay nada que leer').toBeGreaterThan(0);
    expect(
      delArbol,
      'Los conectores del disco no son los que `src/datos/conectores.ts` importa.\n\n' +
        '  Si falta uno del lado del arbol: el filtro de `los-conectores-del-arbol.ts` dejo de\n' +
        '  casarlo, y sus tipos han dejado de compararse contra el contrato SIN que nada se ponga\n' +
        '  rojo — que es como esta guarda se apaga sola.\n' +
        '  Si falta uno del lado del reparto: hay un conector que ninguna hoja pide.',
    ).toEqual(modulosQueElRepartoImporta());
  });

  it('EL CENTINELA: la tabla cubre todas las lecturas que los conectores piden', () => {
    // Sin esto, un tipo nuevo que nadie anadiera aqui se quedaria sin comparar para siempre — que
    // es como una barrera se apaga sin que nadie la borre (#78, #80). Y al reves: una entrada que
    // ya no pide nadie es una linea que se queda vieja sin dar rojo.
    expect(tiposQueSePiden()).toEqual(Object.keys(LA_FORMA_DE).sort());
  });

  it('EL CENTINELA: y cada entrada resuelve a un tipo y a una operacion SERVIDA', () => {
    const declarados = camposDeclarados();
    const servidas = new Set(YA_SERVIDAS.map((una) => `${una.metodo} ${una.ruta}`));
    for (const [tipo, clave] of Object.entries(LA_FORMA_DE)) {
      expect(declarados.get(tipo), `«${tipo}» no es una interfaz de lecturas.ts`).toBeDefined();
      expect(servidas.has(clave), `«${clave}» no esta en YA_SERVIDAS`).toBe(true);
      expect(Object.keys(formaDe(clave)).length, `«${clave}» vino sin campos`).toBeGreaterThan(0);
    }
  });

  it('LA IGUALDAD: ningun campo publicado se queda sin declarar', () => {
    const declarados = camposDeclarados();
    const desfases: string[] = [];
    for (const [tipo, clave] of Object.entries(LA_FORMA_DE)) {
      const mios = new Set(declarados.get(tipo) ?? []);
      const aposta = new Set(DECLARADO_QUE_NO_SE_DECLARA[tipo] ?? []);
      const faltan = Object.keys(formaDe(clave)).filter(
        (campo) => !mios.has(campo) && !aposta.has(campo),
      );
      if (faltan.length > 0) desfases.push(`  ${tipo} (${clave}): ${faltan.join(', ')}`);
    }
    expect(
      desfases,
      'El backend publica campos que la lectura de la interfaz no declara:\n' +
        `${desfases.join('\n')}\n\n` +
        '  Mientras no esten declarados, ningun conector puede leerlos —no compila— y la celda\n' +
        '  que los ensenaria sigue diciendo «no publicado» de un dato que SI se publica. Eso no\n' +
        '  rompe nada: se lee como una mentira con formato, y manda a arreglar un backend que ya\n' +
        '  lo arreglo. Fue el defecto de `ActaDeFiscalizacion` entre #191 y #239.\n\n' +
        '  Se corrige declarando el campo, o anadiendolo a `DECLARADO_QUE_NO_SE_DECLARA` CON SU\n' +
        '  MOTIVO — nunca ampliando la excepcion a ojo.',
    ).toEqual([]);
  });

  it('y la excepcion no tapa un campo que ya esta declarado', () => {
    // Una excepcion que sobra es una excepcion que ya no protege de nada y que oculta el dia que
    // el campo se retire del contrato.
    const declarados = camposDeclarados();
    for (const [tipo, campos] of Object.entries(DECLARADO_QUE_NO_SE_DECLARA)) {
      const mios = new Set(declarados.get(tipo) ?? []);
      const publicados = new Set(Object.keys(formaDe(LA_FORMA_DE[tipo] ?? '')));
      for (const campo of campos) {
        expect(mios.has(campo), `«${tipo}.${campo}» ya esta declarado: sobra la excepcion`).toBe(
          false,
        );
        expect(publicados.has(campo), `«${tipo}.${campo}» ya no lo publica nadie`).toBe(true);
      }
    }
  });
});
