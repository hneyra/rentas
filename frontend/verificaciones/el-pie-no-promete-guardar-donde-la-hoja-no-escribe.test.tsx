import { cleanup, render, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { Aplicacion, CONSULTAS } from '../src/aplicacion.tsx';
import { CATALOGO } from '../src/catalogo.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
} from '../src/datos/seguridadMedida.ts';
import { ARBOL } from '../src/pantallas/arbol.ts';
import { AVISOS_DE_V8 } from '../src/pantallas/avisos.ts';
import { laHojaEscribe, type Hoja } from '../src/pantallas/tipos.ts';

/**
 * **El pie no ofrece «Guardar» en una pantalla que no escribe** (#291).
 *
 * <h2>De que defecto viene, medido</h2>
 *
 * `laHojaSeEscribe` de `catalogo.ts` contestaba «esta hoja se escribe» mirando **los campos**: si
 * alguno no era de solo lectura. Sobre las cuarenta eso da **39**, porque en un tablero los campos
 * que se escriben son **los filtros** —un desplegable de ejercicio, un «Desde», un buscador—. Con
 * esas 39, `accionesDelPie()` de `@kamayuk/shell` ponia «Limpiar»+«Guardar» y `avisoDelPie()`
 * ponia «Nada se escribe hasta que pulse Guardar.»; y el «Guardar» que la frase nombra **estaba
 * deshabilitado**, porque `ACCIONES` de `aplicacion.tsx` solo atiende `imprimir` y
 * `AccionesAlPie.tsx` dibuja `disabled={atendida === undefined}`.
 *
 * Los diez paneles —uno por modulo— eran el caso mas claro: `ini-panel` tiene «Ejercicio» y cinco
 * cifras de solo lectura, y mandaba a pulsar un boton que no se puede pulsar para guardar un
 * tablero.
 *
 * Desde #291 la pregunta la contesta `laHojaEscribe()` con el **verbo** que la hoja declara en
 * `arbol.ts`: con #291 quedaron **15 de 40**, y los diez paneles fuera. El porque de ese dato y no otro —con las
 * parejas que demuestran que el tipo del campo no separa un filtro de un dato que se guarda— vive
 * en `pantallas/tipos.ts`.
 *
 * <h2>Por que esta guarda mira el DOM y no `seEscribe`</h2>
 *
 * Porque comparar `CATALOGO[].seEscribe` con `laHojaEscribe()` seria comparar la regla **consigo
 * misma**: un `expect` que no puede fallar mientras `catalogo.ts` llame a esa funcion, y que se
 * quedaria verde el dia que el armazon dejara de usar el dato o lo usara al reves. Lo que hay que
 * poder afirmar es lo que **la pantalla ensena**, y eso solo se ve montandola y leyendo su pie.
 * Es el camino que #281 abrio con `el-pie-que-se-ve-sale-de-aqui`, que mide la otra mitad del
 * mismo pie —que frase sale— mientras esta mide **a quien se le dice**.
 *
 * <h2>Y por que exige ademas que el boton principal se pueda pulsar</h2>
 *
 * Porque es la mitad que se gana, y sin comprobarla el arreglo se puede deshacer sin ponerse rojo.
 * El unico acto que este sistema atiende es `imprimir`, y `imprimir` solo se ofrece en la rama de
 * consulta: antes de #291 llegaba a **una** de las cuarenta pantallas —`seg-panel`—, y con #291
 * paso a **25**. Si alguien quita `imprimir` de `ACCIONES`, esas pantallas vuelven a tener dos
 * botones muertos y esto lo dice.
 */

/** La hoja del arbol de cada destino, por su clave. */
const HOJA_POR_CLAVE = new Map<string, Hoja>(
  ARBOL.flatMap((modulo) => modulo.hojas.map((hoja) => [hoja.clave, hoja] as const)),
);

const DESTINOS = CATALOGO.flatMap((modulo) =>
  modulo.destinos.map((destino) => {
    const hoja = HOJA_POR_CLAVE.get(destino.clave);
    if (hoja === undefined) throw new Error(`«${destino.clave}» no es una hoja del arbol`);
    return { clave: destino.clave, rotulo: destino.rotulo, escribe: laHojaEscribe(hoja) };
  }),
);

/**
 * Los paneles, DERIVADOS de la clave y no escritos: `ini-panel`, `fis-panel` … y `panel`, que es
 * el de Predial y no lleva prefijo. Son uno por modulo, y son el caso que el issue nombra.
 */
const PANELES = DESTINOS.filter((d) => /(^|-)panel$/.test(d.clave));

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
  // La cache de consultas es de MODULO y sobrevive a cada `render` (ver `aplicacion.tsx`).
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
      // igual.
      return json({}, 404);
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
});

interface BotonDelPie {
  readonly acto: string;
  readonly rotulo: string;
  readonly sePuedePulsar: boolean;
}

/** Monta la aplicacion en ese destino y devuelve el pie tal como queda en el DOM. */
async function elPieDe(
  clave: string,
): Promise<{ readonly aviso: string; readonly botones: readonly BotonDelPie[] }> {
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
  const botones = [...pie.querySelectorAll('button[data-acto]')].map((b) => ({
    acto: b.getAttribute('data-acto') ?? '',
    rotulo: (b.textContent ?? '').trim(),
    sePuedePulsar: !(b as HTMLButtonElement).disabled,
  }));
  return { aviso: (parrafo?.textContent ?? '').trim(), botones };
}

describe('el pie de cada pantalla ofrece lo que su hoja declara', () => {
  it('EL CENTINELA: hay destinos de las dos clases, y los diez paneles', () => {
    // Sin esto, un catalogo vacio —o uno en el que todas las hojas cayeran del mismo lado— dejaria
    // el `it.each` de abajo midiendo una sola rama, y en verde. Es como este arbol ya se quedo sin
    // guarda dos veces (#78, #80). Las cuentas del dia en que se escribio son 15 que escriben y 25
    // de consulta; no se fijan aqui a proposito, porque una hoja que gane una escritura de verdad
    // las mueve y eso NO es un defecto. Lo que no puede moverse es que haya de las dos.
    expect(DESTINOS).toHaveLength(40);
    expect(DESTINOS.filter((d) => d.escribe).length, 'ninguna hoja declara una escritura')
      .toBeGreaterThan(0);
    expect(DESTINOS.filter((d) => !d.escribe).length, 'todas las hojas escriben').toBeGreaterThan(0);
    // Uno por modulo. Si algun dia son nueve u once, esta guarda tiene que enterarse.
    expect(PANELES).toHaveLength(10);
    // Y el caso que motivo el issue: ningun panel declara una escritura. Si alguno la declarara,
    // la comprobacion de abajo le admitiria «Guardar» y nadie notaria que la promesa volvio.
    expect(PANELES.filter((p) => p.escribe)).toEqual([]);
  });

  // **20 s por destino, y no los 5 de por omision.** Montar la aplicacion entera cuarenta veces
  // en jsdom pasa de los cinco segundos en cuanto el puesto esta cargado —medido: el mismo destino
  // tarda 0,8 s solo y mas de 5 con otros arboles compilando al lado—, y un rojo por tiempo no
  // dice nada de lo que esta guarda mide. El tope sigue existiendo: una pantalla que no monte
  // nunca sigue saliendo roja, solo que mas tarde.
  it.each(DESTINOS)('«$clave» — $rotulo', { timeout: 20_000 }, async ({ clave, escribe }) => {
    const { aviso, botones } = await elPieDe(clave);
    const actos = botones.map((b) => b.acto);

    if (escribe) {
      expect(
        actos,
        `«${clave}» declara una operacion que escribe, asi que su pie ofrece limpiar y guardar.`,
      ).toEqual(['limpiar', 'guardar']);
      expect(aviso).toBe(AVISOS_DE_V8.escritura);
      return;
    }

    expect(
      actos,
      `El pie de «${clave}» ofrece «Guardar», y esta hoja NO declara ninguna operacion que\n` +
        '  escriba: ni un POST, ni un PUT, ni un PATCH. Lo que se escribe en ella son sus\n' +
        '  FILTROS, y un filtro no se guarda.\n\n' +
        '  Ademas el boton que el aviso nombra no se puede pulsar: este sistema solo atiende\n' +
        '  `imprimir` (ver `ACCIONES` en `aplicacion.tsx`), asi que «Guardar» se dibuja\n' +
        '  deshabilitado. Es el defecto que #291 cerro: la pantalla prometia un acto que no\n' +
        '  existe, en las diez pantallas de panel entre otras.',
    ).toEqual(['exportar', 'imprimir']);
    expect(aviso).toBe(AVISOS_DE_V8.consulta);

    const principal = botones[1];
    expect(
      principal?.sePuedePulsar,
      `«${clave}» ofrece «${principal?.rotulo ?? ''}» y no se puede pulsar.\n` +
        '  `imprimir` es el UNICO acto que este sistema atiende, y esta es la rama donde se\n' +
        '  ofrece. Si esto sale rojo, alguien lo quito de `ACCIONES` y las 25 pantallas de\n' +
        '  consulta se quedaron con dos botones muertos.',
    ).toBe(true);
  });
});
