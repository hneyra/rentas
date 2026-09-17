import { describe, expect, it } from 'vitest';

import { ARBOL, hojaDe } from './pantallas/arbol.ts';
import type { ClaveDeHoja } from './pantallas/arbol.ts';
import type { Hoja } from './pantallas/tipos.ts';
import { CONECTORES } from './datos/conectores.ts';
import { YA_SERVIDAS } from './datos/servidas.ts';
import {
  NADA_SERVIDO,
  SERVIDO_Y_SIN_PEDIR,
  SOLO_BASE,
  SOLO_ESCRIBE,
  operacionesUtiles,
  porQueNoHayDato,
} from './porQueNoHayDato.ts';

/**
 * **Por que una pantalla no tiene datos** (#97).
 *
 * Lo que se comprueba aqui no es que las frases suenen bien: es que **los casos no se confundan**.
 * Meterlos en un «no hay datos» unico seria mentir por omision — «este modulo no esta conectado» y
 * «esta pantalla se conecta pero no se sabe el verbo de su ruta» son cosas distintas para quien
 * tenga que arreglarlas.
 */

// Anotada a proposito: `ARBOL` es un `as const` de diez modulos con hojas de tipos distintos, y
// sin la anotacion el compilador intenta unificar diez tuplas y se rinde.
const TODAS: readonly Hoja[] = ARBOL.flatMap((modulo) => [...modulo.hojas]);

describe('el cruce contra lo que el backend sirve', () => {
  it('EL CENTINELA: hay cuarenta hojas y treinta y dos operaciones servidas', () => {
    // Sin esto, un arbol vacio o unas `YA_SERVIDAS` vacias dejarian todo lo de abajo pasando
    // sobre la nada — y la respuesta seria «ninguna pantalla tiene datos», que ademas parece
    // razonable.
    expect(TODAS).toHaveLength(40);
    expect(YA_SERVIDAS).toHaveLength(32);
  });

  it('cruza por RUTA, no por verbo: dos servidas las declara el artboard como `BASE`', () => {
    // `GET /rentas/contribuyentes` y `GET /rentas/beneficios` estan servidas y medidas, y el
    // artboard las declara `BASE`. Con el cruce estricto, `predios` y `valores` perderian dato
    // que existe.
    expect(operacionesUtiles(hojaDe('predios')).map((o) => o.ruta)).toContain(
      '/rentas/contribuyentes',
    );
    expect(operacionesUtiles(hojaDe('valores')).map((o) => o.ruta)).toContain('/rentas/beneficios');
  });

  it('y solo mira verbos de LECTURA: un PUT no pinta una pantalla', () => {
    // `seg-sis` declara `PUT /seguridad/sesion/ejercicio`, que ESTA servida. Y aun asi no tiene
    // con que dibujarse: un PUT no devuelve una pantalla.
    const utiles = operacionesUtiles(hojaDe('seg-sis'));
    expect(utiles.every((o) => o.verbo !== 'PUT')).toBe(true);
  });

  it('veintitres hojas tienen alguna operacion util, y diecisiete ninguna', () => {
    const con = TODAS.filter((hoja) => operacionesUtiles(hoja).length > 0);
    // `aut-cat` entra con #168 por la ruta que el ARTBOARD le atribuye, `GET /licencias/ciiu`.
    //
    // **`aut-tram` esta aqui desde #173, y `aut-panel` ya no.** Hasta entonces era al reves, y era
    // la unica pareja de este tipo en las cuarenta: `aut-tram` tenia conector y no declaraba la
    // operacion que lo alimenta, mientras `aut-panel` declaraba esa misma operacion y no podia
    // dibujar con ella ni un campo. #173 lo decidio con el artboard delante —la tabla «Padron de
    // licencias» de `aut-tram` cuadra columna a columna con `GET /licencias/funcionamiento`, y las
    // cinco cifras de `aut-panel` son estados de TRAMITE que nadie publica— y movio la operacion
    // en el artboard y en el arbol a la vez. La cuenta no se mueve: una sale y otra entra.
    //
    // `con-contrib`, `con-doc` y `con-panel` entran con #169, y la primera **sin conector y es
    // correcto que entre**: declara `/consultas/unificada`, que ya esta servida, y lo que le falta
    // no es la operacion sino lo que ensena —unidades y deuda por FILA, que esa operacion no
    // publica—. Por eso dice «sin pedir» y no «sin conectar»: son cosas distintas para quien tenga
    // que arreglarlas.
    //
    // Las tres de Fiscalizacion entran con #179, y **`fis-actas` entra por una declaracion
    // `BASE`**: el artboard le atribuye `BASE /fiscalizacion/actas` y lo que se enciende es
    // `GET /fiscalizacion/actas`, que es la misma RUTA. Es el tercer caso de este tipo —los otros
    // dos son `predios` y `valores`, y estan justo arriba—, y es la razon por la que el cruce mira
    // la ruta y no el verbo.
    //
    // `tra-pap` y `tra-veh` entran con #180, y las dos **por las rutas que el ARTBOARD les
    // atribuye**: las cuatro de transito que se encienden estan en el arbol de esas dos hojas. Las
    // otras dos de Transito siguen fuera — `tra-panel` declara `/transito/estado-cuenta` y
    // `tra-cua` declara `/transito/codigos`, y ninguna de las dos esta servida.
    expect(con.map((h) => h.clave).sort()).toEqual(
      [
        'aut-cat',
        'aut-tram',
        'coa-cost',
        'coa-exp',
        'coa-panel',
        'con-contrib',
        'con-doc',
        'con-panel',
        'fis-actas',
        'fis-prog',
        'fis-res',
        'ini-flujo',
        'ini-panel',
        'ini-parado',
        'panel',
        'predios',
        'seg-acc',
        // `seg-aud` entra con #181, que enciende `GET /seguridad/auditoria` — la unica operacion
        // que declara. Y entra **con conector**, asi que esta funcion no llega a decir nada de
        // ella: lo que decide que ensena es `conectores/seguridad.ts`.
        'seg-aud',
        'seg-panel',
        'tra-pap',
        'tra-veh',
        'val-tip',
        'valores',
      ].sort(),
    );
    expect(TODAS.length - con.length).toBe(17);
  });
});

/**
 * **El barrido de #173 (AC4): ninguna hoja pide una operacion que no declara.**
 *
 * El desajuste que #173 corrigio no era de una hoja: era un cruce que nadie comprobaba. `aut-tram`
 * pedia `GET /licencias/funcionamiento` desde #168 y su arbol declaraba otras tres; `aut-panel`
 * declaraba esa y no podia pedir nada. Mientras eso solo viviera en un javadoc, la siguiente hoja
 * conectada podia repetirlo sin que nada lo dijera.
 *
 * <h2>Lo que este barrido puede comprobar hoy, y lo que no</h2>
 *
 * **No** puede comparar ruta con ruta: `Conector.pedir` es una funcion, y la ruta que pide vive
 * dentro de ella —sacarla a dato es #186, que la necesita para paginar y ordenar en servidor—. Lo
 * que si puede, y es lo que fallaba, es la condicion NECESARIA: una hoja con conector tiene que
 * declarar **al menos una operacion de lectura que el backend sirva**. Si no la declara, o pide
 * algo que no declaro, o no puede pedir nada — y las dos cosas son el defecto.
 *
 * Cuando #186 saque la ruta al conector, esto se estrecha a la igualdad y este comentario sobra.
 */
describe('AC4 — toda hoja con conector declara la operacion que la sirve', () => {
  it('las diecisiete que piden de verdad declaran alguna servida de lectura', () => {
    const mudas = Object.keys(CONECTORES).filter(
      (clave) => operacionesUtiles(hojaDe(clave as ClaveDeHoja)).length === 0,
    );

    expect(
      mudas,
      'Estas hojas tienen conector y su arbol no declara ni una operacion servida de lectura: o\n' +
        'piden algo que no declararon —el defecto de `aut-tram` hasta #173— o no pueden pedir\n' +
        'nada. Se corrige en el ARTBOARD y en `arbol.ts` a la vez, no aflojando esto.',
    ).toEqual([]);
  });

  it('EL CENTINELA: y son diecisiete, no cero', () => {
    // Un registro de conectores vacio dejaria la comprobacion de arriba pasando sobre la nada.
    expect(Object.keys(CONECTORES)).toHaveLength(17);
  });
});

describe('los cinco casos no se confunden', () => {
  it('sin ninguna servida: «sin conectar», en tono informativo', () => {
    // `fis-panel` es el ejemplo desde #167, que encendio las dos de indicadores y dejo servida
    // a `ini-panel`. No vale cualquier hoja sin conector: hace falta una que no declare NINGUNA
    // operacion servida —`ini-cierre`, por ejemplo, declara varias en `BASE` y por eso dice «sin
    // verificar», que es el caso de la prueba de abajo y no el de esta.
    expect(porQueNoHayDato(hojaDe('fis-panel'))).toBe(NADA_SERVIDO);
    expect(NADA_SERVIDO.tono).toBe('info');
  });

  it('todo en `BASE`: «sin verificar», y en tono de ATENCION', () => {
    // `tra-cua` tiene su unica operacion en `BASE`. Hay controlador y no se sabe llamarlo: eso
    // merece mas atencion que no tener nada, no menos.
    //
    // **Era `tra-pap` hasta #180**, y dejo de valer porque se comprobo el verbo: de sus cuatro
    // rutas, tres son `GET` y la cuarta —`/transito/descargos`— es `POST`. Verificarlas es lo que
    // este caso premia, asi que el ejemplo se muda a la hoja que todavia no lo esta.
    expect(porQueNoHayDato(hojaDe('tra-cua'))).toBe(SOLO_BASE);
    expect(SOLO_BASE.tono).toBe('atencion');
  });

  it('sin NINGUNA operacion declarada: «sin conectar», y no «sin verificar»', () => {
    // `aut-panel` es la primera hoja de las cuarenta que no declara ninguna operacion (#173), y
    // es la que enseno que `[].every(...)` es `true`: sin el `length > 0` contestaba «sin
    // verificar», o sea «de las rutas que esta pantalla declara solo se leyo el `@RequestMapping`
    // de su controlador» — dos afirmaciones falsas sobre una hoja que no declara ninguna ruta.
    const hoja = hojaDe('aut-panel');

    expect(hoja.operaciones).toEqual([]);
    expect(porQueNoHayDato(hoja)).toBe(NADA_SERVIDO);
    expect(porQueNoHayDato(hoja)).not.toBe(SOLO_BASE);
  });

  it('ni un `GET` y con escrituras: «solo escribe», que no es «sin conectar» (#182)', () => {
    // `territorio` es la hoja de la Determinacion, y declara SIETE operaciones: cuatro `POST` y
    // tres `BASE` que —medidas contra el contrato y contra sus controladores— tambien son `POST`.
    // Con las tres frases de antes decia «sin conectar», o sea «ninguna de las operaciones que
    // declara la sirve el backend», que es falso: las sirve todas. Lo que pasa es que escriben.
    const hoja = hojaDe('territorio');

    expect(hoja.operaciones.some((o) => o.verbo === 'GET')).toBe(false);
    expect(porQueNoHayDato(hoja)).toBe(SOLO_ESCRIBE);
    expect(porQueNoHayDato(hoja)).not.toBe(NADA_SERVIDO);
  });

  it('y el barrido da CUATRO, todas de ejecutar: ninguna otra hoja cae aqui (#182)', () => {
    // Si la condicion se ensanchara —por ejemplo contando `BASE` como escritura— entrarian hojas
    // que si son de consulta, y la frase dejaria de decir la verdad sin que nada lo notara.
    const soloEscriben = TODAS.filter((h) => porQueNoHayDato(h) === SOLO_ESCRIBE);

    expect(soloEscriben.map((h) => h.clave).sort()).toEqual(
      ['aut-sol', 'territorio', 'val-cart', 'val-val'].sort(),
    );
  });

  it('una hoja SIN operaciones no «escribe»: no se sabe nada de ella', () => {
    // Los dos lados de la condicion. `aut-panel` no declara ninguna operacion desde #173, y eso no
    // es ejecutar: es no tener a quien preguntar. Sin el `algunaEscribe` caeria en «solo escribe».
    expect(porQueNoHayDato(hojaDe('aut-panel'))).toBe(NADA_SERVIDO);
  });

  it('con servidas y sin pedirlas: lo dice, en vez de callar', () => {
    expect(porQueNoHayDato(hojaDe('seg-panel'))).toBe(SERVIDO_Y_SIN_PEDIR);
  });

  it('y las CUATRO frases son DISTINTAS, que es lo que las hace servir de algo', () => {
    const frases = [NADA_SERVIDO, SOLO_BASE, SOLO_ESCRIBE, SERVIDO_Y_SIN_PEDIR];
    expect(new Set(frases.map((a) => a.enElCampo)).size).toBe(4);
    expect(new Set(frases.map((a) => a.explicacion)).size).toBe(4);
  });

  it('NINGUNA dice un cero ni una cifra: no saber no es una afirmacion', () => {
    // Es la regla que motiva todo esto. Un «0» donde no se ha contado nada es indistinguible de
    // un cero real, y en recaudacion eso se lee como «no debe nada».
    for (const ausencia of [NADA_SERVIDO, SOLO_BASE, SOLO_ESCRIBE, SERVIDO_Y_SIN_PEDIR]) {
      expect(ausencia.enElCampo, `«${ausencia.enElCampo}» lleva un digito`).not.toMatch(/\d/);
      expect(ausencia.enElCampo).not.toMatch(/^0|S\//);
    }
  });
});
