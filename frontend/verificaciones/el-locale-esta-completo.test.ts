import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { RAIZ } from './artboards.ts';
import { catalogoDeClaves } from '../src/i18n/catalogo-de-claves.ts';

/**
 * **El locale tiene todas las claves, y ninguna se aparta de la suya** (#103, AC5).
 *
 * <h2>Las dos direcciones, y por que hacen falta las dos</h2>
 *
 * · **Ninguna clave usada falta del locale.** Sin esto, un segundo idioma se haria copiando un
 *   archivo incompleto y la pantalla saldria **a medias**: unas frases traducidas y otras en
 *   castellano, que es peor que no traducir nada — parece un defecto de la traduccion y es del
 *   inventario.
 * · **Ningun valor se aparta de su clave.** El castellano esta ahora en dos sitios —la definicion
 *   y el locale— y eso es exactamente lo que la guarda anti-deriva existe para impedir. Aqui se
 *   cierra: cada valor **tiene que ser igual a su clave**, asi que el locale no puede decir algo
 *   distinto del artboard sin ponerse rojo.
 *
 * <h2>La excepcion son los plurales, y es de verdad</h2>
 *
 * `{{count}} registro_one` no puede valer lo que su clave: tiene que valer «{{count}} registro» y
 * su hermana «{{count}} registros». Una clave sola no expresa dos formas, y un ternario en el
 * codigo dejaria fuera los idiomas con mas de dos.
 *
 * <h2>Y por que el locale se REGENERA en vez de escribirse</h2>
 *
 * Porque son `MEDIDO: 967 entradas de es.json`, y salen del dato: a mano se quedan viejas a la
 * primera pantalla nueva. `KAMAYUK_REGENERAR=1 yarn vitest run verificaciones/el-locale-esta-completo` lo
 * vuelve a escribir, que es el mismo trato que `kamayuk-lib` da a su archivo de temas.
 */

const LOCALE = join(RAIZ, 'src/i18n/locales/es.json');

/** Las claves que el interprete no puede derivar del dato: las que estan escritas como `t('…')`. */
const LITERALES = [
  // Las tres que llevan una cuenta dentro: dos del marco (#133) y el conteo de filas del
  // interprete (#153). Sus claves base llegan DERIVADAS de `textosDelMarco.ts` —como las de las
  // definiciones—; lo que no se puede derivar son sus formas plurales: una clave sola no
  // expresa dos formas. Hasta #153 la de `registro` era un `t()` literal dentro del interprete.
  '{{count}} registro_one',
  '{{count}} registro_many',
  '{{count}} registro_other',
  '{{count}} aviso sin leer_one',
  '{{count}} aviso sin leer_many',
  '{{count}} aviso sin leer_other',
  '{{casan}} de {{count}} destino_one',
  '{{casan}} de {{count}} destino_many',
  '{{casan}} de {{count}} destino_other',
  // Y la del grafico de `ini-flujo` (#288), por lo mismo: su clave base entra derivada de
  // `FRASES_DEL_GRAFICO` y sus dos formas no se pueden derivar de una sola cadena.
  '{{count}} tributo no dibuja barra porque su avance no esta medido; su fila esta en la tabla._one',
  '{{count}} tributo no dibuja barra porque su avance no esta medido; su fila esta en la tabla._many',
  '{{count}} tributo no dibuja barra porque su avance no esta medido; su fila esta en la tabla._other',
  'Rentas',
  // «Municipalidad Distrital de Catacaos» salio de aqui en #356: era la entidad de la barra escrita
  // a mano, y hoy la entidad es un DATO de `GET /seguridad/sesion/municipalidad`, que no se
  // traduce. Lo que si se traduce son las cuatro frases con que la barra dice que todavia no lo sabe,
  // o que no lo pudo saber (`datos/useCabeceraDeLaSesion.ts`).
  'Averiguando la municipalidad de esta sesion',
  'No se pudo saber de que municipalidad es esta sesion',
  'Averiguando quien ha entrado',
  'No se pudo saber quien ha entrado',
  'Mi perfil',
  'Cambiar la contrasena',
  'Preferencias',
  'Cerrar sesion',
  'Diez modulos y cuarenta submodulos. Catastro y Tesoreria son de otros sistemas.',
  // `dd/mm/aaaa` salio de aqui en #153: el interprete lo recibe en `textos` y entra DERIVADO de
  // `FRASES_DEL_INTERPRETE`, como la marca de opcional.
  'no publicado',
  // Las cuatro del catalogo filtrado por permisos (#105). Las cazo `i18next-cli status` en cuanto
  // se escribieron, que es exactamente para lo que esta encadenado en `yarn verificar`.
  'Averiguando que puede abrir esta cuenta.',
  'La sesion no vale para saber que puede abrir esta cuenta. Vuelva a entrar.',
  'No se pudo saber que modulos puede abrir esta cuenta, asi que no se ofrece ninguno. Ofrecerlos todos ante un fallo convertiria un problema de red en un agujero de autorizacion.',
  'Esta cuenta no puede abrir ningun modulo de este sistema. No es un fallo: es una cuenta sin permisos, o afiliada a un grupo que no los tiene.',
  // Las del 403 SIN_PRIVILEGIO sobre el catalogo (#311): la frase, el titulo, el remedio y el
  // boton, escritos como `t('…')`; y los NOMBRES de las dos opciones, que se leen por variable
  // —`t(nombre)`, de `OPCIONES_QUE_LEEN_EL_CATALOGO`— y por eso `i18next-cli` no los ve.
  'Esta cuenta no tiene permiso para leer el catalogo de este sistema, asi que no hay modulos que ofrecerle. No es una averia: le falta el permiso de lectura en estas opciones, y lo da quien administre los perfiles.',
  'A esta cuenta le faltan opciones para ver sus modulos',
  'Cuando se las den, pulse Reintentar: no hace falta volver a entrar. El permiso se da en identidad, y este sistema lo recoge cada cinco minutos.',
  'Reintentar',
  'Módulos del sistema',
  'Accesos y políticas',
  // Las tres de la puerta que no contesta (#112). La segunda lleva interpolacion: el emisor, la
  // URL y lo que dijo el navegador son dato, y por eso van entre llaves y no escritos.
  'No se pudo llegar al emisor de identidad, asi que no se mando a nadie a identificarse.',
  'El emisor es {{emisor}}, y la peticion a {{url}} no llego a completarse: {{motivo}}.',
  'Si esto es un puesto de desarrollo, levante la plataforma; si no, avise a quien la administra. Despues vuelva a cargar la pagina.',
  // Las tres del 401 con su remedio (#355): el boton, lo que ESCRIBIO el emisor —dato, entre
  // llaves— y lo que dijo el navegador si la ida revento antes de salir. Los motivos y las
  // explicaciones de una vuelta fallida no estan aqui: entran derivados, ver `catalogo-de-claves`.
  'Volver a identificarse',
  'Lo que dijo el emisor: «{{texto}}»',
  'No se pudo salir hacia el emisor de identidad. El navegador dijo: «{{motivo}}».',
  // Las doce del mando de preferencias (#111). Los rotulos de las cuatro identidades y de los tres
  // modos se leen por variable —`t(ROTULO_DE_LA_IDENTIDAD[identidad])`—, asi que `i18next-cli`
  // no los ve: son de la misma familia que las de las definiciones, y por eso estan aqui.
  'Se guarda en este navegador y solo aqui: no viaja al servidor ni cambia lo que ven las demas personas.',
  'Identidad visual',
  'La paleta con que se dibuja este servicio.',
  'Apariencia',
  'Sin elegir, se sigue lo que el equipo tenga puesto.',
  'Institucional',
  'Alto contraste',
  'Sepia',
  // La cuarta identidad, `clasico`, que publica `@kamayuk/ui` desde kamayuk-lib#56.
  'Clásico',
  'Claro',
  'Oscuro',
  'El del sistema',
] as const;

/** Lo que el plural tiene que decir, que es lo unico que no puede ser su clave. */
const PLURALES: Readonly<Record<string, string>> = {
  '{{count}} registro_one': '{{count}} registro',
  '{{count}} registro_many': '{{count}} registros',
  '{{count}} registro_other': '{{count}} registros',
  '{{count}} aviso sin leer_one': '{{count}} aviso sin leer',
  '{{count}} aviso sin leer_many': '{{count}} avisos sin leer',
  '{{count}} aviso sin leer_other': '{{count}} avisos sin leer',
  // El plural lo decide CUANTOS HAY y no cuantos casan: «1 de 40 destinos», nunca «1 de 40
  // destino». Por eso `ofrecidos` entra como `count` y `casan` como interpolacion normal.
  '{{casan}} de {{count}} destino_one': '{{casan}} de {{count}} destino',
  '{{casan}} de {{count}} destino_many': '{{casan}} de {{count}} destinos',
  '{{casan}} de {{count}} destino_other': '{{casan}} de {{count}} destinos',
  // El grafico (#288). En singular es una fila de la tabla; en plural, varias.
  '{{count}} tributo no dibuja barra porque su avance no esta medido; su fila esta en la tabla._one':
    '{{count}} tributo no dibuja barra porque su avance no esta medido; su fila esta en la tabla.',
  '{{count}} tributo no dibuja barra porque su avance no esta medido; su fila esta en la tabla._many':
    '{{count}} tributos no dibujan barra porque su avance no esta medido; sus filas estan en la tabla.',
  '{{count}} tributo no dibuja barra porque su avance no esta medido; su fila esta en la tabla._other':
    '{{count}} tributos no dibujan barra porque su avance no esta medido; sus filas estan en la tabla.',
};

function elQueDeberiaSer(): Readonly<Record<string, string>> {
  const claves = [...new Set([...catalogoDeClaves(), ...LITERALES])].sort((a, b) =>
    a.localeCompare(b, 'es'),
  );
  return Object.fromEntries(claves.map((c) => [c, PLURALES[c] ?? c]));
}

describe('el locale `es` esta completo y no se aparta', () => {
  const esperado = elQueDeberiaSer();

  if (process.env.KAMAYUK_REGENERAR === '1') {
    writeFileSync(LOCALE, `${JSON.stringify(esperado, null, 2)}\n`, 'utf8');
  }

  const enDisco = JSON.parse(readFileSync(LOCALE, 'utf8')) as Record<string, string>;

  it('EL CENTINELA: el catalogo derivado trae cientos de claves', () => {
    // Sin esto, un catalogo vacio —una importacion rota, un cambio de forma en las definiciones—
    // haria que «no falta ninguna» pasara en verde sobre la nada.
    expect(Object.keys(esperado).length, 'el catalogo vino corto').toBeGreaterThan(700);
  });

  it('no falta ninguna clave, y no sobra ninguna', () => {
    const faltan = Object.keys(esperado).filter((c) => !(c in enDisco));
    const sobran = Object.keys(enDisco).filter((c) => !(c in esperado));
    expect(
      { faltan: faltan.slice(0, 8), sobran: sobran.slice(0, 8) },
      'El locale `es` dejo de cuadrar con lo que el sistema dice.\n' +
        '  Se regenera con:  KAMAYUK_REGENERAR=1 yarn vitest run verificaciones/el-locale-esta-completo',
    ).toEqual({ faltan: [], sobran: [] });
  });

  it('y NINGUN valor se aparta de su clave, salvo los plurales', () => {
    const apartados = Object.entries(enDisco)
      .filter(([clave, valor]) => valor !== (PLURALES[clave] ?? clave))
      .map(([clave, valor]) => `  «${clave}» dice «${valor}»`);
    expect(
      apartados,
      'Hay entradas del locale que dicen algo distinto de su clave:\n' +
        `${apartados.join('\n')}\n\n` +
        '  El castellano esta en dos sitios —la definicion y el locale— y el artboard manda sobre\n' +
        '  el primero. Si el locale puede decir otra cosa, la pantalla se aparta del artboard sin\n' +
        '  que la guarda anti-deriva lo vea.',
    ).toEqual([]);
  });
});
