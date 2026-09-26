import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { RAIZ } from './artboards.ts';
import { ACCESOS_MEDIDOS } from '../src/datos/seguridadMedida.ts';
import { OPCIONES_QUE_LEEN_EL_CATALOGO } from '../src/datos/useCatalogoPermitido.ts';
import { CAMBIAR_EL_EJERCICIO } from '../src/permisos.ts';

/**
 * **Las dos opciones que la pantalla del 403 nombra son las que el backend pide y siembra** (#311).
 *
 * <h2>Por que hace falta</h2>
 *
 * Cuando `GET /seguridad/{modulos,accesos}` contesta 403 `SIN_PRIVILEGIO`, la pantalla nombra las
 * opciones que faltan. Esos nombres **no se pueden leer en ese momento** —los publica `GET
 * /seguridad/accesos`, que es justo la que fallo—, asi que estan escritos en
 * `OPCIONES_QUE_LEEN_EL_CATALOGO`. Escritos sin esta guarda, el dia que el backend cambie la opcion
 * que pide una de las dos, o el catalogo la renombre, la pantalla seguiria mandando a pedir una
 * opcion que no existe — y en verde.
 *
 * <h2>Lee las dos fuentes del backend, y la captura como tercera</h2>
 *
 * · **Que codigo pide cada lectura**: el `@RequiereAcceso` que sigue a su `@GetMapping` en
 *   `SeguridadController.java`.
 * · **Que nombre tiene ese codigo**: la fila de `docs/10-negocio/catalogo-de-opciones.md`, que es lo
 *   que `SembradorDelCatalogo` lee (via `CatalogoDeOpciones`) y mete en `acceso.nombre`.
 * · **Y lo que la instalacion contesto**: la captura de `datos/seguridadMedida.ts`, que es la
 *   respuesta de `GET /seguridad/accesos` por `curl`. Si las dos primeras cuadran y esta no, el
 *   catalogo se cambio despues de sembrar, y eso tambien hay que saberlo.
 */

const CONTROLADOR = join(
  RAIZ,
  '../backend/kamayuk-rentas-seguridad/src/main/java/kamayuk/rentas/seguridad/infraestructura/web/SeguridadController.java',
);
const CATALOGO_DE_OPCIONES = join(RAIZ, '../docs/10-negocio/catalogo-de-opciones.md');
/** Donde vive `PUT /seguridad/sesion/ejercicio`, la escritura del mando de la barra (#391). */
const CONTROLADOR_DE_LA_SESION = join(
  RAIZ,
  '../backend/kamayuk-rentas-seguridad/src/main/java/kamayuk/rentas/seguridad/infraestructura/web/SesionController.java',
);

function leer(ruta: string): string {
  if (!existsSync(ruta)) {
    throw new Error(
      `FALTA UNA FUENTE DEL BACKEND: ${ruta}\n\n` +
        '  Esta guarda lee de ahi que opcion pide cada lectura del catalogo, o con que nombre la\n' +
        '  siembra el backend. Si el archivo se movio, muevela con el.',
    );
  }
  return readFileSync(ruta, 'utf8');
}

/** El `acceso` del `@RequiereAcceso` que va justo despues de `@GetMapping("<ruta>")`. */
function accesoQuePide(controlador: string, ruta: string): string | null {
  const patron = new RegExp(
    `@GetMapping\\("${ruta.replace('/', '\\/')}"\\)\\s*@RequiereAcceso\\(acceso\\s*=\\s*"([a-z_]+)"`,
  );
  return patron.exec(controlador)?.[1] ?? null;
}

/** El nombre de la fila `| \`codigo\` | Nombre | …` del catalogo de opciones. */
function nombreEnElCatalogo(catalogo: string, codigo: string): string | null {
  const fila = catalogo
    .split('\n')
    .find((linea) => linea.startsWith(`| \`${codigo}\` |`));
  return fila?.split('|')[2]?.trim() ?? null;
}

describe('las opciones que leen el catalogo (#311)', () => {
  const controlador = leer(CONTROLADOR);
  const catalogo = leer(CATALOGO_DE_OPCIONES);

  it('EL CENTINELA: el controlador y el catalogo se leen, y traen lo que se busca', () => {
    // Sin esto, un patron que dejara de casar daria `null` en los dos lados y «null === null».
    expect(controlador).toContain('@RequiereAcceso');
    expect(catalogo.split('\n').filter((l) => /^\| `[a-z_]+` \|/.test(l)).length).toBeGreaterThan(100);
  });

  it.each(Object.entries(OPCIONES_QUE_LEEN_EL_CATALOGO))(
    '`GET /seguridad/%s` pide la opcion que la pantalla nombra, con el nombre que se siembra',
    (lectura, opcion) => {
      expect(
        accesoQuePide(controlador, `/${lectura}`),
        `\`GET /seguridad/${lectura}\` ya no pide \`${opcion.codigo}\``,
      ).toBe(opcion.codigo);
      expect(
        nombreEnElCatalogo(catalogo, opcion.codigo),
        `el catalogo de opciones no llama «${opcion.nombre}» a \`${opcion.codigo}\``,
      ).toBe(opcion.nombre);
      expect(
        ACCESOS_MEDIDOS.find((a) => a.codigo === opcion.codigo)?.nombre,
        `la instalacion no llama «${opcion.nombre}» a \`${opcion.codigo}\``,
      ).toBe(opcion.nombre);
    },
  );
});

/**
 * **La opcion con que se fija el ejercicio es la que el `PUT` pide, con su privilegio** (#391).
 *
 * El mando de la barra se ofrece solo si la cuenta tiene `CAMBIAR_EL_EJERCICIO.privilegio` sobre
 * `CAMBIAR_EL_EJERCICIO.codigo`, y a quien no lo tiene le nombra la opcion por su nombre del
 * catalogo. Escritos sin esta guarda, el dia que el backend pidiera otra cosa el mando se ofreceria
 * a quien recibe 403 —o se esconderia a quien si puede—, y la barra mandaria a pedir una opcion con
 * un nombre que nadie encuentra. Las mismas tres fuentes que las dos de arriba.
 */
describe('la opcion que fija el ejercicio (#391)', () => {
  const controlador = leer(CONTROLADOR_DE_LA_SESION);
  const catalogo = leer(CATALOGO_DE_OPCIONES);

  it('`PUT /seguridad/sesion/ejercicio` pide el codigo y el privilegio que el mando pregunta', () => {
    const patron =
      /@PutMapping\(Api\.RAIZ \+ "\/seguridad\/sesion\/ejercicio"\)\s*@RequiereAcceso\(acceso\s*=\s*"([a-z_]+)",\s*privilegio\s*=\s*Privilegio\.([A-Z]+)\)/;
    const casa = patron.exec(controlador);
    // Sin casar, las dos de abajo compararian `undefined` con algo y el mensaje no diria por que.
    expect(casa, 'el patron ya no encuentra el `PUT` del ejercicio en `SesionController`').not.toBeNull();
    expect(casa?.[1], '`PUT /seguridad/sesion/ejercicio` ya no pide ese codigo').toBe(
      CAMBIAR_EL_EJERCICIO.codigo,
    );
    expect(casa?.[2]?.toLowerCase(), '`PUT /seguridad/sesion/ejercicio` ya no pide ese privilegio').toBe(
      CAMBIAR_EL_EJERCICIO.privilegio,
    );
  });

  it('y su nombre es el que se siembra y el que contesto la instalacion', () => {
    expect(nombreEnElCatalogo(catalogo, CAMBIAR_EL_EJERCICIO.codigo)).toBe(CAMBIAR_EL_EJERCICIO.nombre);
    expect(ACCESOS_MEDIDOS.find((a) => a.codigo === CAMBIAR_EL_EJERCICIO.codigo)?.nombre).toBe(
      CAMBIAR_EL_EJERCICIO.nombre,
    );
  });
});
