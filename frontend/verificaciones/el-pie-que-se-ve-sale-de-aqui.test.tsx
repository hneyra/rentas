import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { Aplicacion, CONSULTAS } from '../src/aplicacion.tsx';
import { CATALOGO } from '../src/catalogo.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
} from '../src/datos/seguridadMedida.ts';
import { AVISOS_DE_V8 } from '../src/pantallas/avisos.ts';

/**
 * **El aviso del pie que la pantalla MONTADA ensena es `AVISOS_DE_V8`** (#281).
 *
 * <h2>De que defecto viene, y por que la otra guarda no podia verlo</h2>
 *
 * `los-avisos-del-pie-son-los-del-artboard` compara `AVISOS_DE_V8` contra
 * `diseno/RentasV8.dc.html` y **no mira el DOM**. Medido en #262: hasta #281 el unico `import` de
 * esa constante era el de esa guarda, y quien ponia el pie de verdad era `i18n/textosDelMarco.ts`
 * con las dos frases escritas a mano. O sea que aquella guarda podia estar verde para siempre
 * mientras el pie decia otra cosa — y la decia: a `datosDeHoy` le faltaban las tres palabras «en
 * el padrón». Una de las dos frases llegaba al pie **por coincidir**, que es una coincidencia con
 * forma de contrato.
 *
 * Esta guarda cierra el otro extremo: monta la aplicacion de verdad, llega al destino por su hash
 * y **lee el pie del DOM**. Entre las dos no queda camino por el que una copia se mueva sola —
 * tocar `avisos.ts` o el artboard pone roja a aquella; tocar lo que el marco recibe, o volver a
 * escribir la frase a mano en `textosDelMarco.ts`, pone roja a esta—.
 *
 * <h2>Por que se abren DOS destinos y no uno</h2>
 *
 * Porque el armazon elige entre las dos frases con `destino.seEscribe` —`avisoDelPie()` de
 * `@kamayuk/shell`—, y con un solo destino la otra rama no se mide. Y cual es de cada clase **se
 * deriva del catalogo**, no se escribe. Cuando #281 lo midio eran 39 de escritura y **una** de
 * consulta, `seg-panel`, y esa proporcion es justamente lo que hizo falsa la frase de V8 (ver
 * `avisos.ts`); **desde #291 son 15 y 25**, porque la clase la decide el verbo que la hoja declara
 * y no si algun campo suyo se puede teclear. Esto no cambio ni una linea: el dia que una hoja
 * cambie de clase sigue midiendo las dos ramas sin que nadie vuelva aqui, que es para lo que se
 * derivaba.
 *
 * <h2>Lo que esta guarda NO mide, y quien lo mide</h2>
 *
 * **A quien se le dice cada frase.** Aqui se abre un destino de cada clase y se comprueba que la
 * frase es palabra por palabra la de `avisos.ts`; que la clase sea la que corresponde a esa hoja
 * lo comprueba `el-pie-no-promete-guardar-donde-la-hoja-no-escribe` (#291), que recorre las
 * cuarenta.
 */

const DE_CONSULTA = CATALOGO.flatMap((m) => m.destinos).filter((d) => !d.seEscribe);
const DE_ESCRITURA = CATALOGO.flatMap((m) => m.destinos).filter((d) => d.seEscribe);

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

beforeEach(() => {
  // La cache de consultas es de MODULO y sobrevive a cada `render`.
  CONSULTAS.clear();
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      const json = (cuerpo: unknown, estado = 200) =>
        Promise.resolve(
          new Response(JSON.stringify(cuerpo), {
            status: estado,
            headers: { 'content-type': 'application/json' },
          }),
        );
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
      if (url.includes('/seguridad/sesion/permisos')) return json(PERMISOS_MEDIDOS);
      // Todo lo demas, un 404: lo que aqui se mide es el PIE, y una pantalla sin datos lo dibuja
      // igual. Un cuerpo vacio dejaria a alguna hoja pareciendo que tiene datos.
      return json({}, 404);
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
});

/** Monta la aplicacion en ese destino y devuelve el aviso del pie, leido del DOM. */
async function elAvisoDelPieDe(clave: string): Promise<string> {
  window.location.hash = `#/${clave}`;
  render(<Aplicacion />);
  // El armazon no existe hasta que las tres de seguridad contestan (ver `aplicacion.tsx`).
  const pie = await waitFor(() => {
    const encontrado = document.querySelector('[data-slot="acciones-al-pie"]');
    expect(encontrado, `«${clave}» no llego a dibujar su pie`).not.toBeNull();
    return encontrado as HTMLElement;
  });
  const parrafo = pie.querySelector('p');
  expect(parrafo, `el pie de «${clave}» no trae el aviso`).not.toBeNull();
  return (parrafo?.textContent ?? '').trim();
}

describe('el aviso del pie que se ve sale de `AVISOS_DE_V8`', () => {
  it('EL CENTINELA: el catalogo trae destinos de las DOS clases', () => {
    // Sin esto, un catalogo en el que todas las hojas se escribieran dejaria la mitad de esta
    // guarda midiendo la rama que no existe — y en verde, que es como este repositorio ya se
    // quedo sin guarda antes (#78, #80).
    expect(DE_CONSULTA.length, 'ninguna hoja es de solo consulta').toBeGreaterThan(0);
    expect(DE_ESCRITURA.length, 'ninguna hoja se escribe').toBeGreaterThan(0);
    // Y que las dos frases no sean la misma: si lo fueran, las dos comprobaciones de abajo
    // pasarian con el armazon eligiendo mal.
    expect(AVISOS_DE_V8.consulta).not.toBe(AVISOS_DE_V8.escritura);
  });

  it('una hoja de SOLO CONSULTA ensena el aviso de consulta, palabra por palabra', async () => {
    const destino = DE_CONSULTA[0];
    if (destino === undefined) throw new Error('el catalogo no trae ninguna hoja de consulta');

    expect(
      await elAvisoDelPieDe(destino.clave),
      `El pie de «${destino.clave}» no dice lo que «pantallas/avisos.ts» guarda.\n` +
        '  El aviso del pie sale de AHI y de ningun otro sitio: el saco que el armazon recibe\n' +
        '  —`i18n/textosDelMarco.ts`— lo deriva. Si esto sale rojo, alguien ha vuelto a escribir\n' +
        '  la frase a mano en el saco, que es el defecto que #281 cerro.',
    ).toBe(AVISOS_DE_V8.consulta);
  });

  it('y una que SE ESCRIBE ensena el de escritura, palabra por palabra', async () => {
    const destino = DE_ESCRITURA[0];
    if (destino === undefined) throw new Error('el catalogo no trae ninguna hoja que se escriba');

    expect(
      await elAvisoDelPieDe(destino.clave),
      `El pie de «${destino.clave}» no dice lo que «pantallas/avisos.ts» guarda.`,
    ).toBe(AVISOS_DE_V8.escritura);
  });
});
