// @vitest-environment node
//
// Lee el contrato, `Observacion.java` y los dos enumerados de la notificacion del disco, y recorre
// las definiciones como dato. No hay DOM que necesitar.

import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

import type { DefinicionDeActo, DefinicionDePantalla, PiezaDeLaPantalla } from '@kamayuk/ui';

import { RAIZ } from './artboards.ts';
import { ACTOS_DE_LAS_HOJAS } from '../src/datos/actos.ts';
import { YA_SERVIDAS } from '../src/datos/servidas.ts';
import {
  FORMAS_DE_NOTIFICACION,
  LARGO_DE_LA_OBSERVACION,
  LO_QUE_CONTESTO_LA_DILIGENCIA,
  NOTIFICAR_LA_RESOLUCION,
  RESULTADOS_DE_LA_DILIGENCIA,
  puedeHacerlo,
} from '../src/pantallas/actos.ts';
import { CLAVES_DE_HOJA, hojaDe, type ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { bloquesDe } from '../src/pantallas/bloques.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';

/**
 * **Un acto escribe lo que su hoja declara, lo que el backend sirve y lo que la regla 10 exige**
 * (#629).
 *
 * <h2>De donde viene</h2>
 *
 * #629 trae el primer ACTO del interprete a este sistema —anular la licencia de edificacion en
 * `aut-sol`— y con el una cadena nueva de cinco eslabones en cuatro archivos, del mismo tipo que la
 * de las tablas que vigila `la-ruta-de-la-hoja-llega-al-conector`:
 *
 * <ol>
 *   <li>la definicion declara el acto por su `clave`, y una accion de un bloque lo `abre`;</li>
 *   <li>`datos/actos.ts` registra quien lo atiende con esa MISMA clave —sin el, el interprete dibuja
 *       el primario impedido «sin quien lo atienda», que es un boton que no hace nada y lo dice—;</li>
 *   <li>la escritura que manda es una operacion que la HOJA declara en `arbol.ts` —y el artboard,
 *       que `pantallas-del-artboard` compara con el arbol—: es lo que una revision lee para saber
 *       que hace una pantalla;</li>
 *   <li>esa operacion esta en `YA_SERVIDAS` y la publica el contrato;</li>
 *   <li>y la observacion tiene el largo del backend —la unica cifra suya que esta interfaz copia,
 *       ver `pantallas/actos.ts`—.</li>
 * </ol>
 *
 * Mas uno que no es de la cadena sino de lo que se ofrece: **si la escritura pide un privilegio, el
 * boton que abre el acto sale impedido sin el**. Sin eso se ofrece una puerta que contesta 403.
 *
 * <h2>La comprobacion es una funcion, y se ejerce tambien sobre una muestra que la viola</h2>
 *
 * `lasFaltasDe` recibe las hojas y el registro y devuelve lo que falta, una frase por falta. Sobre el
 * arbol de verdad tiene que devolver nada; sobre `LA_MUESTRA` —un acto sin quien lo atienda, otro
 * atendido y no dibujado, una operacion que la hoja no declara y un boton sin impedimento— tiene que
 * devolver las cinco —la operacion ajena falta dos veces: ni la hoja la declara ni esta servida—.
 * Una guarda que no puede fallar no vigila nada.
 *
 * <h2>Y desde #638, el vocabulario de lo que se elige</h2>
 *
 * El segundo acto —notificar la resolucion de un recurso, en `tra-pap`— trae dos desplegables cuyo
 * rotulo no es lo que el backend lee: la forma y el resultado viajan con el NOMBRE de su enumerado.
 * Los mapas de `pantallas/actos.ts` son la unica traduccion, y aqui se comprueba contra los `.java`
 * que sus valores sean exactamente los del enumerado: uno de mas contesta 422 al elegirlo, y uno de
 * menos es una forma de notificar que la pantalla no deja registrar.
 */

const CONTRATO = join(RAIZ, '../docs/50-api/formas-de-la-api.json');
const OBSERVACION = join(
  RAIZ,
  '../backend/kamayuk-rentas-dominio-compartido/src/main/java/kamayuk/rentas/dominio/Observacion.java',
);

/** Donde viven los dos enumerados de la diligencia (#638): del dominio compartido desde #41. */
const ENUMERADO = (nombre: string) =>
  join(RAIZ, `../backend/kamayuk-rentas-dominio-compartido/src/main/java/kamayuk/rentas/dominio/${nombre}.java`);

function leer(ruta: string): string {
  if (!existsSync(ruta)) {
    throw new Error(
      `FALTA UNA FUENTE: ${ruta}\n\n` +
        '  Esta guarda la lee para comprobar que el acto escribe lo que el backend publica. Sin ella\n' +
        '  no compara nada, y no se salta: se rompe.',
    );
  }
  return readFileSync(ruta, 'utf8');
}

const PUBLICADAS = new Set(Object.keys(JSON.parse(leer(CONTRATO)) as Record<string, unknown>));
const SERVIDAS = new Set(YA_SERVIDAS.map((o) => `${o.metodo} ${o.ruta}`));

/** Lo que la guarda necesita de una hoja: su definicion y las operaciones que declara. */
interface HojaQueSeMira {
  readonly clave: string;
  readonly definicion: DefinicionDePantalla<PiezaDeLaPantalla>;
  readonly operaciones: readonly string[];
}

/** Lo que la guarda necesita de un acto registrado. */
interface ActoQueSeMira {
  readonly operacion: string;
  readonly permiso?: unknown;
}

function actosDe(definicion: DefinicionDePantalla<PiezaDeLaPantalla>): readonly DefinicionDeActo[] {
  return definicion.bloques.filter((pieza): pieza is DefinicionDeActo => pieza.tipo === 'acto');
}

/** Las faltas de la cadena, una frase por falta. Vacia es que la cadena esta entera. */
function lasFaltasDe(
  hojas: readonly HojaQueSeMira[],
  registro: Readonly<Record<string, Readonly<Record<string, ActoQueSeMira>> | undefined>>,
): readonly string[] {
  const faltas: string[] = [];
  for (const hoja of hojas) {
    const dibujados = new Set(actosDe(hoja.definicion).map((acto) => acto.clave));
    const atendidos = registro[hoja.clave] ?? {};
    for (const clave of dibujados) {
      if (atendidos[clave] === undefined) faltas.push(`${hoja.clave} dibuja «${clave}» y nadie lo atiende`);
    }
    for (const [clave, escritura] of Object.entries(atendidos)) {
      if (!dibujados.has(clave)) faltas.push(`${hoja.clave} atiende «${clave}» y su definicion no lo dibuja`);
      if (!hoja.operaciones.includes(escritura.operacion)) {
        faltas.push(`${hoja.clave} escribe «${escritura.operacion}» y la hoja no la declara en arbol.ts`);
      }
      if (!SERVIDAS.has(escritura.operacion)) faltas.push(`«${escritura.operacion}» no esta en YA_SERVIDAS`);
      if (!PUBLICADAS.has(escritura.operacion)) faltas.push(`«${escritura.operacion}» no la publica el contrato`);
      if (escritura.permiso === undefined) continue;
      // El boton que ABRE el acto: sin el privilegio, impedido leyendo `puedeHacerlo(clave)`.
      const botones = bloquesDe(hoja.definicion).flatMap((bloque) =>
        (bloque.acciones ?? []).filter((accion) => accion.abre === clave),
      );
      const sinImpedir = botones.filter(
        (boton) =>
          !(boton.impedida ?? []).some(
            (impedimento) => impedimento.si.dato === puedeHacerlo(clave) && 'hay' in impedimento.si && !impedimento.si.hay,
          ),
      );
      if (botones.length === 0) faltas.push(`${hoja.clave}: ningun boton abre «${clave}»`);
      if (sinImpedir.length > 0) {
        faltas.push(`${hoja.clave}: el boton que abre «${clave}» no sale impedido sin el privilegio`);
      }
    }
  }
  return faltas;
}

/** Las cuarenta, como la guarda las mira. */
const LAS_HOJAS: readonly HojaQueSeMira[] = CLAVES_DE_HOJA.map((clave: ClaveDeHoja) => ({
  clave,
  definicion: pantallaDe(clave),
  operaciones: hojaDe(clave).operaciones.map((o) => `${o.verbo} ${o.ruta}`),
}));

/**
 * **La muestra que la viola**: una hoja con dos actos dibujados y uno atendido que no dibuja, cuya
 * escritura la hoja no declara, y con el boton del acto que pide permiso sin impedimento.
 */
const LA_MUESTRA: readonly HojaQueSeMira[] = [
  {
    clave: 'muestra',
    operaciones: ['POST /licencias/edificacion/{expediente}/anulacion'],
    definicion: {
      instruccion: '',
      bloques: [
        {
          titulo: 'Muestra',
          nota: '',
          campos: [],
          acciones: [{ rotulo: 'Abrir', abre: 'con-permiso' }],
        },
        {
          tipo: 'acto',
          clave: 'con-permiso',
          titulo: 'Con permiso',
          campos: [],
          observacion: { etiqueta: 'Observacion', largo: LARGO_DE_LA_OBSERVACION },
        },
        {
          tipo: 'acto',
          clave: 'sin-quien-lo-atienda',
          titulo: 'Sin quien lo atienda',
          campos: [],
          observacion: { etiqueta: 'Observacion', largo: LARGO_DE_LA_OBSERVACION },
        },
      ],
    },
  },
];

const EL_REGISTRO_DE_LA_MUESTRA = {
  muestra: {
    'con-permiso': { operacion: 'POST /licencias/edificacion/{expediente}/anulacion', permiso: {} },
    'no-dibujado': { operacion: 'POST /licencias/ciiu' },
  },
};

describe('#629 — un acto escribe lo que su hoja declara', () => {
  it('EL CENTINELA: hay al menos un acto dibujado y atendido, y el contrato se pudo leer', () => {
    // Sin esto, la de abajo recorreria cuarenta hojas sin actos y pasaria en verde sobre nada.
    const conActos = LAS_HOJAS.filter((hoja) => actosDe(hoja.definicion).length > 0);
    expect(conActos.map((hoja) => hoja.clave)).toEqual(expect.arrayContaining(['aut-sol', 'tra-pap']));
    expect(Object.keys(ACTOS_DE_LAS_HOJAS['aut-sol'] ?? {})).toEqual(['anular-licencia-de-edificacion']);
    // #638: notificar la resolucion de un recurso, desde la hoja que dibuja los actos de la papeleta.
    expect(Object.keys(ACTOS_DE_LAS_HOJAS['tra-pap'] ?? {})).toEqual(['notificar-resolucion-del-recurso']);
    expect(PUBLICADAS.size, 'el contrato llego vacio').toBeGreaterThan(50);
  });

  it('la cadena esta entera: atendido, dibujado, declarado por la hoja, servido, publicado y con su boton impedido', () => {
    const faltas = lasFaltasDe(LAS_HOJAS, ACTOS_DE_LAS_HOJAS);
    expect(
      faltas,
      'A un acto le falta un eslabon:\n' +
        `${faltas.map((falta) => `  ${falta}`).join('\n')}\n\n` +
        '  Dibujado y sin atender es un primario impedido «sin quien lo atienda»; atendido y sin\n' +
        '  dibujar es una escritura que nadie puede pulsar; y una escritura que la hoja no declara\n' +
        '  es una pantalla que hace algo que su arbol no dice. Ver el javadoc de este archivo.',
    ).toEqual([]);
  });

  it('LA MUESTRA que la viola sale roja, con sus cinco faltas', () => {
    expect(lasFaltasDe(LA_MUESTRA, EL_REGISTRO_DE_LA_MUESTRA)).toEqual([
      'muestra dibuja «sin-quien-lo-atienda» y nadie lo atiende',
      'muestra: el boton que abre «con-permiso» no sale impedido sin el privilegio',
      'muestra atiende «no-dibujado» y su definicion no lo dibuja',
      'muestra escribe «POST /licencias/ciiu» y la hoja no la declara en arbol.ts',
      '«POST /licencias/ciiu» no esta en YA_SERVIDAS',
    ]);
  });
});

describe('#629 — lo que la tarjeta de lo hecho ensena, la respuesta lo publica', () => {
  it('la anulacion contesta `nroExpediente`, `nroLicencia` y `resolucion.numero`, que es lo que se ensena', () => {
    // Lo que `datos/actos.ts` lee de `ActoDeEdificacion` para decir que resolucion salio. Con un
    // campo que el contrato no publica, la tarjeta diria la raya del dato ausente despues de haber
    // anulado de verdad: el acto hecho, y la pantalla sin poder decir con que papel.
    const formas = JSON.parse(leer(CONTRATO)) as Record<string, Record<string, unknown>>;
    const forma = formas['POST /licencias/edificacion/{expediente}/anulacion'] ?? {};
    expect(Object.keys(forma)).toEqual(expect.arrayContaining(['nroExpediente', 'nroLicencia', 'resolucion']));
    expect(Object.keys((forma.resolucion ?? {}) as Record<string, unknown>)).toContain('numero');
  });

  it('#638: la diligencia contesta `numero`, `resolucion`, `direccion` y `resultado`, que es lo que se ensena', () => {
    // Lo que `datos/actos.ts` lee de `DiligenciaDeUnaResolucion` para la tarjeta de lo hecho: el
    // titulo sale del `resultado` y el texto nombra la cedula, la resolucion y donde se diligencio.
    const formas = JSON.parse(leer(CONTRATO)) as Record<string, Record<string, unknown>>;
    const forma = formas['POST /transito/descargos/{nDeExpediente}/resolucion/notificacion'] ?? {};
    const leidos = Object.values(LO_QUE_CONTESTO_LA_DILIGENCIA).map((nombre) => nombre.replace(/^diligencia\./, ''));
    expect(Object.keys(forma)).toEqual(expect.arrayContaining(leidos));
  });
});

describe('#638 — la forma y el resultado viajan con el nombre del enumerado del backend', () => {
  /** Las constantes de un `enum` de Java, en su orden. */
  const constantesDe = (nombre: string): readonly string[] => {
    const cuerpo = /public enum \w+ \{([\s\S]*?)(;|\})/.exec(leer(ENUMERADO(nombre)))?.[1] ?? '';
    return [...cuerpo.replace(/\/\*\*[\s\S]*?\*\//g, '').matchAll(/\b([A-Z][A-Z_]+)\b/g)].map((c) => c[1] ?? '');
  };

  it('EL CENTINELA: los dos `.java` se leen y traen constantes', () => {
    // Sin esto, un patron que dejara de casar compararia dos listas vacias y pasaria en verde.
    expect(constantesDe('ModalidadDeNotificacion')).toContain('PERSONAL');
    expect(constantesDe('ResultadoDeNotificacion')).toContain('NO_UBICADO');
  });

  it('`FORMAS_DE_NOTIFICACION` manda exactamente las de `ModalidadDeNotificacion`', () => {
    expect([...Object.values(FORMAS_DE_NOTIFICACION)].sort()).toEqual([...constantesDe('ModalidadDeNotificacion')].sort());
  });

  it('`RESULTADOS_DE_LA_DILIGENCIA` manda exactamente las de `ResultadoDeNotificacion`, y lo hecho dice las tres', () => {
    const delBackend = [...constantesDe('ResultadoDeNotificacion')].sort();
    expect([...Object.values(RESULTADOS_DE_LA_DILIGENCIA)].sort()).toEqual(delBackend);
    // El titulo de lo hecho sale `segun` el resultado que contesto el backend: un caso de menos lo
    // diria con el `otro` generico, y el que decide si el plazo corre es justo ese.
    const acto = actosDe(pantallaDe('tra-pap')).find((a) => a.clave === NOTIFICAR_LA_RESOLUCION);
    const titulo = acto?.hecho?.titulo;
    expect(typeof titulo === 'object' && 'casos' in titulo ? Object.keys(titulo.casos).sort() : []).toEqual(delBackend);
  });
});

describe('#629 — la observacion de un acto tiene el largo del backend (regla 10)', () => {
  const observacion = leer(OBSERVACION);
  const cifra = (nombre: string): number | undefined => {
    const casa = new RegExp(`int ${nombre} = (\\d+);`).exec(observacion);
    return casa?.[1] === undefined ? undefined : Number(casa[1]);
  };

  it('`LARGO_DE_LA_OBSERVACION` es `LARGO_MINIMO` y `LARGO_MAXIMO` de `Observacion.java`', () => {
    // Sin casar, la comparacion seria contra `undefined` y el mensaje no diria por que.
    expect(cifra('LARGO_MINIMO'), 'el patron ya no encuentra `LARGO_MINIMO` en `Observacion.java`').toBeDefined();
    expect(LARGO_DE_LA_OBSERVACION).toEqual({ minimo: cifra('LARGO_MINIMO'), maximo: cifra('LARGO_MAXIMO') });
  });

  it('y todo acto dibujado usa ESE largo, y no uno escrito a mano', () => {
    const otros = LAS_HOJAS.flatMap((hoja) =>
      actosDe(hoja.definicion)
        .filter((acto) => acto.observacion.largo !== LARGO_DE_LA_OBSERVACION)
        .map((acto) => `  ${hoja.clave} · ${acto.clave}`),
    );
    expect(otros, `Actos cuyo largo de la observacion no es LARGO_DE_LA_OBSERVACION:\n${otros.join('\n')}`).toEqual(
      [],
    );
  });
});
