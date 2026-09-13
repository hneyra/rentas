import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
  sinLosAccesosDe,
} from '../src/datos/seguridadMedida.ts';
import type { PermisosDeLaSesion } from '../src/datos/lecturas.ts';

import { Aplicacion, CONSULTAS } from '../src/aplicacion.tsx';
import { CATALOGO } from '../src/catalogo.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../src/pantallas/arbol.ts';

/**
 * **Los cuarenta destinos se abren, en la aplicacion de verdad** (#90, AC8).
 *
 * <h2>Que cambia frente a la version anterior de esta guarda</h2>
 *
 * Hasta #90 esto montaba `<Pantalla>` suelta, con su definicion en la mano. Ahora monta **la
 * aplicacion entera** y llega a cada destino **por su hash**, que es como se llega de verdad.
 *
 * La diferencia no es de estilo: montar la pantalla suelta no comprueba que el destino este en el
 * catalogo, ni que el armazon sepa enrutarlo, ni que la hoja y su definicion sigan emparejadas.
 * Las tres cosas pueden romperse sin tocar una sola pantalla — y las tres dejan la aplicacion sin
 * ese destino mientras las pruebas de pantalla siguen en verde.
 *
 * <h2>Y de cada uno se exige algo que SOLO aparece con su definicion puesta</h2>
 *
 * Su titulo, su instruccion y **todos** los titulos de sus bloques. Un armazon que enrutara bien y
 * dibujara un marco vacio pasaria un «no reventó»; no pasa esto.
 *
 * <h2>Desde #105 hace falta contestar a las TRES de seguridad, y eso mejora la prueba</h2>
 *
 * El catalogo que el armazon recibe **se compone** de `GET /seguridad/{modulos,accesos}` y
 * `/seguridad/sesion/permisos`: lo que la cuenta no puede abrir no se ofrece. Sin contestarlas, el
 * arbol sale vacio y **ninguno de los cuarenta destinos abre** — que es el comportamiento correcto
 * y lo que esta prueba media antes por accidente, porque el catalogo se pasaba entero.
 *
 * Se contestan con `seguridadMedida.ts`, que son **respuestas de `curl` a la instalacion de
 * verdad** y no invenciones. Asi esta prueba deja de comprobar «el enrutado funciona» y pasa a
 * comprobar la cadena entera: permisos -> catalogo -> ruta -> pantalla.
 */

const DESTINOS = CATALOGO.flatMap((modulo) =>
  modulo.destinos.map((destino) => ({ modulo: modulo.rotulo, destino })),
);

beforeAll(() => {
  // Lo que jsdom no trae y las piezas del armazon piden. Sus motivos, en `@kamayuk/shell`.
  Element.prototype.scrollIntoView = () => {};
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.releasePointerCapture = () => {};
  globalThis.matchMedia ??= ((consulta: string) => ({
    matches: false,
    media: consulta,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as typeof matchMedia;
});

/**
 * Contesta a las tres de seguridad con lo medido, y a todo lo demas con un 404.
 *
 * Un 404 y no un cuerpo vacio: lo que este arnes NO quiere es que una pantalla parezca tener datos
 * porque el doble contesto algo. Las que piden de verdad —`panel`, `coa-panel`— tienen su propia
 * prueba; aqui lo que se mide es que abran.
 */
/** Los permisos con que contesta el doble. Una prueba los cambia para medir el filtrado. */
let permisos: PermisosDeLaSesion = PERMISOS_MEDIDOS;

beforeEach(() => {
  // La cache de consultas es de MODULO y sobrevive a cada `render`: sin limpiarla, la prueba que
  // cambia los permisos leeria los de la prueba anterior y saldria verde sobre el catalogo
  // equivocado. Medido, y fue justo lo que paso.
  CONSULTAS.clear();
  permisos = PERMISOS_MEDIDOS;
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      const json = (cuerpo: unknown) =>
        Promise.resolve(
          new Response(JSON.stringify(cuerpo), {
            status: 200,
            headers: { 'content-type': 'application/json' },
          }),
        );
      // Las dos van PAGINADAS y no como lista pelada: `pedirLista` desenvuelve `contenido`, y con
      // un arreglo suelto revienta con «Cannot read properties of undefined (reading 'length')» —
      // un rojo que habla de `length` y no de la forma de la respuesta.
      const pagina = (contenido: readonly unknown[]) =>
        json({
          contenido,
          pagina: 0,
          tamano: 200,
          totalElementos: contenido.length,
          totalPaginas: 1,
          hayMas: false,
        });
      if (url.includes('/seguridad/modulos')) return pagina(MODULOS_MEDIDOS);
      if (url.includes('/seguridad/accesos')) return pagina(ACCESOS_MEDIDOS);
      if (url.includes('/seguridad/sesion/permisos')) return json(permisos);
      return Promise.resolve(new Response('{}', { status: 404 }));
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
});

/**
 * Monta la aplicacion con el hash ya puesto, y **espera a que el catalogo se componga**.
 *
 * La espera no es un apano: desde #105 el arbol no existe hasta que las tres de seguridad
 * contestan, y montar y mirar en la misma vuelta mediria el estado «pidiendo».
 */
async function abrir(clave: string) {
  window.location.hash = `#/${clave}`;
  render(<Aplicacion />);
  // Se espera a que el ARMAZON exista, no a que desaparezca un texto: `rentas` no lo monta hasta
  // saber que puede abrir la cuenta (ver `aplicacion.tsx`), asi que antes de eso no hay ni barra.
  await waitFor(() => {
    expect(document.querySelector('[data-slot="barra-global"], header, nav')).not.toBeNull();
  });
}

describe('los cuarenta destinos se recorren, en la aplicacion montada', () => {
  it('EL CENTINELA: el catalogo trae diez modulos y cuarenta destinos', () => {
    // Sin esto, un catalogo vacio dejaria el `it.each` de abajo sin casos y el archivo en verde
    // habiendo recorrido cero pantallas. Es la forma en que este repositorio ya se quedo sin
    // guarda dos veces (#78, #80).
    expect(CATALOGO).toHaveLength(10);
    expect(DESTINOS).toHaveLength(40);
  });

  it.each(DESTINOS)('«$destino.clave» — $destino.rotulo', async ({ destino }) => {
    await abrir(destino.clave);
    const definicion = pantallaDe(destino.clave as ClaveDeHoja);

    expect(
      screen.getByRole('heading', { level: 1, name: destino.rotulo }),
      `«${destino.clave}» no abrio por su hash`,
    ).toBeTruthy();
    expect(screen.getByText(new RegExp(escapar(definicion.instruccion)))).toBeTruthy();
    for (const bloque of definicion.bloques) {
      expect(
        screen.getByRole('heading', { level: 2, name: bloque.titulo }),
        `«${destino.clave}» no pinto el bloque «${bloque.titulo}»`,
      ).toBeTruthy();
    }
  });

  /**
   * **AC2 de #105: lo que la cuenta no puede abrir, no se abre — ni por el hash.**
   *
   * Sin esta prueba el recorrido de arriba **no distingue** una aplicacion que filtra de una que
   * no: la cuenta de la instalacion puede abrir los diez modulos, asi que pasarle el catalogo
   * entero da exactamente el mismo verde. Medido — se probo a devolver el catalogo sin filtrar y
   * las 45 pruebas seguian pasando.
   *
   * Con una cuenta a la que le falta un modulo, la diferencia se ve.
   */
  it('un modulo que la cuenta NO puede abrir no esta en el arbol NI se abre por su hash', async () => {
    permisos = sinLosAccesosDe('TRANSITO');
    await abrir('tra-pap');

    // Ni la pantalla...
    expect(screen.queryByRole('heading', { level: 1, name: 'Papeletas' })).toBeNull();
    // ...ni su modulo en el carril...
    expect(screen.queryByRole('button', { name: /Tránsito/ })).toBeNull();
    // ...y el armazon LO DICE, en vez de dejar la pantalla en blanco.
    //
    // Se mira el `data-slot` y no el TEXTO, a proposito: las frases son de `@kamayuk/shell`. Una
    // prueba de este repositorio atada a las palabras de otro se rompe cada vez que aquel las
    // toque, y el rojo hablaria de una cadena en vez de de la propiedad — que es «no se queda
    // mudo».
    //
    // Cuando esto se escribio, `kamayuk-lib`#19 las estaba sacando a `textos`. **Ya cerro**, y
    // desde #133 las pasa `i18n/textosDelMarco.ts` (#136): el texto depende ahora ademas del
    // idioma de la sesion, o sea que mirar el `data-slot` vale por dos motivos en vez de uno.
    expect(elArmazonLoDice()).toBe(true);
  });

  it('y el CENTINELA: con permiso, ese MISMO hash si abre', async () => {
    // Sin esta mitad, la de arriba pasaria igual con una aplicacion que no abriera nada por hash.
    await abrir('tra-pap');
    expect(screen.getByRole('heading', { level: 1, name: 'Papeletas' })).toBeTruthy();
  });

  it('un hash que no es de ningun destino NO abre una pantalla', async () => {
    // La otra direccion. Un enrutador que cayera en la primera pantalla ante cualquier hash
    // desconocido pasaria las cuarenta de arriba y ofreceria pantallas que nadie pidio.
    await abrir('no-existe-este-destino');
    // Se mira un titulo de bloque de una pantalla de VERDAD y no «cualquier h2»: el armazon usa un
    // h2 para su propio estado vacio, y prohibirlos todos mediria el marco en vez de la pantalla.
    expect(screen.queryByRole('heading', { level: 2, name: 'Ejercicio en curso' })).toBeNull();
    expect(elArmazonLoDice()).toBe(true);
  });

  it('y el arbol ofrece los diez modulos, con sus rotulos', async () => {
    await abrir('ini-panel');
    for (const modulo of CATALOGO) {
      expect(
        screen.getByRole('button', { name: new RegExp(escapar(modulo.rotulo)) }),
        `el carril no ofrece «${modulo.rotulo}»`,
      ).toBeTruthy();
    }
  });

  it('el modulo del destino abierto viene YA desplegado, con sus cuatro hojas', async () => {
    // Abrir por hash tiene que desplegar su modulo: si no, quien llega por un enlace ve su
    // pantalla y **el arbol cerrado**, sin pista de donde esta. Y por eso no se pulsa nada aqui —
    // pulsar el modulo lo CERRARIA, que es lo que hacia fallar la version anterior de esta prueba.
    await abrir('ini-panel');
    const primero = CATALOGO[0];
    if (primero === undefined) throw new Error('el catalogo vino vacio');
    for (const destino of primero.destinos) {
      expect(
        screen.getByRole('button', { name: destino.rotulo }),
        `el carril no ofrece «${destino.rotulo}»`,
      ).toBeTruthy();
    }
  });

  it('y pulsar otro modulo despliega el suyo', async () => {
    await abrir('ini-panel');
    const otro = CATALOGO[1];
    if (otro === undefined) throw new Error('el catalogo tiene un solo modulo');
    fireEvent.click(screen.getByRole('button', { name: new RegExp(escapar(otro.rotulo)) }));
    for (const destino of otro.destinos) {
      // `getAllBy` y no `getBy`: hay rotulos que se repiten entre un modulo y la hoja de otro
      // —«Valores» es un modulo Y una hoja de «Rentas · Registro»—, y eso no es un defecto: son
      // dos cosas distintas que se llaman igual, como en el artboard.
      expect(
        screen.getAllByRole('button', { name: destino.rotulo }).length,
        `el carril no ofrece «${destino.rotulo}»`,
      ).toBeGreaterThan(0);
    }
  });
});

/**
 * Si el armazon esta diciendo que ahi no hay destino, de cualquiera de sus dos formas.
 *
 * Por `data-slot` y no por texto: las frases viven en `@kamayuk/shell` y estan volviendose
 * traducibles. Cual de las dos sale depende de cuantos modulos queden, y eso no es lo que esta
 * prueba mide — lo que mide es que **no se quede muda**.
 */
function elArmazonLoDice(): boolean {
  return (
    document.querySelector('[data-slot="sin-destino"]') !== null ||
    document.querySelector('[data-slot="destino-no-ofrecido"]') !== null
  );
}

/** Una instruccion lleva parentesis y puntos: sin escapar, la expresion regular no casa. */
function escapar(texto: string): string {
  return texto.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
