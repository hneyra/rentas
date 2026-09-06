import { render, screen } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';

import { desinstalarProxyDeDatos, instalarProxyDeDatos } from '../api/proxy.ts';
import { Marco } from '../marco/Marco.tsx';
import { SECCIONES } from '../marco/arbol.ts';

/**
 * **El modulo entero recorre** (AC9 de #8): las cuatro secciones de Rentas se abren por su slug
 * y cada una dibuja algo que **solo existe con los datos puestos**.
 *
 * <h2>Por que no basta con esperar al titulo</h2>
 *
 * Porque el titulo lo pone el catalogo. `SECCIONES` declara el rotulo de las cuatro y el marco
 * lo dibuja en cuanto la pestana se abre, **haya llegado o no un solo dato**: una prueba que
 * esperara «Determinación» estaria verde con la pantalla en blanco, con el proxy apagado y con
 * la seccion sin escribir. Lo que se espera aqui es una cifra o un nombre que **no puede estar**
 * si la peticion no salio: el autovaluo del conjunto, el nombre de un contribuyente del padron,
 * la UIT del ejercicio.
 *
 * Y eso no se afirma: se mide. El ultimo grupo apaga el proxy y comprueba las dos mitades — que
 * los cuatro titulos siguen ahi, y que las cuatro marcas de dato desaparecen—. Si el testigo de
 * una seccion fuera del catalogo y no del dato, ese grupo lo dice.
 */

beforeAll(() => {
  instalarProxyDeDatos();
});

afterAll(() => {
  desinstalarProxyDeDatos();
});

afterEach(() => {
  window.history.replaceState(null, '', '/');
});

/**
 * El recorrido: para cada seccion, su slug, el titulo que pone el catalogo y **la marca que solo
 * aparece con los datos**.
 */
const RECORRIDO = [
  {
    slug: 'panel',
    titulo: 'Panel de Rentas',
    // Es `kpis[0].label` de `GET /indicadores/recaudacion`.
    conDatos: 'Emitido del ejercicio',
  },
  {
    slug: 'contribuyentes',
    titulo: 'Contribuyentes',
    // Un contribuyente del padron que sirve `GET /rentas/contribuyentes`.
    conDatos: 'Suc. Rufina Medina Medina',
  },
  {
    slug: 'determinacion',
    titulo: 'Determinación',
    // El valuo total de `POST /rentas/predial/calculo-individual`, ya formateado.
    conDatos: 'S/ 170,616.75',
  },
  {
    slug: 'valores',
    titulo: 'Valores del ejercicio 2026',
    // El concepto de la primera fila de la escala, compuesto con el ejercicio de la respuesta.
    conDatos: 'UIT 2026',
  },
] as const;

/** Abre la aplicacion directamente sobre ese slug, como haria una recarga. */
function abrirEn(slug: string) {
  window.history.replaceState(null, '', `#${slug}`);
  render(<Marco />);
}

describe('AC9 — las cuatro secciones se abren por su slug y dibujan sus datos', () => {
  it('el recorrido cubre las CUATRO secciones que declara el catalogo', () => {
    // Sin esto, borrar una entrada de `RECORRIDO` dejaria el `it.each` de abajo mas corto y en
    // verde: recorreria tres secciones diciendo que recorre el modulo entero.
    expect(RECORRIDO.map((paso) => paso.slug).sort()).toEqual(
      SECCIONES.map((seccion) => seccion.slug).sort(),
    );
  });

  it.each(RECORRIDO.map((paso) => [paso.slug, paso] as const))(
    '«#%s» dibuja lo que solo se ve con los datos',
    async (_slug, paso) => {
      abrirEn(paso.slug);

      expect(await screen.findByText(paso.conDatos)).toBeInTheDocument();
      expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(paso.titulo);
    },
  );
});

describe('la marca de cada seccion es del DATO y no del catalogo', () => {
  it.each(RECORRIDO.map((paso) => [paso.slug, paso] as const))(
    'sin proxy, «#%s» conserva su titulo y pierde su marca',
    async (_slug, paso) => {
      desinstalarProxyDeDatos();
      try {
        abrirEn(paso.slug);

        // El titulo sale igual: lo pone el catalogo, y por eso esperarlo no probaria nada.
        expect(screen.getByRole('heading', { level: 1 }).textContent).toBe(paso.titulo);
        // Y la marca no llega, porque no hay quien conteste.
        await screen.findAllByRole('alert');
        expect(screen.queryByText(paso.conDatos)).toBeNull();
      } finally {
        instalarProxyDeDatos();
      }
    },
  );
});
