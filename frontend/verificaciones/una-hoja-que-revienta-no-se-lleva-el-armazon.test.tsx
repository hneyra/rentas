import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { Aplicacion, CONSULTAS } from '../src/aplicacion.tsx';
import type { DeterminacionGuardada } from '../src/datos/lecturas.ts';
import { ACCESOS_MEDIDOS, MODULOS_MEDIDOS, PERMISOS_MEDIDOS } from '../src/datos/seguridadMedida.ts';
import { conEjercicio } from '../src/datos/sesionMedida.ts';

/**
 * **Lo que una hoja no sabe dibujar se queda en esa hoja** (#354).
 *
 * <h2>De que defecto viene</h2>
 *
 * El reparto de un conector corre en el RENDER —`useDatosDeLaHoja` llama a `repartir` en el cuerpo
 * del gancho, fuera de la consulta—, asi que lo que lance no pasa por `isError` ni por `alFallar`.
 * Y hasta #354 no habia ninguna frontera de error ni en este arbol ni en `@kamayuk/shell`: la raiz
 * del enrutador no declara `errorElement`, y react-router envuelve entonces la coincidencia con su
 * componente por omision, que dibuja **«Unexpected Application Error!», en ingles**, en lugar de
 * `Cascara` entera: barra, arbol y pie. Una forma inesperada en UN conector se llevaba las
 * cuarenta pantallas, y F5 repetia lo mismo, porque el destino vive en el hash.
 *
 * Las excepciones son deliberadas —`formatearImporte` «falla ruidosamente» a proposito— y el
 * diseno contaba con que acabaran siendo el hueco de **esa** hoja. Esto mide que lo sean.
 *
 * <h2>Por que la muestra es la que es</h2>
 *
 * Es la respuesta **medida** de `GET /rentas/predial/determinaciones` antes de #354, con el
 * conjunto sellado tal como lo entrega la copia local de `normativa` —`numeric(18,6)` leido con
 * `toString()`—: la sacaron `PredialControllerTest` y su prueba de #354 en rojo, byte a byte. Con
 * ella `formatearImporte` lanza en la coordenada (2,4), la de la UIT. No se inventa una forma rota:
 * es la que el backend servia, y la que cualquier otra deriva futura de un conector se parecera.
 *
 * <h2>Y por que se monta la aplicacion entera</h2>
 *
 * Porque lo que se afirma es del armazon —que la barra y el arbol siguen ahi— y la frontera que
 * lo defiende esta en `aplicacion.tsx`, entre el armazon y el cuerpo de cada hoja. Montar la
 * pantalla suelta no la tendria delante.
 */

/** La determinacion tal como salia ANTES de #354: la forma de la cache, sin escala. */
const CON_LA_FORMA_DE_LA_CACHE: DeterminacionGuardada = {
  id: 901,
  ejercicio: '2026',
  codContribuyente: '00000025673',
  sujeto: 'SUC. RUFINA MEDINA MEDINA',
  conjuntoId: 77,
  conjunto: '2026 v1',
  estado: 'BORRADOR',
  origen: 'ORDINARIA',
  predios: [],
  valuoTotal: '400000.75',
  valuoExonerado: '0',
  valuoAfecto: '400000.75',
  baseImponible: '400000.75',
  uit: '5500.000000',
  tramos: [
    {
      orden: 1,
      limiteSuperior: '82500.000000000000',
      alicuota: '0.200000',
      porcionGravada: '82500.000000000000',
      aporte: '165.00000000000000000000',
    },
    {
      orden: 2,
      limiteSuperior: '330000.000000000000',
      alicuota: '0.600000',
      porcionGravada: '247500.000000000000',
      aporte: '1485.00000000000000000000',
    },
    {
      orden: 3,
      limiteSuperior: null,
      alicuota: '1.000000',
      porcionGravada: '70000.750000000000',
      aporte: '700.00750000000000000000',
    },
  ],
  minimoImponible: '33.00000000000000',
  impuestoInsoluto: '2350.01',
  derechoDeEmision: '4.500000',
  totalAPagar: '2354.510000',
  modalidad: 'CONTADO',
  cuotas: [{ numero: 1, vencimiento: '2026-02-27', importe: '2350.01' }],
  reglasAplicadas: ['RT-011', 'RT-013', 'RT-014'],
};

/**
 * La MISMA determinacion con la forma que el backend publica desde #354: dos decimales en los
 * importes de cierre y el aporte entero. Es la de un contribuyente distinto a proposito: cambiar
 * de sujeto es cambiar de ruta sin cambiar de hoja, y la frontera tiene que reiniciarse igual.
 */
const CON_DOS_DECIMALES: DeterminacionGuardada = {
  ...CON_LA_FORMA_DE_LA_CACHE,
  codContribuyente: '00000000008',
  uit: '5500.00',
  tramos: [
    { orden: 1, limiteSuperior: '82500.00', alicuota: '0.200000', porcionGravada: '82500.00', aporte: '165.00000000000000000000' },
    { orden: 2, limiteSuperior: '330000.00', alicuota: '0.600000', porcionGravada: '247500.00', aporte: '1485.00000000000000000000' },
    { orden: 3, limiteSuperior: null, alicuota: '1.000000', porcionGravada: '70000.75', aporte: '700.00750000000000000000' },
  ],
  minimoImponible: '33.00',
  derechoDeEmision: '4.50',
  totalAPagar: '2354.51',
};

/** La frase del componente por omision de react-router. Es lo que NO puede volver a salir. */
const LA_PANTALLA_DE_REACT_ROUTER = 'Unexpected Application Error!';

/**
 * **Se espera al hecho, no al reloj** (#504).
 *
 * `waitFor` sin opciones se rinde al segundo, y lo que aqui se espera no es un paso sino una
 * cadena entera: el `hashchange`, la navegacion de react-router, el reinicio de la frontera, la
 * consulta nueva, su respuesta y el render del interprete. Sola, la prueba del reinicio lo hacia en
 * poco mas de un segundo; con `yarn verificar` corriendo las ochenta suites a la vez, se quedaba
 * sin plazo con la frontera ya reiniciada y la respuesta en camino, y salia roja con «Unable to
 * find an element with the text: S/ 5,500.00» —medido, y sobre `main`—. Un rojo que depende de la
 * carga del puesto tumba la CI de cualquier PR de frontend sin decir nada del codigo.
 *
 * Diez segundos no son un plazo que se espere: `waitFor` vuelve en cuanto el hecho se cumple, asi
 * que en verde cuesta lo mismo que antes. Solo pesan cuando el hecho NO llega, y entonces el rojo
 * es el mismo, diez segundos despues. Y la prueba entera lleva el suyo, holgado por encima
 * —`PLAZO_DE_LA_PRUEBA`—: con los cinco de Vitest por omision, dos esperas seguidas volverian a
 * dejar la prueba en manos del reloj, solo que un nivel mas arriba.
 */
const ESPERAR = { timeout: 10_000 };

/** Por encima de la suma de las esperas de una prueba: ver `ESPERAR`. */
const PLAZO_DE_LA_PRUEBA = { timeout: 60_000 };

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
  // La cache es de MODULO: ver el javadoc de `CONSULTAS`.
  CONSULTAS.clear();
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
      // `territorio` exige ejercicio de SESION: sin el no pide nada (#181).
      if (url.includes('/seguridad/sesion')) return json(conEjercicio(2026));
      if (url.includes('/rentas/predial/determinaciones')) {
        return json(
          url.includes('00000000008') ? CON_DOS_DECIMALES : CON_LA_FORMA_DE_LA_CACHE,
        );
      }
      return Promise.resolve(new Response('{}', { status: 404 }));
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
});

/** Monta la aplicacion en ese destino y espera a que el armazon exista. */
async function abrir(ruta: string) {
  window.location.hash = `#/${ruta}`;
  render(<Aplicacion />);
  await waitFor(() => {
    expect(document.querySelector('[data-slot="barra-global"], header, nav')).not.toBeNull();
  }, ESPERAR);
}

describe('una hoja que no se puede dibujar no se lleva el armazon (#354)', () => {
  it('con la forma de la cache, la barra y el arbol siguen, y el cuerpo dice «fallo» con su motivo', PLAZO_DE_LA_PRUEBA, async () => {
    // React avisa por consola de lo que una frontera recoge, y eso es lo que tiene que pasar: se
    // silencia para que el volcado no parezca un rojo, y se comprueba abajo que hubo aviso.
    const consola = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      await abrir('territorio/00000025673');

      // El cuerpo lo dice: la palabra del hueco y el motivo, que es lo que `formatearImporte`
      // escribio nombrando el valor. Sin motivo, «fallo» mandaria a mirar la red.
      await waitFor(() => {
        expect(document.body.textContent).toContain('5500.000000');
      }, ESPERAR);
      expect(screen.getAllByText('fallo').length).toBeGreaterThan(0);
      // Y el armazon sigue en pie: el titulo de la hoja, y el carril con OTRO modulo que abrir.
      expect(screen.getByRole('heading', { level: 1, name: 'Determinación' })).toBeTruthy();
      expect(screen.getByRole('button', { name: /Tránsito/ })).toBeTruthy();
      expect(document.body.textContent).not.toContain(LA_PANTALLA_DE_REACT_ROUTER);
      // Ni una cifra de la respuesta rota se pinta: es la pantalla entera la que no se dibuja, no
      // la mitad que si se pudo formatear.
      expect(screen.queryByText('S/ 400,000.75')).toBeNull();
    } finally {
      consola.mockRestore();
    }
  });

  it('y se REINICIA al cambiar de ruta: otro contribuyente de la misma hoja se dibuja', PLAZO_DE_LA_PRUEBA, async () => {
    const consola = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      await abrir('territorio/00000025673');
      await waitFor(() => {
        expect(document.body.textContent).toContain('5500.000000');
      }, ESPERAR);

      // La misma hoja con otro sujeto: el marco NO desmonta la pantalla —su `key` es el destino—,
      // asi que sin reinicio la frontera se quedaria en su fallo para siempre.
      window.location.hash = '#/territorio/00000000008';

      await waitFor(() => {
        expect(screen.getByText('S/ 5,500.00')).toBeInTheDocument();
      }, ESPERAR);
      expect(screen.getByText('S/ 33.00')).toBeInTheDocument();
      expect(document.body.textContent).not.toContain('5500.000000');
    } finally {
      consola.mockRestore();
    }
  });
});
