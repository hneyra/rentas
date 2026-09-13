import { ProveedorDeTema } from '@kamayuk/ui';
import { cleanup, render, screen } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest';

import { MandoDeTema } from '../src/preferencias/MandoDeTema.tsx';
import { CATALOGO } from '../src/catalogo.ts';
import i18n, { ABRE, CIERRA, IDIOMA_MARCADO, IDIOMA_POR_OMISION } from '../src/i18n/i18n.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { Pantalla } from '../src/pantallas/Pantalla.tsx';
import { porQueNoHayDato } from '../src/porQueNoHayDato.ts';
import { hojaDe } from '../src/pantallas/arbol.ts';

/**
 * **Ninguna cadena llega al DOM sin pasar por `t()`** (#103, AC2).
 *
 * <h2>Por que se MONTA y no se barren las fuentes</h2>
 *
 * Un escaner de fuentes contesta otra pregunta —«¿hay literales en el codigo?»— y contesta mal las
 * dos direcciones: da rojos sobre cadenas que nunca se dibujan (una clave de consulta, un
 * `data-slot`) y **se calla sobre las que si**, porque no sabe cuales llegan a la pantalla.
 *
 * Montando, la pregunta es la de verdad: **lo que se ve**. Se cambia el idioma a uno que envuelve
 * todo lo que traduce entre `⟦` y `⟧`, se dibujan las cuarenta pantallas, y **lo que salga sin
 * marcar es texto que se escapo de `t()`**. No hay forma de que un literal se cuele y esto lo
 * ignore: si se ve, se mide.
 *
 * <h2>Lo que NO tiene que estar marcado, y por que</h2>
 *
 * **Los datos.** Un importe, una fecha, un codigo predial: traducirlos seria absurdo y ademas
 * falso —«S/ 9,418,204.60» no tiene traduccion—. Como hoy ninguna pantalla trae datos en esta
 * prueba —se montan sin conector—, lo que queda son los HUECOS, que si son frases nuestras.
 *
 * Y los separadores que el propio artboard dibuja: la raya de un dato vacio y la barra de la miga.
 */

/** Lo que puede aparecer sin marcar sin que sea un defecto. Ver el javadoc. */
const NO_ES_TEXTO = new Set(['—', '/', '·', ':', '(opcional)']);

beforeAll(async () => {
  await i18n.changeLanguage(IDIOMA_MARCADO);
});

afterAll(async () => {
  await i18n.changeLanguage(IDIOMA_POR_OMISION);
});

afterEach(cleanup);

/** Todo el texto visible del documento, trozo a trozo. */
function textoSuelto(raiz: HTMLElement): readonly string[] {
  const paseo = document.createTreeWalker(raiz, NodeFilter.SHOW_TEXT);
  const trozos: string[] = [];
  let nodo = paseo.nextNode();
  while (nodo !== null) {
    const texto = (nodo.textContent ?? '').trim();
    if (texto !== '') trozos.push(texto);
    nodo = paseo.nextNode();
  }
  return trozos;
}

/** Lo que se escapo: trozos con contenido que no van envueltos. */
function sinTraducir(raiz: HTMLElement): readonly string[] {
  return textoSuelto(raiz).filter(
    (trozo) => !trozo.startsWith(ABRE) && !NO_ES_TEXTO.has(trozo),
  );
}

describe('ninguna cadena llega al DOM sin pasar por `t()`', () => {
  it('EL CENTINELA: el idioma marcado MARCA de verdad', () => {
    // Sin esto, un `parseMissingKeyHandler` que dejara de envolver haria que todo lo de abajo
    // pasara en verde: nada estaria marcado y nada se consideraria escapado. La guarda se quedaria
    // sin sujeto siendo su propio arnes lo que falla.
    expect(i18n.language).toBe(IDIOMA_MARCADO);
    expect(i18n.t('Una frase cualquiera')).toBe(`${ABRE}Una frase cualquiera${CIERRA}`);
  });

  it.each(CATALOGO.flatMap((m) => m.destinos.map((d) => [d.clave] as const)))(
    '«%s» no ensena una sola cadena sin traducir',
    (clave) => {
      const { container } = render(
        <Pantalla
          definicion={pantallaDe(clave as ClaveDeHoja)}
          datos={{ ausencia: porQueNoHayDato(hojaDe(clave as ClaveDeHoja)) }}
        />,
      );
      const escapadas = sinTraducir(container);
      expect(
        escapadas,
        `«${clave}» dibuja texto que no paso por «t()»:\n` +
          `${escapadas.map((e) => `  «${e}»`).join('\n')}\n\n` +
          '  Ese texto no se puede traducir nunca, y nadie lo ve hasta que alguien pide un\n' +
          '  segundo idioma y aparece una pantalla a medias.',
      ).toEqual([]);
    },
  );

  /**
   * **El mando de preferencias tambien** (#111).
   *
   * Es la unica pieza que este repositorio dibuja fuera del interprete, asi que es la unica que el
   * recorrido de las cuarenta **no** puede ver: no es una pantalla y no esta en el catalogo. Sus
   * once cadenas —los rotulos de los dos ejes, las tres identidades, los tres modos y las tres
   * notas— llegarian al DOM sin que nadie mirase.
   *
   * Se lee de `document.body` y no del contenedor porque el cajon sale en un portal: lo que se
   * dibuja no cuelga de lo que `render` devuelve.
   */
  it('y el mando de preferencias, que no es una pantalla y por eso se le olvida a todo el mundo', () => {
    render(
      <ProveedorDeTema configuracion={{ identidadPorOmision: 'institucional', prefijoDeClaves: 'kamayuk.prueba' }}>
        <MandoDeTema abierto alCerrar={() => {}} />
      </ProveedorDeTema>,
    );
    const escapadas = sinTraducir(document.body);
    expect(
      escapadas,
      'El mando de preferencias dibuja texto que no paso por «t()»:\n' +
        `${escapadas.map((e) => `  «${e}»`).join('\n')}`,
    ).toEqual([]);
  });

  it('y el CENTINELA de la otra direccion: con el idioma normal NO hay marcas', async () => {
    // Sin esta mitad, la de arriba pasaria igual con un locale que envolviera SIEMPRE — incluso en
    // castellano—, y estariamos comprobando que el arnes funciona, no que la pantalla traduce.
    await i18n.changeLanguage(IDIOMA_POR_OMISION);
    render(
      <Pantalla
        definicion={pantallaDe('ini-panel')}
        datos={{ ausencia: porQueNoHayDato(hojaDe('ini-panel')) }}
      />,
    );
    expect(screen.queryByText(new RegExp(ABRE))).toBeNull();
    expect(screen.getByText('Ejercicio en curso')).toBeTruthy();
    await i18n.changeLanguage(IDIOMA_MARCADO);
  });
});
