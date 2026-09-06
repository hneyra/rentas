import { describe, expect, it } from 'vitest';

import { join } from 'node:path';

import { TOKENS, bloque, leer, normalizar, paletas } from './tokens.ts';

/**
 * El contraste se MIDE, no se supone.
 *
 * Esta prueba calcula el ratio WCAG 2.1 de cada par de tokens que los ocho
 * componentes ponen juntos, **en los dos temas**, y falla por debajo de AA. No
 * hay ninguna cifra copiada de una herramienta: la formula esta aqui abajo y los
 * colores salen de `tokens/colors.css`, asi que cambiar un token cambia el
 * resultado en la siguiente corrida.
 *
 * DOS CRITERIOS, porque WCAG tiene dos y piden cosas distintas:
 *
 *   · **1.4.3 Contraste (minimo)** — texto: 4.5:1. Un `::placeholder` es texto.
 *   · **1.4.11 Contraste no textual** — el filo que identifica un control y la
 *     informacion visual que identifica un estado: 3:1.
 *
 * Y un tercero que no es de WCAG sino de honestidad: `decorativo`, para lo que no
 * transporta informacion. Un par solo puede declararse decorativo **con su
 * motivo escrito**, y la prueba exige que lo lleve: sin eso, «decorativo» seria
 * el cajon donde se esconde lo que no pasa.
 *
 * LO QUE NO CUMPLE ESTA ENUMERADO Y MEDIDO, no exento. `POR_DEBAJO_DEL_MINIMO`
 * lleva los pares que la paleta de V6 deja por debajo de su piso, cada uno con su
 * ratio exacto. La prueba comprueba **las dos direcciones**: que ninguno se aleje
 * mas del piso, y que ninguno que ya cumple siga en la lista. Asi la lista no
 * crece en silencio ni se queda tapando un defecto que ya se arreglo.
 */

type Criterio = 'texto' | 'no-textual' | 'decorativo';

/** El piso de cada criterio. `decorativo` no tiene: por definicion no informa. */
const MINIMO: Record<Criterio, number> = {
  texto: 4.5,
  'no-textual': 3,
  decorativo: 0,
};

interface Par {
  readonly frente: string;
  readonly fondo: string;
  readonly criterio: Criterio;
  /** Donde se ve. Lo lee quien revisa; la maquina no lo comprueba. */
  readonly donde: string;
  /** Obligatorio si el criterio es `decorativo`. */
  readonly porque?: string;
}

/** Todo par de tokens que un componente de `src/ds/` pone junto. */
const PARES: readonly Par[] = [
  // —— Texto ——
  { frente: '--tinta', fondo: '--superficie', criterio: 'texto', donde: 'Aviso: el titulo' },
  { frente: '--tinta', fondo: '--fondo', criterio: 'texto', donde: 'el texto sobre el lienzo' },
  { frente: '--tinta', fondo: '--sup', criterio: 'texto', donde: 'la cabecera de una tabla' },
  { frente: '--tinta-2', fondo: '--superficie', criterio: 'texto', donde: 'Campo: la etiqueta' },
  { frente: '--tinta-2', fondo: '--fondo', criterio: 'texto', donde: 'la nota de una seccion' },
  { frente: '--tinta-2', fondo: '--sup', criterio: 'texto', donde: 'Campo «ro»: el valor' },
  {
    frente: '--tinta-3',
    fondo: '--superficie',
    criterio: 'texto',
    donde: 'Aviso: el detalle; Importe: la fecha; el ::placeholder',
  },
  { frente: '--tinta-3', fondo: '--fondo', criterio: 'texto', donde: 'FechaDeCalculo' },
  { frente: '--tinta-3', fondo: '--sup', criterio: 'texto', donde: 'el ::placeholder de un «ro»' },
  { frente: '--azul', fondo: '--superficie', criterio: 'texto', donde: 'Boton fantasma; un enlace' },
  { frente: '--azul', fondo: '--fondo', criterio: 'texto', donde: 'un enlace sobre el lienzo' },
  {
    frente: '--azul',
    fondo: '--azul-suave',
    criterio: 'texto',
    donde: 'Boton fantasma con el puntero encima',
  },
  { frente: '--sobre-azul', fondo: '--azul', criterio: 'texto', donde: 'Boton primario: el rotulo' },
  {
    frente: '--sobre-azul',
    fondo: '--azul-hover',
    criterio: 'texto',
    donde: 'Boton primario con el puntero encima',
  },
  { frente: '--ok-tinta', fondo: '--ok-fondo', criterio: 'texto', donde: 'Insignia «ok»' },
  {
    frente: '--atencion-tinta',
    fondo: '--atencion-fondo',
    criterio: 'texto',
    donde: 'Insignia «atencion»',
  },
  { frente: '--mal-tinta', fondo: '--mal-fondo', criterio: 'texto', donde: 'Insignia «mal»' },
  { frente: '--info-tinta', fondo: '--info-fondo', criterio: 'texto', donde: 'Insignia «info»' },
  {
    frente: '--mal-tinta',
    fondo: '--superficie',
    criterio: 'texto',
    donde: 'Campo: el mensaje de error',
  },
  {
    frente: '--ok-tinta',
    fondo: '--superficie',
    criterio: 'texto',
    donde: 'Aviso: el «Copiada» de la traza',
  },

  // —— No textual (1.4.11) ——
  {
    frente: '--azul',
    fondo: '--superficie',
    criterio: 'no-textual',
    donde: 'el indicador de :focus-visible sobre papel',
  },
  {
    frente: '--azul',
    fondo: '--fondo',
    criterio: 'no-textual',
    donde: 'el indicador de :focus-visible sobre el lienzo',
  },
  {
    frente: '--borde-campo',
    fondo: '--superficie',
    criterio: 'no-textual',
    donde: 'Campo: el filo de un control que se escribe',
  },
  {
    frente: '--borde-campo',
    fondo: '--fondo',
    criterio: 'no-textual',
    donde: 'Campo: el mismo filo sobre el lienzo',
  },
  {
    frente: '--borde-boton',
    fondo: '--superficie',
    criterio: 'no-textual',
    donde: 'Boton secundario: su filo',
  },
  {
    frente: '--borde-hover',
    fondo: '--superficie',
    criterio: 'no-textual',
    donde: 'Boton secundario con el puntero encima',
  },
  {
    frente: '--mal-borde',
    fondo: '--mal-campo',
    criterio: 'no-textual',
    donde: 'Campo con error: su filo',
  },

  // —— Decorativo ——
  {
    frente: '--tinta-4',
    fondo: '--superficie',
    criterio: 'decorativo',
    donde: 'Aviso: el trazo del icono',
    porque:
      'El icono de un Aviso no dice nada que el titulo no diga: los tres tipos llevan su ' +
      'texto debajo, y el componente no admite dibujarse sin el. Si algun dia un icono ' +
      'quedara solo, este par deja de ser decorativo.',
  },
  {
    frente: '--linea',
    fondo: '--superficie',
    criterio: 'decorativo',
    donde: 'el filo por omision: el borde de una tarjeta, el subrayado de una cabecera',
    porque:
      'Separa regiones que ya estan separadas por su relleno y por el cambio de superficie. ' +
      'Lo que SI identifica un control —el filo de un boton y el de un campo— tiene sus ' +
      'propios tokens (`--borde-boton`, `--borde-campo`) justamente para que este no tenga ' +
      'que servir a los dos criterios a la vez.',
  },
  {
    frente: '--linea-2',
    fondo: '--superficie',
    criterio: 'decorativo',
    donde: 'el filo mas tenue, entre filas de una lista',
    porque:
      'Separa filas que ya estan separadas por su propio relleno y por el color de fila ' +
      'alterna. Nada se identifica solo por esta linea.',
  },
  {
    frente: '--esqueleto-brillo',
    fondo: '--esqueleto',
    criterio: 'decorativo',
    donde: 'Esqueleto: el barrido',
    porque:
      'Es la animacion de un marcador de carga que ya lleva `aria-hidden`: quien no la ve ' +
      'no se pierde nada, y `prefers-reduced-motion` la apaga del todo.',
  },
];

/**
 * Lo que la paleta de V6 deja por debajo de su piso. **Enumerado y medido, no
 * exento.**
 *
 * Cada entrada lleva su ratio exacto: la prueba comprueba que sigue siendo ese, y
 * tambien que sigue estando por debajo del piso. Un token que cambie de valor
 * pone esta lista roja aunque el cambio «mejore» el contraste, que es lo que se
 * quiere — la lista es el inventario del defecto, y un inventario que se
 * actualiza solo no sirve de inventario.
 */
const POR_DEBAJO_DEL_MINIMO: ReadonlyArray<{
  readonly frente: string;
  readonly fondo: string;
  readonly tema: 'claro' | 'oscuro';
  readonly ratio: number;
  readonly porque: string;
}> = [
  {
    frente: '--borde-campo',
    fondo: '--superficie',
    tema: 'claro',
    ratio: 1.58,
    porque:
      'El filo de un control en reposo. #C3CFD9 es `const BORDE_CAMPO` del artboard, y AC2 ' +
      'lo ata: cambiarlo aqui seria repintar el disenio desde una prueba. El estado de FOCO ' +
      'si cumple —el filo pasa a `--azul` (8.27:1) mas un halo de 3 px—, asi que lo que ' +
      'queda por debajo es solo el reposo. Cerrarlo es un cambio del artboard, no de F-2. ' +
      'En el tema oscuro, donde nada obliga, el mismo token si cumple (3.56:1).',
  },
  {
    frente: '--borde-campo',
    fondo: '--fondo',
    tema: 'claro',
    ratio: 1.45,
    porque:
      'El mismo filo de control, cuando el campo cae sobre el lienzo en vez de sobre una ' +
      'tarjeta. Misma causa —el valor esta atado al artboard— y mismo arreglo: repintar el ' +
      'artboard, no el token.',
  },
  {
    frente: '--borde-boton',
    fondo: '--superficie',
    tema: 'claro',
    ratio: 1.36,
    porque:
      'El filo de un boton secundario. En claro vale #D6DEE4, que es lo que el artboard le ' +
      'pone (`border:1px solid #D6DEE4`, y es ademas `const LINEA`). El ROTULO del boton si ' +
      'cumple (16.02:1) y su foco tambien, asi que el boton se lee y se opera; lo que no ' +
      'llega a 3:1 es su contorno en reposo. En oscuro, sin artboard que copiar, cumple.',
  },
];

/** La luminancia relativa de un canal, segun WCAG 2.1 §Relative luminance. */
function canal(valor: number): number {
  const proporcion = valor / 255;
  return proporcion <= 0.04045 ? proporcion / 12.92 : ((proporcion + 0.055) / 1.055) ** 2.4;
}

function luminancia(hex: string): number {
  const entero = Number.parseInt(normalizar(hex).slice(1), 16);
  return (
    0.2126 * canal((entero >> 16) & 255) +
    0.7152 * canal((entero >> 8) & 255) +
    0.0722 * canal(entero & 255)
  );
}

/** El ratio de contraste de WCAG: `(L1 + 0.05) / (L2 + 0.05)`. */
function ratio(frente: string, fondo: string): number {
  const uno = luminancia(frente);
  const otro = luminancia(fondo);
  const claro = Math.max(uno, otro);
  const oscuro = Math.min(uno, otro);
  return (claro + 0.05) / (oscuro + 0.05);
}

/** Redondeado a dos decimales HACIA ABAJO: 4.4999 no puede leerse como 4.50. */
const dosDecimales = (numero: number): number => Math.floor(numero * 100) / 100;

const { claro, oscuroPorAtributo } = paletas();
const TEMAS = {
  claro,
  oscuro: oscuroPorAtributo,
} as const;

function color(tema: keyof typeof TEMAS, token: string): string {
  const valor = TEMAS[tema].get(token);
  if (valor === undefined) {
    throw new Error(`El tema «${tema}» no declara «${token}».`);
  }
  return valor;
}

const declarada = (frente: string, fondo: string, tema: string) =>
  POR_DEBAJO_DEL_MINIMO.find(
    (entrada) => entrada.frente === frente && entrada.fondo === fondo && entrada.tema === tema,
  );

describe.each(['claro', 'oscuro'] as const)('AC7 — contraste medido, tema %s', (tema) => {
  const evaluables = PARES.filter((par) => par.criterio !== 'decorativo');

  it.each(evaluables.map((par) => ({ ...par })))(
    '$frente sobre $fondo — $donde',
    ({ frente, fondo, criterio, donde }) => {
      const medido = dosDecimales(ratio(color(tema, frente), color(tema, fondo)));
      const piso = MINIMO[criterio];
      const excepcion = declarada(frente, fondo, tema);

      if (excepcion !== undefined) {
        expect(
          medido,
          `«${frente}» sobre «${fondo}» (${tema}) esta declarado en POR_DEBAJO_DEL_MINIMO con\n` +
            `${excepcion.ratio}:1, y ahora mide ${medido}:1. Si el cambio es deliberado, la\n` +
            'lista se actualiza en el mismo commit: es el inventario del defecto.',
        ).toBe(excepcion.ratio);
        return;
      }

      expect(
        medido,
        `«${frente}» ${color(tema, frente)} sobre «${fondo}» ${color(tema, fondo)} da\n` +
          `  ${medido}:1, y el criterio «${criterio}» pide ${piso}:1.\n` +
          `  Donde se ve: ${donde}.\n` +
          '  Si el par no puede cumplir, se declara en POR_DEBAJO_DEL_MINIMO con su motivo;\n' +
          '  lo que no vale es dejarlo pasar en silencio.',
      ).toBeGreaterThanOrEqual(piso);
    },
  );
});

describe('la lista de excepciones no crece ni se queda vieja', () => {
  it.each(POR_DEBAJO_DEL_MINIMO.map((entrada) => ({ ...entrada })))(
    '$frente sobre $fondo ($tema) sigue por debajo del piso',
    ({ frente, fondo, tema, ratio: anotado }) => {
      const par = PARES.find((candidato) => candidato.frente === frente && candidato.fondo === fondo);
      expect(par, `«${frente}»/«${fondo}» ya no es un par que ningun componente ponga junto.`).toBeDefined();

      const medido = dosDecimales(ratio(color(tema, frente), color(tema, fondo)));
      expect(medido).toBe(anotado);
      expect(
        medido,
        `«${frente}» sobre «${fondo}» (${tema}) ya cumple: ${medido}:1. Sale de la lista, o la\n` +
          'lista deja de significar «esto es lo que falta por arreglar».',
      ).toBeLessThan(MINIMO[(par as Par).criterio]);
    },
  );

  it('cada excepcion dice por que, y no de pasada', () => {
    for (const entrada of POR_DEBAJO_DEL_MINIMO) {
      expect(entrada.porque.length, `«${entrada.frente}»/«${entrada.fondo}»`).toBeGreaterThan(80);
    }
  });

  it('todo par decorativo dice por que lo es', () => {
    const mudos = PARES.filter((par) => par.criterio === 'decorativo' && par.porque === undefined);

    expect(
      mudos.map((par) => `${par.frente}/${par.fondo}`),
      '«decorativo» sin motivo escrito es el cajon donde se esconde lo que no pasa.',
    ).toEqual([]);
  });
});

describe('que par quedo mas cerca del limite', () => {
  /**
   * **Anotado, y por eso comprobado.** No basta con que todo pase: el par mas
   * ajustado es el que se rompe primero cuando alguien toca un token, y si nadie
   * lo nombra el dia que se rompa no habra con que comparar.
   */
  const MAS_AJUSTADO = {
    // 3.08:1 contra un piso de 3: ocho centesimas. Es el par que se rompe primero,
    // y esta a un tono de distancia — cualquier retoque de `--borde-hover` o de
    // `--superficie` lo cruza. El mas ajustado de TEXTO va muy por detras:
    // `--tinta-3` sobre `--fondo`, 5.07:1 contra 4.5.
    claro: {
      frente: '--borde-hover',
      fondo: '--superficie',
      criterio: 'no-textual',
      ratio: 3.08,
    },
    oscuro: {
      frente: '--borde-campo',
      fondo: '--superficie',
      criterio: 'no-textual',
      ratio: 3.56,
    },
  } as const;

  it.each(['claro', 'oscuro'] as const)('en el tema %s', (tema) => {
    const evaluables = PARES.filter(
      (par) => par.criterio !== 'decorativo' && declarada(par.frente, par.fondo, tema) === undefined,
    );

    const conMargen = evaluables
      .map((par) => ({
        par,
        margen: dosDecimales(ratio(color(tema, par.frente), color(tema, par.fondo))) - MINIMO[par.criterio],
      }))
      .sort((uno, otro) => uno.margen - otro.margen);

    const primero = conMargen[0];
    expect(primero).toBeDefined();

    const esperado = MAS_AJUSTADO[tema];
    const medido = dosDecimales(
      ratio(color(tema, (primero as { par: Par }).par.frente), color(tema, (primero as { par: Par }).par.fondo)),
    );

    expect(
      {
        frente: (primero as { par: Par }).par.frente,
        fondo: (primero as { par: Par }).par.fondo,
        criterio: (primero as { par: Par }).par.criterio,
        ratio: medido,
      },
      `El par mas ajustado del tema ${tema} cambio. Si el cambio es deliberado, se anota aqui\n` +
        'el nuevo: esta constante es lo que hace que «pasa de sobra» y «pasa por poco» sean\n' +
        'afirmaciones distintas.',
    ).toEqual(esperado);
  });
});

describe('las dos desviaciones del artboard siguen aplicadas', () => {
  /**
   * El catalogo de pares de arriba dice que el `::placeholder` usa `--tinta-3` y
   * que el indicador de foco usa `--azul`. **Eso es una afirmacion sobre
   * `base.css`, y `base.css` no la firma.**
   *
   * Sin estas dos comprobaciones, devolver el `::placeholder` a `--tinta-4` —el
   * valor del artboard, 2.59:1— dejaria la prueba de contraste entera EN VERDE:
   * seguiria midiendo el par que el catalogo declara, que ya no seria el que la
   * pantalla pinta. Es la forma de fallo mas silenciosa que tiene una prueba de
   * accesibilidad, y por eso el CSS se lee.
   */
  const base = leer(join(TOKENS, 'base.css'));

  it('el ::placeholder usa «--tinta-3» y no «--tinta-4» (2.59:1)', () => {
    expect(
      bloque(base, '::placeholder {'),
      'El artboard pinta el ::placeholder con #93A3AF, que sobre papel blanco da 2.59:1.\n' +
        'Un placeholder es texto y WCAG 1.4.3 pide 4.5:1. «--tinta-3» da 5.51:1 y tambien es\n' +
        'un color del artboard: lo que cambia es cual de los suyos se usa, no la paleta.',
    ).toContain('var(--tinta-3)');
  });

  it('el indicador de foco usa «--azul» y no «--acento» (2.12:1)', () => {
    expect(
      bloque(base, ':focus-visible {'),
      'El artboard pinta el foco con #52BDEF, que sobre papel blanco da 2.12:1, y WCAG\n' +
        '1.4.11 pide 3:1 para lo que identifica un ESTADO. «--azul» da 8.27:1, y es el mismo\n' +
        'color que el artboard le pone al borde de un campo enfocado.',
    ).toContain('var(--azul)');
  });
});
