import { existsSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';

import {
  ARTBOARD,
  RAIZ,
  TOKENS,
  coloresDelArtboard,
  constantesDelArtboard,
  leer,
  normalizar,
  paletas,
  propiedades,
  sinComentarios,
} from './tokens.ts';

/**
 * Los tokens dicen lo que dice el artboard.
 *
 * **Es la barrera contra el modo de fallo mas caro de un sistema de diseno: que
 * alguien reescriba un valor a ojo.** Un `#005286` en vez de `#005284` no lo ve
 * nadie en una revision, no rompe ninguna prueba de componente, y a los tres
 * meses hay dos azules en la pantalla y ya no se sabe cual era el bueno.
 *
 * Lo que se compara sale de `frontend/diseno/RentasV6.dc.html`, que es el
 * artboard vendorizado — no una copia de sus valores escrita en esta prueba. Si
 * la lista viviera aqui, cambiar el token y cambiar la lista serian el mismo
 * commit y nadie se enteraria.
 *
 * DOS FAMILIAS DE VALOR, y la distincion importa:
 *
 *   · Las ONCE constantes que el artboard declara con nombre. Se comparan por
 *     nombre, valor a valor.
 *   · Los literales que el artboard escribe en linea —el fondo de pagina, el
 *     halo del foco, la tinta del `::placeholder`, los cuatro pares de la
 *     insignia—. No tienen nombre alli, asi que se comprueba que el hexadecimal
 *     del token APAREZCA en el artboard. Es una afirmacion mas debil, y se dice:
 *     no distingue «este es el color del `::placeholder`» de «este color esta en
 *     alguna parte». Lo que si impide, que es lo que importa, es inventarse uno.
 *
 * Lo que esta prueba NO mira es el tema oscuro, porque el artboard no lo trae.
 * De eso responde `contraste.test.ts`, que es lo unico que se puede medir sobre
 * unos valores que no tienen fuente que copiar.
 */

/** Los cinco archivos de tokens, en el orden en que `estilos.css` los encadena. */
const LOS_CINCO = ['colors.css', 'fonts.css', 'typography.css', 'spacing.css', 'base.css'];

/**
 * Token -> constante del artboard. Es la tabla de equivalencias, y es lo unico
 * escrito a mano de este archivo: los VALORES de los dos lados se leen.
 */
const CON_NOMBRE_EN_EL_ARTBOARD: ReadonlyArray<readonly [string, string]> = [
  ['--azul', 'AZUL'],
  ['--azul-oscuro', 'AZUL_OSC'],
  ['--azul-suave', 'AZUL_SUAVE'],
  ['--acento', 'ACENTO'],
  ['--linea', 'LINEA'],
  ['--linea-2', 'LINEA_2'],
  ['--borde-campo', 'BORDE_CAMPO'],
  ['--tinta', 'TINTA'],
  ['--tinta-2', 'TINTA_2'],
  ['--tinta-3', 'TINTA_3'],
  ['--sup', 'SUP'],
];

/**
 * Los tokens cuyo valor el artboard escribe EN LINEA, con el sitio donde se ve.
 * El sitio no lo comprueba la maquina: lo lee quien revisa, y por eso esta.
 */
const EN_LINEA_EN_EL_ARTBOARD: ReadonlyArray<readonly [string, string]> = [
  ['--fondo', "html, body { … background: #F2F6F9 … }"],
  ['--superficie', 'el papel de una tarjeta: background:#fff'],
  ['--sobre-azul', 'el rotulo del boton primario: color:#fff'],
  ['--tinta-4', '::placeholder { color: #93A3AF }'],
  ['--foco', 'input:focus { … box-shadow: 0 0 0 3px #D3EBFA }'],
  ['--borde-hover', 'style-hover="border-color:#7E96A8"'],
  ['--ok-fondo', 'INS.ok'],
  ['--ok-tinta', 'INS.ok'],
  ['--atencion-fondo', 'INS.warn'],
  ['--atencion-tinta', 'INS.warn'],
  ['--mal-fondo', 'INS.bad'],
  ['--mal-tinta', 'INS.bad'],
  ['--info-fondo', 'INS.info'],
  ['--info-tinta', 'INS.info'],
  ['--mal-borde', 'const IN_MAL'],
  ['--mal-campo', 'const IN_MAL'],
];

describe('AC1 — los cinco archivos de tokens, y un solo punto de entrada', () => {
  const entrada = leer(join(RAIZ, 'src/estilos/estilos.css'));

  it.each(LOS_CINCO)('«tokens/%s» existe', (archivo) => {
    expect(existsSync(join(TOKENS, archivo))).toBe(true);
  });

  it('«estilos.css» los importa a los cinco, EN ORDEN', () => {
    const importados = [...sinComentarios(entrada).matchAll(/@import\s+'\.\/tokens\/([^']+)'/g)].map(
      (encontrado) => encontrado[1],
    );

    expect(
      importados,
      'El orden no es decorativo. Para `var()` da igual —se resuelve al usarla—, pero\n' +
        '`base.css` escribe REGLAS que leen lo que los otros cuatro declaran, y a igualdad\n' +
        'de especificidad gana la ultima que llega. Un `@import` movido de sitio no rompe\n' +
        'nada el dia que se mueve, y el dia que si lo rompe ya no se parece a ese cambio.',
    ).toEqual(LOS_CINCO);
  });

  it('«componentes.css» va DESPUES de los tokens: un componente le gana al reinicio', () => {
    const limpio = sinComentarios(entrada);
    expect(limpio.indexOf('./componentes.css')).toBeGreaterThan(limpio.indexOf('./tokens/base.css'));
  });

  it('nadie mas importa una hoja de estilos: el punto de entrada es uno', () => {
    // Si cada componente trajera la suya, el orden de la cascada lo decidiria el
    // orden en que Vite resuelve los modulos, que cambia con un `import` movido.
    const conCss = [...leer(join(RAIZ, 'src/main.tsx')).matchAll(/import\s+'([^']+\.css)'/g)].map(
      (encontrado) => encontrado[1],
    );

    expect(conCss).toEqual(['./estilos/estilos.css']);
  });
});

describe('AC2 — los valores coinciden con el artboard', () => {
  const { claro } = paletas();
  const delArtboard = constantesDelArtboard();
  const coloresQueUsa = coloresDelArtboard();

  it('el artboard vendorizado esta y declara sus once constantes con nombre', () => {
    expect(existsSync(ARTBOARD), `Falta el artboard en ${ARTBOARD}.`).toBe(true);

    // Sin esta comprobacion, un cambio en el formato del artboard —o un archivo
    // que no se descargo entero— dejaria el mapa VACIO, y entonces los once casos
    // de abajo compararian `undefined` contra `undefined` y pasarian en verde.
    expect(
      [...delArtboard.keys()].sort(),
      'El artboard tiene que declarar exactamente estas once constantes de color.',
    ).toEqual(
      [
        'ACENTO',
        'AZUL',
        'AZUL_OSC',
        'AZUL_SUAVE',
        'BORDE_CAMPO',
        'LINEA',
        'LINEA_2',
        'SUP',
        'TINTA',
        'TINTA_2',
        'TINTA_3',
      ].sort(),
    );
  });

  it.each(CON_NOMBRE_EN_EL_ARTBOARD)('%s vale lo que el artboard llama %s', (token, constante) => {
    expect(
      normalizar(claro.get(token) ?? ''),
      `«${token}» tiene que valer lo que el artboard declara en «const ${constante}».\n` +
        'No se reescribe a ojo: se copia, o se cambia el artboard primero.',
    ).toBe(delArtboard.get(constante));
  });

  it.each(EN_LINEA_EN_EL_ARTBOARD)('%s sale del artboard (%s)', (token, donde) => {
    const valor = normalizar(claro.get(token) ?? '');

    expect(
      coloresQueUsa.has(valor),
      `«${token}» vale ${valor}, y ese color no aparece en el artboard.\n` +
        `Deberia salir de: ${donde}.\n` +
        'Un color que no esta en el artboard es un color que alguien invento.',
    ).toBe(true);
  });

  it('ningun token de color se invento: TODOS salen del artboard', () => {
    // La red de seguridad de las dos listas de arriba. Si alguien anade un token
    // de color nuevo y no lo mete en ninguna, esta prueba lo caza igual — y si de
    // verdad no sale del artboard, tiene que decirlo aqui y no en un comentario.
    const inventados = [...claro]
      .filter(([, valor]) => /^#[0-9a-f]{3,6}$/i.test(valor.trim()))
      .filter(([, valor]) => !coloresQueUsa.has(normalizar(valor)))
      .map(([nombre, valor]) => `${nombre}: ${valor}`);

    expect(
      inventados,
      'Estos colores no estan en el artboard. Si hacen falta, el sitio donde se justifican\n' +
        'es el javadoc de `colors.css`, y ademas hay que declararlos aqui como excepcion.',
    ).toEqual([]);
  });
});

describe('los componentes no pintan: solo usan tokens', () => {
  // `marco.css` entra en la lista desde F-3, y por el mismo motivo que los otros
  // dos: es la hoja mas grande del proyecto y la unica que dibuja una superficie
  // OSCURA —la barra global— en tema claro. Un `#fff` escrito ahi a mano seria
  // invisible en la revision y, en tema oscuro, texto blanco sobre papel blanco.
  it.each(['componentes.css', 'marco.css', 'tokens/base.css'])(
    '«%s» no escribe ni un color a mano',
    (hoja) => {
      const css = sinComentarios(leer(join(RAIZ, 'src/estilos', hoja)));
      const colores = css.match(/#[0-9a-f]{3,8}\b|\brgba?\(|\bhsla?\(/gi) ?? [];

      expect(
        colores,
        `«${hoja}» tiene un color escrito a mano. Todo color sale de \`tokens/colors.css\`:\n` +
          'es lo que hace que el tema oscuro exista sin tocar un componente, y lo que hace\n' +
          'que la prueba de contraste mida lo que la pantalla ensena y no otra cosa.',
      ).toEqual([]);
    },
  );
});

describe('el tema oscuro se declara dos veces y dice lo mismo', () => {
  const { claro, oscuroPorPreferencia, oscuroPorAtributo } = paletas();

  it('la preferencia del sistema y el atributo declaran los mismos valores', () => {
    // En CSS plano no hay forma de escribir esto una sola vez: un `@media` no
    // admite que le anadan un selector desde fuera. Asi que se escribe dos veces
    // y lo que impide que diverjan es esta prueba — que es peor que no tener que
    // repetirlo, y mejor que repetirlo sin nada que lo vigile.
    expect(Object.fromEntries(oscuroPorPreferencia)).toEqual(
      Object.fromEntries(oscuroPorAtributo),
    );
  });

  it('el tema oscuro redefine TODOS los colores del claro, sin dejarse ninguno', () => {
    const huerfanos = [...claro.keys()].filter((token) => !oscuroPorAtributo.has(token));

    expect(
      huerfanos,
      'Un color declarado solo en el tema claro se queda con el valor claro sobre papel\n' +
        'oscuro. No sale un error: sale un texto que no se lee.',
    ).toEqual([]);
  });

  it('y no inventa ninguno que el claro no tenga', () => {
    const sobrantes = [...oscuroPorAtributo.keys()].filter((token) => !claro.has(token));

    expect(sobrantes).toEqual([]);
  });
});

describe('la escala tipografica y el espaciado son los del artboard', () => {
  const html = leer(ARTBOARD);

  it.each([
    ['--texto-base', 'font-size: 15px'],
    ['--texto-control', 'font-size:14px'],
    ['--texto-celda', 'font-size:13.5px'],
    ['--texto-insignia', 'font-size:11.5px'],
    ['--relleno-campo', 'padding:9px 10px'],
    ['--relleno-insignia', 'padding:2px 8px'],
    ['--relleno-celda', 'padding:11px 16px'],
  ])('%s vale lo que el artboard escribe («%s»)', (token, comoLoEscribeElArtboard) => {
    const escala = propiedades(leer(join(TOKENS, 'typography.css')));
    const espacios = propiedades(leer(join(TOKENS, 'spacing.css')));
    const valor = escala.get(token) ?? espacios.get(token);

    expect(valor, `Falta el token «${token}».`).toBeDefined();
    expect(
      comoLoEscribeElArtboard.includes(valor as string),
      `«${token}» vale ${valor} y el artboard escribe «${comoLoEscribeElArtboard}».\n` +
        'Los medios pixeles de V6 son deliberados: una tabla de 13.5 px cabe donde una de\n' +
        '14 no. Redondearlos es reescribir el diseno a ojo.',
    ).toBe(true);

    expect(
      html.includes(comoLoEscribeElArtboard),
      `El artboard ya no escribe «${comoLoEscribeElArtboard}»: la referencia cambio.`,
    ).toBe(true);
  });

  it('la familia es una sola, y es la del artboard', () => {
    const familias = propiedades(leer(join(TOKENS, 'fonts.css')));

    expect(familias.get('--fuente')).toContain("'Source Sans 3'");
    expect(html).toContain("font-family: 'Source Sans 3', system-ui, sans-serif");
  });
});
