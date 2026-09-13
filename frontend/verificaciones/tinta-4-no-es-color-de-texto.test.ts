// @vitest-environment node
//
// Lee archivos del disco y los parte con el compilador de TypeScript. No es un DOM lo que necesita.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { ARTBOARDS, RAIZ, rutaDe } from './artboards.ts';
import { UMBRAL_DE_TEXTO, conDosDecimales, contraste } from './contraste.ts';
import {
  archivosDeInterfaz,
  hallazgosDe,
  losQueNoSonTexto,
  usaLaClase,
  valorDelToken,
} from './tinta-que-no-es-texto.ts';

/**
 * **`--tinta-4` no es color de texto, y aqui deja de poder serlo** (#140).
 *
 * <h2>El defecto del que viene</h2>
 *
 * El artboard lo escribe en su hoja de tokens y la libreria lo repite en mayusculas en la suya:
 * `--tinta-4` **NO ES COLOR DE TEXTO**. Sobre el lienzo da 2,39:1 y WCAG 1.4.3 pide 4,5:1; es el
 * trazo de un icono decorativo y nada mas. Los usos de `@kamayuk/{ui,shell}` lo respetan: los tres
 * que pintan un chevron, una flecha y el separador de una miga llevan `aria-hidden`.
 *
 * Y este repositorio lo usaba para **prosa que hay que leer**: las dos frases que explican los dos
 * ejes del mando de temas, a 12 px, en el cajon de **Preferencias** — donde vive el mando de
 * accesibilidad visual. La segunda es la unica que dice que hace la opcion «El del sistema», o sea
 * que quien no la lea no sabe que esta eligiendo.
 *
 * La regla estaba escrita en prosa **en dos hojas de estilo**, y una regla que solo vive en un
 * comentario se incumple en seis meses. Aqui se convierte en rojo.
 *
 * <h2>QUE BARRE: `src/`, y NO los `@kamayuk/*` enlazados. Medido</h2>
 *
 * La pregunta es real —`tailwind-emite-las-clases.test.ts` si barre `@kamayuk/ui`— y la respuesta
 * aqui es que no, por tres cosas que se midieron antes de decidirlo:
 *
 *   1. **La libreria no esta limpia hoy, y no son tres usos sino CUATRO.** Ademas del chevron, la
 *      flecha y el separador, `paquetes/ui/shadcn/calendario.tsx:50` pone `text-tinta-4` en
 *      `classNames.outside` del `DayPicker` — los dias del mes vecino, que son **numeros que se
 *      leen y se pulsan**, sin `aria-hidden` y sin poder llevarlo. O sea que barrer la libreria no
 *      es «verde hoy y guarda para manana»: es **rojo desde el primer commit**, por un archivo que
 *      este repositorio no puede tocar porque vive en otro.
 *   2. **Una guarda que se pone roja sola se silencia.** El hermano se enlaza con `link:` y no lo
 *      fija ningun lockfile: un `git pull` en `kamayuk-lib` puede poner en rojo el `yarn verificar`
 *      de los cuatro sistemas a la vez, y ninguno tendria como arreglarlo. La primera vez se abre
 *      un issue; la segunda se comenta la guarda.
 *   3. **La libreria ya declaro quien la vigila.** Su propia hoja termina la frase con «lo vigilara
 *      la guarda de contraste de UI-3». Ponerla tambien aqui es la misma regla en dos repositorios
 *      con duenos distintos, que es lo que ADR-0030 §4 desaconseja por el lado del contrato.
 *
 * Lo que si se hizo con el hallazgo es reportarlo, que es lo que un consumidor puede hacer.
 *
 * Y `src/` entero, no `src/preferencias`: es lo que
 * `docs/00-gobierno/verificar-fila-del-registro.mjs` declara como codigo de produccion de este
 * frontend, y acotarla al archivo del defecto la dejaria sin sujeto en cuanto alguien escriba la
 * clase en otro sitio.
 *
 * <h2>Por que el token no esta escrito aqui</h2>
 *
 * Sale de `diseno/rentas-tokens.css`, que es la hoja del artboard V8 vendorizada en este arbol y
 * ya declarada en `artboards.ts`. El artboard es la fuente: la libreria misma dice «lo dice el
 * artboard». Escribir «tinta-4» a mano seria una tercera copia, y el dia que la condicion se mueva
 * a otro token esta guarda seguiria vigilando el de ayer, en verde.
 *
 * Y el centinela no se cree la frase del artboard: **la calcula**. Si alguien aclarara el token
 * hasta hacerlo legible, el rojo dice que la regla se quedo sin motivo en vez de seguir
 * prohibiendo por costumbre.
 */

const HOJA_DEL_ARTBOARD = (() => {
  const declarada = ARTBOARDS.find((a) => a.archivo.endsWith('rentas-tokens.css'));
  if (declarada === undefined) throw new Error('La hoja de tokens no esta en `artboards.ts`.');
  return readFileSync(rutaDe(declarada), 'utf8');
})();

/** Los tokens que el artboard declara que NO se usan como texto. Hoy, uno. */
const NO_SON_TEXTO = losQueNoSonTexto(HOJA_DEL_ARTBOARD);

/** Los dos papeles sobre los que se dibuja: el lienzo y la superficie de una tarjeta o un cajon. */
const PAPELES = ['fondo', 'superficie'] as const;

/** Todo `src/`, que es lo que este repositorio declara codigo de produccion del frontend. */
const ARCHIVOS = archivosDeInterfaz(join(RAIZ, 'src'));

/**
 * Las fuentes inventadas con que se demuestra que la guarda muerde, y que no muerde de mas.
 *
 * Son el equivalente de `verificaciones/muestras/`: la regla se ejerce sobre codigo que la viola a
 * proposito **y** sobre codigo que la cumple. Sin la segunda mitad, una guarda que senalara todo
 * pasaria esta prueba igual de bien y dejaria el repositorio sin poder pintar un icono.
 */
const QUE_VIOLA: readonly { readonly que: string; readonly archivo: string; readonly fuente: string }[] = [
  {
    que: 'prosa a 12 px, que es el defecto de este issue',
    archivo: 'Mando.tsx',
    fuente: 'export const M = () => <p className="text-[12px] text-tinta-4">{t(nota)}</p>;\n',
  },
  {
    que: 'la clase escondida dentro de un `cn()`, que es como llega de verdad',
    archivo: 'Mando.tsx',
    fuente:
      "export const M = ({ a }: { a: boolean }) => <span className={cn('leading-[1.5]', a ? 'text-tinta-4' : 'text-tinta-3')}>x</span>;\n",
  },
  {
    que: '`aria-hidden` puesto en `false`, que es no llevarlo',
    archivo: 'Mando.tsx',
    fuente: 'export const M = () => <span aria-hidden={false} className="text-tinta-4">/</span>;\n',
  },
  {
    que: 'la clase fuera de todo JSX, donde no se puede saber sobre que cae',
    archivo: 'clases.ts',
    fuente: "export const NOTA = 'text-[12px] text-tinta-4';\n",
  },
  {
    que: 'la clase en un mapa de `classNames`, como la lleva el calendario de la libreria',
    archivo: 'Calendario.tsx',
    fuente:
      "export const C = () => <DayPicker classNames={{ outside: cn(porOmision.outside, 'text-tinta-4') }} />;\n",
  },
];

const QUE_CUMPLE: readonly { readonly que: string; readonly archivo: string; readonly fuente: string }[] = [
  {
    que: 'el trazo de un icono, con `aria-hidden` escrito',
    archivo: 'Icono.tsx',
    fuente:
      'export const I = () => <svg aria-hidden="true" className="size-[15px] text-tinta-4" />;\n',
  },
  {
    que: '`aria-hidden` a secas, que en JSX es `true`',
    archivo: 'Miga.tsx',
    fuente: "export const S = () => <span aria-hidden className={cn('text-tinta-4')}>/</span>;\n",
  },
  {
    que: 'la variante de un estado, sobre un elemento oculto',
    archivo: 'Chevron.tsx',
    fuente:
      'export const C = () => <span aria-hidden="true" className="text-tinta-3 hover:text-tinta-4" />;\n',
  },
  {
    que: 'otro token que empieza igual: `text-tinta-4` no esta dentro de `text-tinta-40`',
    archivo: 'Otro.tsx',
    fuente: 'export const O = () => <p className="text-tinta-40">x</p>;\n',
  },
  {
    que: 'la clase NOMBRADA en un comentario, que es como esta guarda se explica a si misma',
    archivo: 'Nota.tsx',
    fuente: '// `text-tinta-4` no es color de texto.\nexport const N = () => <p>x</p>;\n',
  },
];

describe('el token que no es color de texto', () => {
  it('EL CENTINELA: el artboard declara uno, hay archivos que barrer y la regla muerde', () => {
    // Sin esto, un cambio de redaccion en la hoja del artboard dejaria `NO_SON_TEXTO` vacio y la
    // guarda de abajo recorreria la lista vacia **en verde**, que es como este repositorio ya se
    // quedo sin guarda dos veces (#78, #90).
    expect(
      NO_SON_TEXTO.map((t) => t.token),
      'el artboard no declara ningun token como no-texto: la guarda se quedo sin sujeto',
    ).toEqual(['tinta-4']);

    // Y lo mismo por el otro lado: un `src/` que no se pudiera leer daria cero hallazgos y cero
    // hallazgos es exactamente lo que la guarda considera correcto.
    expect(ARCHIVOS.length, 'no se leyo ni un archivo de `src/`').toBeGreaterThanOrEqual(15);

    // La regla, ejercida sobre codigo inventado. Es la mitad que demuestra que puede fallar.
    for (const { que, archivo, fuente } of QUE_VIOLA) {
      expect(
        hallazgosDe(archivo, fuente, 'text-tinta-4'),
        `la guarda dejo pasar ${que}`,
      ).not.toEqual([]);
    }
    for (const { que, archivo, fuente } of QUE_CUMPLE) {
      expect(hallazgosDe(archivo, fuente, 'text-tinta-4'), `la guarda senalo ${que}`).toEqual([]);
    }

    // Y que el reconocedor de clases mire el token entero y no un trozo.
    expect(usaLaClase('text-[12px] text-tinta-4', 'text-tinta-4')).toBe(true);
    expect(usaLaClase('text-tinta-40', 'text-tinta-4')).toBe(false);
  });

  it('y NO se le cree al artboard: el token es ilegible sobre los dos papeles, calculado', () => {
    // Si alguien aclarara `--tinta-4` hasta hacerlo legible, esta guarda dejaria de tener motivo.
    // Mejor que lo diga aqui, que seguir prohibiendo por costumbre.
    for (const token of NO_SON_TEXTO) {
      for (const papel of PAPELES) {
        const valor = valorDelToken(HOJA_DEL_ARTBOARD, papel);
        expect(valor, `el artboard no declara --${papel}`).not.toBeNull();

        const razon = contraste(token.valor, valor as string);
        expect(
          razon,
          `--${token.token} (${token.valor}) sobre --${papel} (${valor as string}) da ` +
            `${conDosDecimales(razon)}:1, que YA LLEGA a ${UMBRAL_DE_TEXTO}:1. El artboard dice ` +
            'que no es color de texto y el calculo ya no lo sostiene: revisa la regla antes de ' +
            'seguir prohibiendola.',
        ).toBeLessThan(UMBRAL_DE_TEXTO);
      }
    }
  });

  it('nadie en `src/` lo usa como texto', () => {
    const hallazgos = NO_SON_TEXTO.flatMap((token) =>
      ARCHIVOS.flatMap((ruta) => hallazgosDe(ruta, readFileSync(ruta, 'utf8'), token.clase)),
    );

    expect(
      hallazgos,
      'Hay texto pintado con un token que el artboard declara NO-color-de-texto:\n' +
        `${hallazgos
          .map((h) => `  ${h.ruta}:${h.linea} sobre ${h.sobre} — ${h.porQue}`)
          .join('\n')}\n\n` +
        `  ${NO_SON_TEXTO.map((t) => `«${t.porQue}»`).join('\n  ')}\n\n` +
        '  Si lo que pinta se lee, el token es `tinta-3` —medido por encima de 4,5:1 en las seis\n' +
        '  combinaciones—. Si es el trazo de un icono decorativo, el elemento lleva `aria-hidden`\n' +
        '  y deja de anunciarse al lector de pantalla, que es lo que lo hace decorativo de verdad.',
    ).toEqual([]);
  });
});
