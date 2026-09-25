import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { sembrarElCatalogo } from '../desarrollo/sembrarElCatalogo.ts';
import { Aplicacion, CONSULTAS } from '../src/aplicacion.tsx';
import { CATALOGO } from '../src/catalogo.ts';
import { MUNICIPALIDAD_MEDIDA, SESION_MEDIDA } from '../src/datos/sesionMedida.ts';
import type { ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { bloquesDe } from '../src/pantallas/bloques.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';

/**
 * **La siembra abre los cuarenta destinos sin que nadie conteste** (#114, AC1).
 *
 * <h2>Que anade a `los-cuarenta-destinos-se-recorren.test.tsx`, que ya los recorre</h2>
 *
 * Aquella **contesta las tres de seguridad con un doble de `fetch`**: mide que la cadena
 * permisos → catalogo → ruta → pantalla funciona cuando el backend contesta. Esta mide lo otro,
 * que es lo que #114 vino a cerrar: que la cadena funcione **cuando no contesta nadie**, que es
 * el estado de un puesto de desarrollo sin plataforma levantada.
 *
 * La diferencia esta en el doble: aqui `fetch` **rechaza todo**. Si la siembra no pusiera el dato
 * donde las tres consultas lo buscan —o lo pusiera rancio, y salieran a refrescarlo—, no habria
 * arbol y no abriria ni un destino. Con el doble que contesta, las dos roturas pasan en verde.
 *
 * <h2>Y se cuentan las peticiones, que es la mitad que no se ve mirando la pantalla</h2>
 *
 * Una siembra que dejara el dato rancio **dibujaria el arbol igual** durante un instante y
 * despues lo perderia, porque una consulta que falla al refrescar pasa a `error` aunque conserve
 * el dato. Contar las idas a `/seguridad/` distingue «sembrado» de «sembrado y encima pedido»,
 * que es la diferencia entre funcionar sin backend y parecer que funciona.
 */

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
 * Tres destinos de tres modulos distintos, sacados del catalogo y no escritos.
 *
 * Tres y no cuarenta: los cuarenta ya los recorre la guarda hermana, y con el doble puesto. Lo
 * que aqui se mide es de donde sale el ARBOL, y para eso tres destinos de tres ramas distintas
 * dicen lo mismo que cuarenta en un tercio del tiempo. De tres modulos distintos a proposito: con
 * tres hojas del mismo, un arbol sembrado a medias pasaria.
 */
const TRES_DESTINOS = [0, 4, 9].map((i) => {
  const modulo = CATALOGO[i];
  const destino = modulo?.destinos[0];
  if (destino === undefined) throw new Error(`el catalogo no trae el modulo ${String(i)}`);
  return { clave: destino.clave, rotulo: destino.rotulo };
});

/** Las URL que se pidieron. Vacia de seguridad es la mitad de lo que esta prueba afirma. */
let pedidas: string[] = [];

beforeEach(() => {
  // La cache es de MODULO y sobrevive a cada `render`: sin limpiarla, la segunda prueba leeria lo
  // que sembro la primera y no mediria nada.
  CONSULTAS.clear();
  pedidas = [];
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      pedidas.push(String(entrada));
      // **Nadie contesta**, que es el estado que esta prueba mide: sin PostgreSQL, sin Keycloak,
      // sin Traefik y sin backend, el navegador no recibe una respuesta — recibe un rechazo.
      return Promise.reject(new TypeError('Failed to fetch'));
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
});

/** Monta la aplicacion en un destino y espera al armazon, que no existe hasta que hay arbol. */
async function abrir(clave: string) {
  window.location.hash = `#/${clave}`;
  render(<Aplicacion />);
  await waitFor(() => {
    expect(document.querySelector('[data-slot="barra-global"], header, nav')).not.toBeNull();
  });
}

describe('con el catalogo sembrado, la interfaz se recorre sin backend', () => {
  it('EL CENTINELA: sin sembrar NO abre nada, y ese es el hueco que #114 cerro', async () => {
    // Sin esta mitad, la prueba de abajo pasaria igual con una siembra que no hiciera nada: con
    // el catalogo llegando de otro sitio, el verde no diria de donde salio el arbol.
    render(<Aplicacion />);

    await waitFor(() => {
      expect(
        screen.getByText(/No se pudo saber que modulos puede abrir esta cuenta/),
      ).toBeTruthy();
    });
    expect(document.querySelector('[data-slot="barra-global"]')).toBeNull();
  });

  it.each(TRES_DESTINOS)('sembrado, «$clave» abre con su titulo y sus bloques', async (destino) => {
    sembrarElCatalogo();
    await abrir(destino.clave);
    const definicion = pantallaDe(destino.clave as ClaveDeHoja);

    expect(
      screen.getByRole('heading', { level: 1, name: destino.rotulo }),
      `«${destino.clave}» no abrio por su hash`,
    ).toBeTruthy();
    // Solo los bloques (#288): una pieza del consumidor pone su propia cabecera, y no es un bloque
    // del artboard que esta guarda tenga que reconocer.
    for (const bloque of bloquesDe(definicion)) {
      expect(
        screen.getByRole('heading', { level: 2, name: bloque.titulo }),
        `«${destino.clave}» no pinto el bloque «${bloque.titulo}»`,
      ).toBeTruthy();
    }
  });

  it('y el carril ofrece los diez modulos, con el rotulo que trae la captura', async () => {
    sembrarElCatalogo();
    await abrir('ini-panel');

    for (const modulo of CATALOGO) {
      expect(
        screen.getByRole('button', { name: new RegExp(escapar(modulo.rotulo)) }),
        `el carril no ofrece «${modulo.rotulo}»`,
      ).toBeTruthy();
    }
  });

  it('NO se pide ni una de las de seguridad —las tres del catalogo y las dos de la barra—: sembrado es sembrado', async () => {
    sembrarElCatalogo();
    await abrir('ini-panel');

    const deSeguridad = pedidas.filter((url) => url.includes('/seguridad/'));
    expect(
      deSeguridad,
      'La siembra dejo el dato rancio: las consultas salieron a refrescarlo, y sin backend eso\n' +
        'las pone en error aunque conserven el dato — o sea el mensaje que #114 vino a quitar.',
    ).toEqual([]);
  });

  it('y la barra dice la cuenta y la municipalidad de la captura, sin salir a pedirlas (#356)', async () => {
    // Las dos de la sesion se siembran desde #356: la barra las lee siempre, y sin sembrar saldria
    // a la red a preguntar quien esta dentro. Que no salga lo mide la prueba de arriba; esta mide
    // que lo que dice sea la captura —la misma cuenta cuya matriz de permisos se sembro— y no el
    // «no se pudo saber» de una lectura que fallo.
    sembrarElCatalogo();
    await abrir('ini-panel');

    const barra = document.querySelector<HTMLElement>('[data-slot="barra-global"]');
    expect(barra?.textContent).toContain(SESION_MEDIDA.nombre);
    expect(barra?.textContent).toContain(MUNICIPALIDAD_MEDIDA.nombre);
  });

  it('pero las pantallas que SI piden datos siguen pidiendo, y fallando de verdad', async () => {
    // Lo que NO entra en #114: sembrar datos de pantalla. `panel` sale a la red, no encuentra a
    // nadie y ensena su estado de error. Si esto dejara de pedir, la siembra habria pasado de
    // sembrar el catalogo a sembrar la interfaz entera, que es el proxy de datos otra vez.
    sembrarElCatalogo();
    await abrir('panel');

    await waitFor(() => {
      expect(pedidas.some((url) => url.includes('/rentas/predial/corridas/ultima'))).toBe(true);
    });
  });
});

/** Un rotulo puede traer parentesis y puntos; en un `RegExp` eso es otra cosa. */
function escapar(texto: string): string {
  return texto.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
