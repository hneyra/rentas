import { readdirSync, readFileSync, statSync } from 'node:fs';
import { dirname, join, relative, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { ErrorDeLaApi } from './cliente.ts';
import { peldanoDe } from './escalera.ts';

/**
 * Los siete peldanos, sin montar nada (AC6).
 *
 * Aqui se mide **el mapa**: que estado y que codigo llevan a que remedio. Las dos mitades hacen
 * falta —un mapa correcto que nadie dibuja no ayuda a nadie, y una pantalla que dibuja el peldano
 * equivocado se ve bien—, y **la otra mitad no esta medida porque no existe**: hasta #255 ponia
 * que se media en `aplicacion.test.tsx`, que no es un archivo de este arbol, y lo cierto es que
 * **ninguna pantalla dibuja el peldano**.
 *
 * Lo que #262 corrige es la consecuencia que se sacaba de ahi. Aqui ponia «mientras siga asi, un
 * 403 `SIN_PRIVILEGIO` y una averia de verdad se ven igual», y **eso no es lo que se mide**:
 * `datos/useDatosDeLaHoja.ts` tiene su propia escalera corta dentro de `alFallar` y da tres
 * frases distintas —401, 403 y el resto con su codigo—. Lo que de verdad se ve igual son los
 * **dos** 403 entre si, y el **422**, que alli sale como «fallo (422)», o sea como una averia.
 * Por que este archivo se queda en el arbol aun asi, y hasta cuando, esta escrito en el javadoc
 * de `escalera.ts`, y el ultimo `describe` de este archivo lo vigila.
 */

function fallo(estado: number, codigo: string | null, mensaje: string): ErrorDeLaApi {
  return new ErrorDeLaApi(
    estado,
    'GET /seguridad/sesion',
    codigo === null ? {} : { codigo, mensaje, title: mensaje },
  );
}

describe('AC6 — cada peldano de la escalera es un remedio distinto', () => {
  it('401: vuelve a pedir identidad, y solo el 401 ofrece esa puerta', () => {
    const peldano = peldanoDe(fallo(401, 'NO_AUTENTICADO', 'La peticion no trae un token valido'));

    expect(peldano.clave).toBe('sin-identidad');
    expect(peldano.pideIdentidad).toBe(true);
    expect(peldano.esAveria).toBe(false);
  });

  it('403 SIN_MUNICIPALIDAD: lo dice, y NO manda a volver a entrar', () => {
    const peldano = peldanoDe(
      fallo(403, 'SIN_MUNICIPALIDAD', 'El token no identifica una municipalidad'),
    );

    expect(peldano.clave).toBe('sin-municipalidad');
    expect(peldano.titulo).toBe('Esta cuenta no tiene municipalidad asignada');
    // Entrar otra vez con la misma cuenta trae el mismo token, sin el mismo claim, y el mismo
    // 403: seria mandar a dar vueltas a quien tiene que llamar al administrador.
    expect(peldano.pideIdentidad).toBe(false);
    expect(peldano.esAveria).toBe(false);
  });

  it('403 SIN_PRIVILEGIO: falta permiso, y NO es una averia', () => {
    const peldano = peldanoDe(
      fallo(403, 'SIN_PRIVILEGIO', 'No tiene el privilegio LECTURA sobre consulta_deuda'),
    );

    expect(peldano.clave).toBe('sin-privilegio');
    expect(peldano.esAveria).toBe(false);
    expect(peldano.remedio).toContain('No es una averia');
    // El mensaje del backend nombra el privilegio y la opcion: es lo que hay que pedir.
    expect(peldano.detalle).toBe('No tiene el privilegio LECTURA sobre consulta_deuda');
  });

  it('404: el detalle del backend se conserva TAL CUAL, porque nombra la cuenta', () => {
    const dijo = "El token identifica a 'administrador', que no es un usuario de esta municipalidad";
    const peldano = peldanoDe(fallo(404, 'NO_ENCONTRADO', dijo));

    expect(peldano.clave).toBe('no-encontrado');
    expect(peldano.detalle).toBe(dijo);
    expect(peldano.esAveria).toBe(false);
  });

  it('los dos 403 NO son el mismo peldano, que es lo que el codigo separa', () => {
    const sinMunicipalidad = peldanoDe(fallo(403, 'SIN_MUNICIPALIDAD', 'a'));
    const sinPrivilegio = peldanoDe(fallo(403, 'SIN_PRIVILEGIO', 'b'));

    expect(sinMunicipalidad.clave).not.toBe(sinPrivilegio.clave);
    expect(sinMunicipalidad.titulo).not.toBe(sinPrivilegio.titulo);
    expect(sinMunicipalidad.remedio).not.toBe(sinPrivilegio.remedio);
  });

  it('un 403 sin codigo no se hace pasar por ninguno de los dos', () => {
    // Sin `codigo` no se puede saber cual de los dos es, y adivinar mandaria a la mitad de los
    // casos a pedir un permiso que no falta.
    const peldano = peldanoDe(fallo(403, null, ''));

    expect(peldano.clave).toBe('no-permitido');
    expect(peldano.esAveria).toBe(false);
  });
});

/**
 * El quinto peldano, que llego con la primera escritura de esta interfaz (I-3).
 *
 * Hasta #31 esta interfaz solo leia y un 422 no podia llegar. Con
 * `PUT /seguridad/sesion/ejercicio` llega, y es **la respuesta mas probable del acto**.
 */
describe('422 VALIDACION — el backend entendio la peticion y la rechazo por una regla suya', () => {
  it('NO es una averia: escribir «ok» no manda a llamar a soporte', () => {
    const dijo =
      'La observacion debe explicar el cambio: al menos 5 caracteres, y no espacios en blanco (ADR-0008)';
    const peldano = peldanoDe(fallo(422, 'VALIDACION', dijo));

    expect(peldano.clave).toBe('no-valido');
    // Sin este peldano caia en `averia`, con «Reintente en unos segundos. Si sigue igual, avise
    // a soporte» — para una observacion corta.
    expect(peldano.esAveria).toBe(false);
    expect(peldano.pideIdentidad).toBe(false);
  });

  it('y el mensaje del backend se conserva TAL CUAL, porque lleva la regla y su cifra', () => {
    const peldano = peldanoDe(
      fallo(422, 'VALIDACION', 'Ejercicio fuera de rango: 1800. Se admite de 1990 a 2100'),
    );

    // Es lo unico con lo que quien esta delante corrige lo que escribio. Resumirlo a «revise
    // los datos» borraria justo eso; copiar la regla aqui para adelantarla seria peor, porque
    // dejaria dos verdades sobre el rango y ninguna que lo dijera.
    expect(peldano.detalle).toBe('Ejercicio fuera de rango: 1800. Se admite de 1990 a 2100');
    expect(peldano.detalle).not.toContain('422');
  });

  it('un 500 sigue siendo una averia, que es lo que el 422 NO es', () => {
    // El contraste importa: los dos son fallos del servidor por el codigo, y sin separarlos el
    // 500 de #30 —el cuerpo sin observacion— y el 422 de una observacion corta se explicarian
    // igual, cuando uno se arregla escribiendo mas y el otro no se arregla desde aqui.
    expect(peldanoDe(fallo(500, 'ERROR_INTERNO', 'No se pudo completar la operacion')).esAveria).toBe(
      true,
    );
    expect(peldanoDe(fallo(422, 'VALIDACION', 'x')).esAveria).toBe(false);
  });
});

describe('AC6 — lo que SI es una averia', () => {
  it('un corte de red: no llega ningun ErrorDeLaApi, y hay que decir algo igual', () => {
    const peldano = peldanoDe(new TypeError('Failed to fetch'));

    expect(peldano.clave).toBe('averia');
    expect(peldano.esAveria).toBe(true);
    expect(peldano.pideIdentidad).toBe(false);
  });

  it('un 500 del backend, con su estado APARTE para dictarlo a soporte', () => {
    const peldano = peldanoDe(fallo(500, 'ERROR_INTERNO', 'Algo se rompio'));

    expect(peldano.clave).toBe('averia');
    expect(peldano.esAveria).toBe(true);
    // Hasta #283 el estado iba pegado a la frase —«Algo se rompio (500)»—, y una frase con un
    // numero dentro no puede ser clave de ningun locale: es distinta en cada fallo. Ahora es un
    // dato, y quien dibuja lo mete por interpolacion.
    expect(peldano.estado).toBe(500);
    expect(peldano.detalle).not.toContain('500');
  });

  it('y un corte de red no inventa un estado: no hubo respuesta que dictar', () => {
    expect(peldanoDe(new TypeError('Failed to fetch')).estado).toBeNull();
  });

  it('y los tres peldanos de autorizacion NO son averias: es el sistema funcionando', () => {
    const codigos: readonly [number, string][] = [
      [401, 'NO_AUTENTICADO'],
      [403, 'SIN_MUNICIPALIDAD'],
      [403, 'SIN_PRIVILEGIO'],
    ];

    // Pintarlas de «algo se rompio» manda a mirar un despliegue cuando lo que falta es una fila
    // en una tabla de permisos.
    expect(codigos.map(([estado, codigo]) => peldanoDe(fallo(estado, codigo, 'x')).esAveria)).toEqual(
      [false, false, false],
    );
  });
});


/* ── Y la decision de #283, vigilada ────────────────────────────────────────────────────── */

const RAIZ_DE_SRC = join(dirname(fileURLToPath(import.meta.url)), '..');

/** Todos los `.ts`/`.tsx` de produccion bajo `src/`, relativos a `src/`. */
function fuentesDeProduccion(desde: string = RAIZ_DE_SRC): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    if (statSync(ruta).isDirectory()) return fuentesDeProduccion(ruta);
    if (!/\.tsx?$/.test(entrada) || /\.test\.tsx?$/.test(entrada)) return [];
    return [relative(RAIZ_DE_SRC, ruta).split(sep).join('/')];
  });
}

/**
 * **`escalera.ts` tiene DOS consumidores de produccion, y se sabe cual es cada uno** (#262, #283).
 *
 * <h2>De que guarda viene esta</h2>
 *
 * #262 midio que este archivo no lo importaba nadie mas que su prueba, decidio dejarlo en el arbol
 * —sus cinco peldanos son `curl` contra una instalacion que este puesto no tiene— y escribio una
 * guarda que salia roja **el dia que alguien lo enchufara**, para que el javadoc no se quedara
 * afirmando lo contrario de lo que pasa. Ese dia fue #283, y la guarda mordio: nombro
 * `datos/useDatosDeLaHoja.ts` y `i18n/catalogo-de-claves.ts`.
 *
 * <h2>Por que sigue habiendo guarda, y no una linea menos</h2>
 *
 * Porque la afirmacion que hay que sostener cambio, no desaparecio. Los dos consumidores no son
 * intercambiables y cada uno tiene su motivo:
 *
 * · **`datos/useDatosDeLaHoja.ts`** la DIBUJA: traduce el peldano a la `Ausencia` que el
 *   interprete de `@kamayuk/ui` sabe pintar. Si desapareciera, la escalera volveria a no tener
 *   quien la ensene y el javadoc de `escalera.ts` —que dice que la dibuja una pantalla— mentiria.
 * · **`i18n/catalogo-de-claves.ts`** la INVENTARIA: llama a `peldanoDe` con un fallo por peldano
 *   para que sus frases entren en el locale. Si desapareciera, las cuatro frases de cada peldano
 *   saldrian en castellano en cualquier idioma, y la guarda del locale no lo veria.
 *
 * Un tercero —una pantalla que se monte su propia escalera, un conector que decida por su cuenta
 * que es una averia— es lo que esto existe para que no pase en silencio: dos escaleras que dicen
 * cosas distintas del mismo 403 es peor que una escalera corta.
 */
describe('#283 — quien importa la escalera, y para que', () => {
  it('EL CENTINELA: el barrido ve el arbol de `src/`, y se ve a si mismo fuera', () => {
    // Sin esto, un `readdirSync` sobre la carpeta equivocada dejaria la prueba de abajo en verde
    // para siempre sobre la lista vacia, que es como una barrera se apaga sin que nadie la borre.
    const fuentes = fuentesDeProduccion();

    expect(fuentes.length).toBeGreaterThanOrEqual(40);
    expect(fuentes).toContain('api/escalera.ts');
    expect(fuentes).toContain('datos/useDatosDeLaHoja.ts');
    // Y no cuenta las pruebas, que son las que SI pueden importarla.
    expect(fuentes).not.toContain('api/escalera.test.ts');
  });

  it('la importan exactamente dos fuentes de produccion, y son esas dos', () => {
    const importa = /from\s+'[^']*\/escalera\.ts'/;
    const quienes = fuentesDeProduccion().filter(
      (ruta) => ruta !== 'api/escalera.ts' && importa.test(readFileSync(join(RAIZ_DE_SRC, ruta), 'utf8')),
    );

    expect(
      [...quienes].sort((a, b) => a.localeCompare(b)),
      'LOS CONSUMIDORES DE LA ESCALERA YA NO SON LOS DOS QUE SU JAVADOC NOMBRA:\n' +
        `${quienes.map((ruta) => `  src/${ruta}`).join('\n')}\n\n` +
        '  Si FALTA alguno: `datos/useDatosDeLaHoja.ts` es quien la dibuja y\n' +
        '  `i18n/catalogo-de-claves.ts` quien mete sus frases en el locale. Sin el primero la\n' +
        '  escalera vuelve a no tener quien la ensene —y el apartado «Y DESDE #283 LA DIBUJA UNA\n' +
        '  PANTALLA» de `escalera.ts` pasa a ser falso—; sin el segundo, las cuatro frases de cada\n' +
        '  peldano salen en castellano en cualquier idioma.\n' +
        '  Si SOBRA alguno: hay una segunda escalera, y dos escaleras que contestan cosas\n' +
        '  distintas al mismo 403 son peor que la escalera corta que #283 retiro. O se enchufa a\n' +
        '  `useDatosDeLaHoja`, o este javadoc y el de `escalera.ts` tienen que decir por que no.',
    ).toEqual(['datos/useDatosDeLaHoja.ts', 'i18n/catalogo-de-claves.ts']);
  });
});
