import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';

import { TEXTOS_DEL_ARMAZON, type TextosDelArmazon } from '@kamayuk/shell';
import { ProveedorDeTema, TEXTOS_DE_LA_UI, TEXTOS_DEL_INTERPRETE } from '@kamayuk/ui';
import { cleanup, fireEvent, render, renderHook, screen, waitFor } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { RAIZ } from './artboards.ts';

import { MandoDeTema } from '../src/preferencias/MandoDeTema.tsx';
import { Aplicacion, CONSULTAS } from '../src/aplicacion.tsx';
import { CATALOGO } from '../src/catalogo.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
} from '../src/datos/seguridadMedida.ts';
import i18n, { ABRE, CIERRA, IDIOMA_MARCADO, IDIOMA_POR_OMISION } from '../src/i18n/i18n.ts';
import {
  FRASES_DEL_INTERPRETE,
  FRASES_DEL_MARCO,
  useTextosDelInterprete,
  useTextosDelMarco,
} from '../src/i18n/textosDelMarco.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { PantallaDeRentas as Pantalla } from '../src/pantallas/PantallaDeRentas.tsx';
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

/**
 * Lo que puede aparecer sin marcar sin que sea un defecto. Ver el javadoc.
 *
 * **«(opcional)» salio de aqui en #133**, y es el unico que se ha ido: no era un separador del
 * artboard sino una palabra de `@kamayuk/ui` que llegaba al DOM sin pasar por `t()`. Lo que la
 * exencion hacia era taparlo. Hoy la pasa `PantallaDeRentas` —desde #153, en el saco `textos` del
 * interprete— y sale marcada como cualquier otra.
 *
 * Los dos nombres son DATOS de una persona, no frases: traducir «J. Cardenas Vega» seria falso.
 * Salen del marco —la cuenta de la barra— y por eso solo hacen falta al montar la aplicacion.
 */
const NO_ES_TEXTO = new Set(['—', '/', '·', ':', 'J. Cardenas Vega', 'JC']);

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

/**
 * **Los atributos que LLEVAN TEXTO, que son los que nadie mira** (#133).
 *
 * Ocho de las treinta y dos palabras del armazon no se dibujan en ninguna parte: seis son nombres
 * accesibles —el boton del carril, el menu de sesion, la miga, el dialogo de la paleta, su lista y
 * la region viva de los avisos— y dos son marcadores de una caja de texto. Un recorrido de nodos
 * de texto no ve ni una, asi que una guarda que solo mirase el texto dibujado diria que el marco
 * esta entero **teniendo ocho cadenas en el idioma equivocado**. Es como llegaron en ingles
 * `Notifications alt+T` de `sonner` y `Suggestions` de `cmdk`, sin que nadie lo notara.
 *
 * Solo estos cuatro, y no todos: `class`, `id`, `href` o un `data-slot` no son frases, y meterlos
 * convertiria la guarda en un ruido que alguien acabaria apagando.
 */
const ATRIBUTOS_CON_TEXTO = ['aria-label', 'placeholder', 'title', 'alt'] as const;

/** Lo que dicen esos atributos en todo el arbol, uno a uno. */
function textoDeLosAtributos(raiz: HTMLElement): readonly string[] {
  const salida: string[] = [];
  for (const elemento of raiz.querySelectorAll('*')) {
    for (const atributo of ATRIBUTOS_CON_TEXTO) {
      const valor = elemento.getAttribute(atributo)?.trim() ?? '';
      if (valor !== '') salida.push(valor);
    }
  }
  return salida;
}

/**
 * Lo que se escapo: trozos con contenido que no van envueltos.
 *
 * `tambienDato` es para lo que llega de la RED y por tanto no es nuestro. No tiene valor por
 * omision a proposito: lo que una pantalla suelta dibuja sale entero de sus definiciones, asi que
 * ahi no hay nada que eximir, y un valor por omision invitaria a eximir de mas.
 */
function sinTraducir(raiz: HTMLElement, tambienDato: ReadonlySet<string> = new Set()): readonly string[] {
  return [...textoSuelto(raiz), ...textoDeLosAtributos(raiz)].filter(
    (trozo) => !trozo.startsWith(ABRE) && !NO_ES_TEXTO.has(trozo) && !tambienDato.has(trozo),
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

/**
 * **Y el MARCO, que es la mitad que se escapaba** (#133).
 *
 * <h2>Lo que la guarda de arriba no puede ver, y lo dice su propia forma</h2>
 *
 * Las cuarenta de arriba montan `<Pantalla>` **suelta**. Eso mide el CUERPO, que es lo que este
 * repositorio dibuja — y deja fuera el marco entero: la barra, el carril, la paleta, la cabecera,
 * el pie y el aviso de cambios sin guardar, que los dibuja `@kamayuk/shell`. Con el nombre que
 * tiene esta guarda —«ninguna cadena llega al DOM sin pasar por `t()`»— eso no es un hueco: es una
 * afirmacion que no se sostiene, y por eso las treinta y dos palabras del armazon llevaban desde
 * `kamayuk-lib`#19 saliendo en castellano **con esta prueba en verde**.
 *
 * Asi que aqui se monta `<Aplicacion />` entera, con las tres de seguridad contestadas, y se exige
 * lo mismo que a una pantalla. El defecto de hoy no puede volver en silencio: el dia que la
 * libreria anada un texto y nadie lo enhebre, sale sin marcar y esto se pone rojo.
 *
 * <h2>Las tres comprobaciones, y por que no basta con montar</h2>
 *
 * Montar **no ve las treinta y dos**: ocho no se dibujan nunca —seis nombres accesibles y dos
 * marcadores—, y otras cuantas solo salen en un estado que esta prueba no provoca (la paleta
 * vacia, el aviso de cambios sin guardar). Por eso ademas se comprueba **el inventario** —que el
 * saco trae las mismas llaves que la libreria publica— y **que cada entrada del saco pasa por
 * `t()`**, llamando a las cuatro que llevan un dato dentro. Las tres juntas son las que hacen que
 * quitar una entrada tenga que ponerse rojo nombrandola.
 */

/**
 * **Los rotulos de los diez modulos, que NO son nuestros y por eso no van marcados.**
 *
 * Desde #105 los pisa `GET /seguridad/modulos`, y a proposito: el dia que la municipalidad
 * renombre un modulo, el arbol tiene que decir el nombre nuevo. Traducirlos los devolveria al del
 * artboard si coincidieran y los dejaria a medias si no —las dos cosas malas a la vez—, asi que
 * `traducirCatalogo` los deja pasar tal cual y su javadoc lo explica.
 *
 * Salen de `MODULOS_MEDIDOS` —lo que contesta la instalacion— y no de una lista escrita aqui: una
 * copia a mano seria una lista que un dia diria otra cosa que el doble de la red, y entonces la
 * exencion taparia justo lo que no debe.
 */
const ROTULOS_DEL_BACKEND: ReadonlySet<string> = new Set(MODULOS_MEDIDOS.map((m) => m.nombre));

/** Con que se llaman las cuatro entradas que llevan un dato dentro. */
const MUESTRAS: Readonly<Record<string, readonly unknown[]>> = {
  avisosSinLeer: [3],
  nadaCasaEnElArbol: ['predial'],
  cuantosDestinos: [1, 40],
  hayCambiosSinGuardar: ['Papeletas'],
};

/** El saco, entrada a entrada, convertido a la cadena que el marco dibujaria. */
function loQueDiceElSaco(saco: TextosDelArmazon): ReadonlyMap<string, string> {
  const dicho = new Map<string, string>();
  for (const [clave, valor] of Object.entries(saco) as readonly (readonly [string, unknown])[]) {
    if (typeof valor === 'string') {
      dicho.set(clave, valor);
      continue;
    }
    const muestra = MUESTRAS[clave];
    if (typeof valor !== 'function' || muestra === undefined) {
      throw new Error(
        `«${clave}» no es una cadena y no tiene muestra con que llamarla.\n` +
          '  Anadela a MUESTRAS: una entrada del saco que esta prueba no sabe invocar es una\n' +
          '  entrada que no se esta midiendo.',
      );
    }
    dicho.set(clave, String((valor as (...datos: readonly unknown[]) => unknown)(...muestra)));
  }
  return dicho;
}

describe('y el marco tampoco: las treinta y dos palabras del armazon (#133)', () => {
  beforeAll(() => {
    // Lo que jsdom no trae y las piezas del armazon piden. Sus motivos, en `@kamayuk/shell`.
    Element.prototype.scrollIntoView = () => {};
    Element.prototype.hasPointerCapture = () => false;
    Element.prototype.releasePointerCapture = () => {};
    // `cmdk` observa el tamano de su lista y jsdom no trae el observador: sin esto la paleta
    // revienta con «ResizeObserver is not defined» y el rojo habla de jsdom, no de traducciones.
    globalThis.ResizeObserver ??= class {
      observe() {}
      unobserve() {}
      disconnect() {}
    };
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
    // La cache de consultas es de MODULO y sobrevive a cada `render`: ver `aplicacion.tsx`.
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
        return Promise.resolve(new Response('{}', { status: 404 }));
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    window.location.hash = '';
  });

  it('EL INVENTARIO: el saco trae las MISMAS llaves que publica la libreria', () => {
    // Es la mitad que no se ve montando: ocho de las treinta y dos no se dibujan en ninguna
    // parte. Quitar una del saco tiene que ponerse rojo NOMBRANDOLA, y aqui es donde pasa.
    const { result } = renderHook(() => useTextosDelMarco());
    const faltan = Object.keys(TEXTOS_DEL_ARMAZON).filter((c) => !(c in result.current));
    const sobran = Object.keys(result.current).filter((c) => !(c in TEXTOS_DEL_ARMAZON));

    expect(
      { faltan, sobran },
      'El saco de textos del marco dejo de cuadrar con el de «@kamayuk/shell».\n' +
        '  Lo que falta lo dibuja el armazon con su castellano por omision, y nadie lo ve hasta\n' +
        '  que alguien pide un segundo idioma y aparece media pantalla sin traducir.\n' +
        '  Se arregla en `src/i18n/textosDelMarco.ts`.',
    ).toEqual({ faltan: [], sobran: [] });
  });

  it('y NINGUNA de las treinta y dos llega sin pasar por `t()`, ni las que no se dibujan', () => {
    const { result } = renderHook(() => useTextosDelMarco());
    const escapadas = [...loQueDiceElSaco(result.current)]
      .filter(([, dice]) => !dice.startsWith(ABRE))
      .map(([clave, dice]) => `  «${clave}» dice «${dice}»`);

    expect(
      escapadas,
      'Hay entradas del saco del marco escritas como literal en vez de pasar por «t()»:\n' +
        `${escapadas.join('\n')}\n\n` +
        '  Pasarlas por el saco y no traducirlas es el mismo defecto con un rodeo mas.',
    ).toEqual([]);
  });

  it('y las seis de `@kamayuk/ui` estan todas colocadas: ninguna se dibuja por omision', () => {
    // AC2. Tres de las seis viajan DENTRO del saco del armazon —la miga, la region de avisos y la
    // lista de la paleta—, la marca de opcional va en el saco del interprete (#153), y las dos que envuelven
    // una fecha son de `Importe` y `FechaDeCalculo`, que este repositorio no monta. Esta prueba
    // caduca sola el dia que alguna se monte: entonces se pone roja y dice cual.
    expect(FRASES_DEL_MARCO.ruta).toBe(TEXTOS_DE_LA_UI.ruta);
    expect(FRASES_DEL_MARCO.avisos).toBe(TEXTOS_DE_LA_UI.avisos);
    expect(FRASES_DEL_MARCO.sugerenciasDeLaPaleta).toBe(TEXTOS_DE_LA_UI.sugerencias);
    expect(FRASES_DEL_INTERPRETE.opcional).toBe(TEXTOS_DE_LA_UI.opcional);
    expect(
      loQueSeImportaDeLaUi().filter((p) => p === 'Importe' || p === 'FechaDeCalculo'),
      'Alguna pantalla monta «Importe» o «FechaDeCalculo», que dicen «al …» y «Cifras\n' +
        '  actualizadas al …» por su cuenta. Pasales `rotuloDeLaFecha` / `rotulo` por `t()`, y\n' +
        '  mete la frase en `textosDelMarco.ts` para que entre en el locale.',
    ).toEqual([]);
  });

  /**
   * **Y las tres del INTERPRETE** (#153), que son la mitad que la subida a `@kamayuk/ui` saco de
   * `t()`.
   *
   * Hasta #153 el interprete vivia aqui y llamaba a `t()` por su cuenta. Ahora las recibe en
   * `textos`. La marca de opcional y el marcador de fecha SI salen en el recorrido de las cuarenta
   * —hay campos opcionales y fechas sin elegir—, pero **el conteo no**: solo se escribe con filas,
   * y ninguna pantalla montada suelta las trae. Por eso, como con el armazon, se comprueba ademas
   * el inventario y que cada entrada pase por `t()`.
   */
  it('EL INVENTARIO del interprete: el saco trae las MISMAS llaves que publica la libreria', () => {
    const { result } = renderHook(() => useTextosDelInterprete());
    const faltan = Object.keys(TEXTOS_DEL_INTERPRETE).filter((c) => !(c in result.current));
    const sobran = Object.keys(result.current).filter((c) => !(c in TEXTOS_DEL_INTERPRETE));
    expect(
      { faltan, sobran },
      'El saco de textos del interprete dejo de cuadrar con el de «@kamayuk/ui».\n' +
        '  Se arregla en `src/i18n/textosDelMarco.ts`.',
    ).toEqual({ faltan: [], sobran: [] });
  });

  it('y NINGUNA de las tres del interprete llega sin pasar por `t()`', () => {
    const { result } = renderHook(() => useTextosDelInterprete());
    const dichas = {
      opcional: result.current.opcional,
      marcadorDeFecha: result.current.marcadorDeFecha,
      registros: result.current.registros(2),
    };
    const escapadas = Object.entries(dichas)
      .filter(([, dice]) => !dice.startsWith(ABRE))
      .map(([clave, dice]) => `  «${clave}» dice «${dice}»`);
    expect(escapadas, `Palabras del interprete sin pasar por «t()»:\n${escapadas.join('\n')}`).toEqual([]);
  });

  it('y en castellano dicen LO MISMO que la libreria: la subida no cambia lo que se lee', async () => {
    await i18n.changeLanguage(IDIOMA_POR_OMISION);
    try {
      const { result } = renderHook(() => useTextosDelInterprete());
      expect(result.current.opcional).toBe(TEXTOS_DEL_INTERPRETE.opcional);
      expect(result.current.marcadorDeFecha).toBe(TEXTOS_DEL_INTERPRETE.marcadorDeFecha);
      // El plural lo pone i18next y no el ternario de la libreria: tienen que coincidir en los dos.
      for (const cuantos of [1, 2, 40]) {
        expect(result.current.registros(cuantos), `con ${String(cuantos)}`).toBe(
          TEXTOS_DEL_INTERPRETE.registros(cuantos),
        );
      }
    } finally {
      await i18n.changeLanguage(IDIOMA_MARCADO);
    }
  });

  it('LA APLICACION ENTERA no ensena una sola cadena sin traducir, marco incluido', async () => {
    window.location.hash = '#/ini-panel';
    render(<Aplicacion />);
    await waitFor(() => {
      expect(document.querySelector('[data-slot="barra-global"], header, nav')).not.toBeNull();
    });

    const escapadas = sinTraducir(document.body, ROTULOS_DEL_BACKEND);
    expect(
      escapadas,
      'La aplicacion montada dibuja texto que no paso por «t()»:\n' +
        `${escapadas.map((e) => `  «${e}»`).join('\n')}\n\n` +
        '  Si son palabras del marco, van en `src/i18n/textosDelMarco.ts`.',
    ).toEqual([]);
  });

  it('y la PALETA tampoco, que es donde viven cuatro que no se ven de otro modo', async () => {
    window.location.hash = '#/ini-panel';
    render(<Aplicacion />);
    await waitFor(() => {
      expect(document.querySelector('[data-slot="barra-global"], header, nav')).not.toBeNull();
    });
    fireEvent.keyDown(window, { key: 'k', ctrlKey: true });
    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeTruthy();
    });

    const escapadas = sinTraducir(document.body, ROTULOS_DEL_BACKEND);
    expect(
      escapadas,
      'La paleta de mando dibuja texto que no paso por «t()»:\n' +
        `${escapadas.map((e) => `  «${e}»`).join('\n')}`,
    ).toEqual([]);
  });
});

/** Todos los `.ts`/`.tsx` de produccion bajo `src/`. */
function fuentesDeProduccion(desde = join(RAIZ, 'src')): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    if (statSync(ruta).isDirectory()) return fuentesDeProduccion(ruta);
    return /\.tsx?$/.test(entrada) && !/\.test\.tsx?$/.test(entrada) ? [ruta] : [];
  });
}

/**
 * Las piezas que `src/` importa de `@kamayuk/ui`, por su nombre.
 *
 * Se leen del `import` y no de un `grep` del archivo entero: este repositorio tiene un tipo del
 * dominio que se llama `Importe` —un texto con centimos— y buscar la palabra suelta daria rojo
 * sobre `dominio/aritmetica.ts`, que no dibuja nada.
 */
function loQueSeImportaDeLaUi(): readonly string[] {
  const piezas = new Set<string>();
  for (const ruta of fuentesDeProduccion()) {
    const fuente = readFileSync(ruta, 'utf8');
    for (const casado of fuente.matchAll(/import\s*\{([^}]*)\}\s*from\s*'@kamayuk\/ui'/g)) {
      for (const pieza of (casado[1] ?? '').split(',')) {
        const nombre = pieza.replace(/^\s*type\s+/, '').trim();
        if (nombre !== '') piezas.add(nombre);
      }
    }
  }
  return [...piezas];
}
