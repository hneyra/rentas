// @vitest-environment node
//
// Lee el contrato del disco y las definiciones del arbol. No hay DOM que necesitar.

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { ARBOL, type ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { bloquesDe } from '../src/pantallas/bloques.ts';
import { PANTALLAS } from '../src/pantallas/definiciones/index.ts';
import type { Modulo } from '../src/pantallas/tipos.ts';

/**
 * **Un mando pregunta por algo que su operacion admite** (#244).
 *
 * <h2>El hueco que lo trae</h2>
 *
 * `val-tip` declaraba tres desplegables y **dos preguntaban lo que `GET /coactiva/prescripcion` no
 * admite**: «Tipo de valor» —Orden de pago, RD, RM—, que no es un filtro de esta operacion ni un
 * campo de sus filas, y «Estado» —Vigente, Por prescribir, Prescrito—, que confundia la situacion
 * del ejercicio con `?resultado=`, que es como se resolvio la SOLICITUD.
 *
 * Ese defecto **no rompe nada mientras el mando no se cablea**, y cuando se cable no da error
 * tampoco: da una lista filtrada por algo distinto de lo que el rotulo dice. Es lo que #169, #173
 * y #184 encontraron tres veces, y lo que #226 midio con un `?direccion=` que significaba dos
 * cosas. Un rotulo no se puede compilar; por eso se declara aqui **contra que parametro va**, y se
 * cruza con lo que el contrato publica.
 *
 * <h2>La tabla se escribe a mano, y el centinela es la mitad que muerde</h2>
 *
 * Derivarla del contrato la haria pasar diga lo que diga. Escrita, lo que caza un mando nuevo
 * —o uno que vuelva— es la comprobacion de COBERTURA: **todo mando de una hoja listada tiene que
 * estar aqui**, asi que anadir «Tipo de valor» otra vez sale rojo nombrandolo, sin que nadie se
 * acuerde de tocar esta prueba.
 *
 * <h2>Lo que esto NO dice</h2>
 *
 * No dice que el mando este CABLEADO. Los de `val-tip` no lo estan —eso es #172—: lo que se
 * declara aqui es **que pregunta cada uno**, que es la decision que #244 tomo y la que se queda
 * vieja sin que nada lo diga. Que lo tecleado llegue al cable es otra guarda y otro issue.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const PARAMETROS = join(AQUI, '../../docs/50-api/parametros-de-la-api.json');

interface ParametrosDeUnaOperacion {
  readonly obligatorios: readonly string[];
  readonly algunoDeEstos: readonly (readonly string[])[];
  readonly condicionales: readonly string[];
  readonly opcionales: readonly string[];
  readonly enElCuerpo: readonly string[];
}

const contrato = JSON.parse(readFileSync(PARAMETROS, 'utf8')) as Record<
  string,
  ParametrosDeUnaOperacion
>;

/**
 * **Que pregunta cada mando, y contra que parametro de su operacion va.**
 *
 * Una entrada por hoja decidida, y dentro una por mando —los campos de solo lectura no son mandos:
 * no preguntan nada—. El valor es el nombre del parametro **tal como el contrato lo publica**.
 */
const LO_QUE_PREGUNTA: Readonly<
  Partial<Record<ClaveDeHoja, Readonly<Record<string, string>>>>
> = {
  // #244. Los tres, con su motivo en `definiciones/valores.ts` y en el artboard.
  'val-tip': {
    // Era «Tipo de valor», que esta operacion no admite y sus filas no llevan.
    Tributo: 'tributo',
    // Era «Ejercicio» a secas, al lado de una columna que se llama igual y significa otra cosa:
    // `?ejercicio=` acota por el RANGO SOLICITADO (`CriterioDePrescripciones`).
    'Ejercicio solicitado': 'ejercicio',
    // Era «Estado» —Vigente, Por prescribir, Prescrito—. Lo que se admite es como se resolvio la
    // solicitud, y la situacion del ejercicio no tiene filtro.
    'Resultado de la solicitud': 'resultado',
  },
};

/** El `GET` que la hoja declara en el arbol, en la forma en que el contrato la nombra. */
function operacionDe(clave: ClaveDeHoja): string {
  const hoja = (ARBOL as readonly Modulo[])
    .flatMap((modulo) => modulo.hojas)
    .find((h) => h.clave === clave);
  if (hoja === undefined) throw new Error(`el arbol no declara la hoja «${clave}»`);
  const get = hoja.operaciones.find((operacion) => operacion.verbo === 'GET');
  if (get === undefined) throw new Error(`la hoja «${clave}» no declara ningun GET`);
  return `GET ${get.ruta}`;
}

/** Los mandos de una hoja: todo campo que no sea de solo lectura. */
function mandosDe(clave: ClaveDeHoja): readonly string[] {
  return bloquesDe(PANTALLAS[clave]).flatMap((bloque) =>
    bloque.campos.filter((campo) => !campo.tipo.startsWith('r')).map((campo) => campo.etiqueta),
  );
}

const DECIDIDAS = Object.keys(LO_QUE_PREGUNTA) as readonly ClaveDeHoja[];

describe('los mandos de una hoja decidida preguntan lo que su operacion admite', () => {
  it('EL CENTINELA: hay hojas decididas, y el contrato trae sus operaciones', () => {
    // Sin esto, una tabla vacia —o una ruta mal calculada— dejaria lo de abajo recorriendo la nada
    // y pasando en verde. Es como este repositorio se quedo sin guarda dos veces (#78, #80).
    expect(DECIDIDAS.length, 'no hay ni una hoja decidida').toBeGreaterThan(0);
    expect(Object.keys(contrato).length, 'el contrato vino vacio').toBeGreaterThan(20);
    for (const clave of DECIDIDAS) {
      expect(contrato[operacionDe(clave)], `el contrato no publica ${operacionDe(clave)}`).toBeDefined();
    }
  });

  for (const clave of DECIDIDAS) {
    const declarado = LO_QUE_PREGUNTA[clave] ?? {};

    it(`«${clave}»: ningun mando pregunta algo que la operacion no admite`, () => {
      const operacion = operacionDe(clave);
      const publicados = contrato[operacion];
      if (publicados === undefined) throw new Error(`el contrato no publica ${operacion}`);
      const admitidos = new Set([
        ...publicados.obligatorios,
        ...publicados.condicionales,
        ...publicados.opcionales,
        ...publicados.algunoDeEstos.flat(),
      ]);
      const inventados = Object.entries(declarado)
        .filter(([, parametro]) => !admitidos.has(parametro))
        .map(([etiqueta, parametro]) => `  «${etiqueta}» dice ir a «?${parametro}=»`);
      expect(
        inventados,
        `Hay mandos de «${clave}» apuntando a un parametro que ${operacion} NO admite:\n` +
          `${inventados.join('\n')}\n\n` +
          `  Lo que admite: ${[...admitidos].sort().join(', ')}.\n` +
          '  Cablear un mando a un parametro que se PARECE no da error: da una lista filtrada por\n' +
          '  algo distinto de lo que su rotulo dice (#169, #173, #184, #226).',
      ).toEqual([]);
    });

    it(`«${clave}»: y no hay ningun mando sin decidir`, () => {
      // La mitad que muerde sola: un mando nuevo —o uno que vuelva— sale rojo nombrandose, sin que
      // nadie se acuerde de venir a escribirlo aqui.
      const sinDecidir = mandosDe(clave).filter((etiqueta) => declarado[etiqueta] === undefined);
      expect(
        sinDecidir,
        `Hay mandos de «${clave}» que no dicen que preguntan:\n` +
          `${sinDecidir.map((e) => `  «${e}»`).join('\n')}\n\n` +
          '  Un mando es un control: o va contra un parametro que la operacion admite, o no es un\n' +
          '  mando de esta hoja. Se decide en el artboard y en la definicion a la vez.',
      ).toEqual([]);
    });
  }
});
