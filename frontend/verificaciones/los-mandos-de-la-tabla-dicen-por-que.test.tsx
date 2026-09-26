import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { useState } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { CambioDeLaRuta, HojaDelMarco, RutaDeLaHoja } from '@kamayuk/ui';

import { useDatosDeLaHoja } from '../src/datos/useDatosDeLaHoja.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';
import { PantallaDeRentas } from '../src/pantallas/PantallaDeRentas.tsx';

/**
 * **La tabla paginada, montada: lo que se ve, lo que se lee y lo que el mando escribe** (#186,
 * #187).
 *
 * <h2>Las tres cosas que ninguna prueba sobre el reparto puede decir</h2>
 *
 * <ol>
 *   <li><b>Que el conteo se LEA</b>. El conector entrega `totalElementos: 84182` —un numero— y lo
 *       que tiene que aparecer es «1 de 84 182», con el «de» traducido y el separador de miles del
 *       artboard. Entre las dos cosas hay un `t()` y un `formatearEntero` que solo se ejercitan
 *       montando.</li>
 *   <li><b>Que la NOTA de una celda sin dato llegue al DOM</b> (#187, AC1). El conector la pone en
 *       la celda; el interprete la anuncia con `title`. Si se perdiera por el camino, la celda
 *       seguiria dibujando la raya y nadie notaria nada — que es exactamente lo que pasaba antes
 *       de este issue, cuando el motivo vivia en el javadoc del conector.</li>
 *   <li><b>Que el mando impedido diga POR QUE</b> (#186, AC5). `aria-disabled` y nunca `disabled`:
 *       el boton sigue en el orden del tabulador y quien navega con teclado llega a el y se entera
 *       de donde esta. Un `disabled` mudo deja concluir lo que se le ocurra a cada uno.</li>
 * </ol>
 *
 * <h2>Y la cuarta, que es la que hace que la paginacion PAGINE</h2>
 *
 * Que pulsar «Siguiente» **mueva la ruta de la hoja**, que es lo unico que el conector lee. El
 * marco de mentira de aqui abajo apunta lo que se le pide mover, asi que este caso falla si el
 * interprete guardara la pagina en su estado —que es lo que hace sin `hoja`— o si `aplicacion.tsx`
 * dejara de pasarsela.
 */

/** La bitacora tal como la instalacion la contesta, recortada a lo que el conector usa. */
const BITACORA = {
  contenido: [
    {
      id: 41184,
      ejercicio: 2026,
      tabla: 'recibo',
      clave: '0003-0041184',
      operacion: 'ANULACION',
      usuario: 'jcardenas',
      origenEquipo: 'PC-CAJA-02',
      origenIp: '10.0.4.12',
      fecha: '2026-08-13T09:41:12-05:00',
      observacion: 'Anulado por duplicado a pedido del contribuyente',
      datosAnteriores: '{}',
      datosNuevos: '{}',
    },
  ],
  pagina: 0,
  tamano: 20,
  totalElementos: 84182,
  totalPaginas: 4210,
  hayMas: true,
};

const SESION = { id: 9, usuarioId: 1, inicio: '2026-09-17T03:00:00-05:00', ejercicioDeTrabajo: 2026 };

function contesta() {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      const cuerpo = url.includes('/seguridad/sesion') ? SESION : BITACORA;
      return Promise.resolve(
        new Response(JSON.stringify(cuerpo), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      );
    }),
  );
}

/** Un marco de mentira: apunta lo que se le pide mover en vez de tocar `window.location`. */
function marcoDeMentira(parametros: Readonly<Record<string, string>> = {}) {
  const movimientos: CambioDeLaRuta[] = [];
  const ruta: RutaDeLaHoja = { sujeto: null, parametros };
  const hoja: HojaDelMarco = {
    ruta,
    moverLaRuta: (cambio) => movimientos.push(cambio),
  };
  return { hoja, movimientos };
}

function Bitacora({ hoja }: { readonly hoja: HojaDelMarco }) {
  return (
    <PantallaDeRentas
      definicion={pantallaDe('seg-aud')}
      datos={useDatosDeLaHoja('seg-aud', hoja.ruta)}
      hoja={hoja}
    />
  );
}

async function dibujar(parametros: Readonly<Record<string, string>> = {}) {
  contesta();
  const { hoja, movimientos } = marcoDeMentira(parametros);
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const envoltorio = ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
  render(<Bitacora hoja={hoja} />, { wrapper: envoltorio });
  await waitFor(() => {
    expect(screen.queryByText('pidiendo…')).toBeNull();
  });
  return { movimientos };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('`seg-aud` dibujada: la tabla dice que es una ventana de 84 182', () => {
  it('el conteo dice «1 de 84 182» y no «1 registro»: el total lo publica la operacion', async () => {
    await dibujar();

    // El separador de miles es el del artboard —`S/ 1,842.60`— y el «de» viene del saco, o sea de
    // `t()`. Con `contenido.length` diria «1 de 1» sobre una bitacora de 84 182 movimientos.
    expect(screen.getByText('1 de 84,182')).toBeInTheDocument();
    expect(screen.queryByText('1 registro')).toBeNull();
  });

  it('LA NOTA de «Riesgo» llega al DOM, que es lo que una raya suelta no podia decir (#187)', async () => {
    await dibujar();

    const celda = document.querySelector('[data-celda-sin-dato]');
    expect(celda, 'ninguna celda se dibujo como «sin dato»').not.toBeNull();
    // La palabra la declara la tabla en su `sinDato`; la nota la pone la celda, y es el motivo
    // que hasta #187 vivia solo en el javadoc del conector.
    expect(celda?.textContent).toBe('—');
    expect(celda?.getAttribute('title')).toContain('AuditoriaResource');
  });

  it('«Anterior» esta impedido en la primera pagina, y DICE POR QUE (#186, AC5)', async () => {
    await dibujar();
    const anterior = document.querySelector('[data-mando="anterior"]');

    expect(anterior).not.toBeNull();
    // `aria-disabled` y NUNCA `disabled`: sigue en el orden del tabulador, y quien navega con
    // teclado llega a el y se entera de donde esta.
    expect(anterior?.getAttribute('aria-disabled')).toBe('true');
    expect(anterior?.hasAttribute('disabled')).toBe(false);
    expect(screen.getByText('Esta es la primera pagina: no hay ninguna antes.')).toBeInTheDocument();
  });

  it('y «Siguiente» NO esta impedido, porque el SERVIDOR dijo que hay mas', async () => {
    await dibujar();
    const siguiente = document.querySelector('[data-mando="siguiente"]');

    // `hayMas` viene del envoltorio. Contando las filas recibidas —una— esto diria que no hay
    // pagina siguiente sobre una bitacora de 4 210 paginas.
    expect(siguiente?.getAttribute('aria-disabled')).not.toBe('true');
    expect(screen.getByText('Pagina 1 de 4210')).toBeInTheDocument();
  });

  it('pulsar «Siguiente» MUEVE LA RUTA, que es lo unico que el conector lee', async () => {
    const { movimientos } = await dibujar();
    (document.querySelector('[data-mando="siguiente"]') as HTMLElement).click();

    // Sin esto, el mando se dibujaria, se pulsaria, y la tabla seguiria en la pagina 0: una
    // paginacion que no pagina, en verde.
    expect(movimientos).toEqual([{ parametros: { pagina: '1' } }]);
  });

  it('y con la pagina 3 en la ruta, la peticion la lleva y la barra la dice', async () => {
    await dibujar({ pagina: '3' });
    const pedidas = vi.mocked(globalThis.fetch).mock.calls.map((llamada) => String(llamada[0]));

    expect(pedidas.some((url) => url.includes('/seguridad/auditoria') && url.includes('pagina=3'))).toBe(
      true,
    );
    expect(screen.getByText('Pagina 4 de 4210')).toBeInTheDocument();
  });

  it('cambiar de ORDEN vuelve a la primera pagina, y en UN solo movimiento', async () => {
    // Con dos `moverLaRuta` seguidos el marco pasa por una direccion intermedia —el orden nuevo
    // con la pagina vieja— que nadie pidio, y quien escucha la ruta para pedir **la pide**.
    const { movimientos } = await dibujar({ pagina: '3' });
    const sentido = document.querySelector('[data-sentido]') as HTMLElement;
    sentido.click();

    expect(movimientos).toHaveLength(1);
    expect(movimientos[0]).toEqual({
      parametros: { pagina: '0', sentido: 'DESCENDENTE' },
    });
  });
});

/**
 * **Pasar de pagina no se lleva el foco** (#393).
 *
 * La siembra que distingue es la PROMESA RETENIDA: la pagina 1 no contesta hasta que la prueba lo
 * dice. Con un `fetch` que resuelve en la misma vuelta, la espera dura una microtarea y la prueba
 * pasaria por suerte. Y el marco es de verdad en lo que importa: mover la ruta vuelve a dibujar la
 * hoja con la ruta nueva, que es lo que el de arriba no hace.
 */
describe('`seg-aud`: pasar de pagina con teclado (#393)', () => {
  const PAGINA_DOS = {
    ...BITACORA,
    contenido: [{ ...BITACORA.contenido[0], id: 41183, usuario: 'mrios' }],
    pagina: 1,
  };

  function conLaPaginaUnoRetenida(): { soltar: () => void } {
    let soltar: () => void = () => undefined;
    const retenida = new Promise<void>((resolver) => {
      soltar = resolver;
    });
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(async (entrada) => {
        const url = String(entrada);
        const responde = (cuerpo: unknown) =>
          new Response(JSON.stringify(cuerpo), {
            status: 200,
            headers: { 'content-type': 'application/json' },
          });
        if (url.includes('/seguridad/sesion')) return responde(SESION);
        if (url.includes('pagina=1')) {
          await retenida;
          return responde(PAGINA_DOS);
        }
        return responde(BITACORA);
      }),
    );
    return { soltar: () => soltar() };
  }

  function BitacoraConRuta() {
    const [parametros, moverA] = useState<Readonly<Record<string, string>>>({});
    const hoja: HojaDelMarco = {
      ruta: { sujeto: null, parametros },
      // Un `null` en el cambio quita el parametro, como hace el marco.
      moverLaRuta: (cambio) =>
        moverA((antes) => {
          const despues: Record<string, string> = { ...antes };
          for (const [nombre, valor] of Object.entries(cambio.parametros ?? {})) {
            if (valor === null) delete despues[nombre];
            else despues[nombre] = valor;
          }
          return despues;
        }),
    };
    return <Bitacora hoja={hoja} />;
  }

  it('«Siguiente» conserva el foco y las filas mientras llega la pagina pedida', async () => {
    const { soltar } = conLaPaginaUnoRetenida();
    const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={cliente}>
        <BitacoraConRuta />
      </QueryClientProvider>,
    );
    await waitFor(() => {
      expect(screen.getByText('jcardenas')).toBeInTheDocument();
    });

    const siguiente = document.querySelector('[data-mando="siguiente"]') as HTMLElement;
    siguiente.focus();
    act(() => {
      siguiente.click();
    });
    await waitFor(() => {
      const pedidas = vi.mocked(globalThis.fetch).mock.calls.map((llamada) => String(llamada[0]));
      expect(pedidas.some((url) => url.includes('pagina=1'))).toBe(true);
    });

    // Con la pagina 1 RETENIDA: la tabla no se vacia y el boton sigue siendo el mismo, con el foco.
    expect(document.activeElement, 'el foco cayo en <body>: se desmonto el mando').toBe(siguiente);
    expect(screen.getByText('jcardenas')).toBeInTheDocument();
    // Y la discordancia —«Pagina 2» sobre las filas de la 1— se dice, no se esconde.
    expect(document.body.textContent).toContain('Actualizando');

    await act(async () => {
      soltar();
    });
    await waitFor(() => {
      expect(screen.getByText('mrios')).toBeInTheDocument();
    });
    expect(document.activeElement).toBe(siguiente);
    expect(document.body.textContent).not.toContain('Actualizando');
  });
});
