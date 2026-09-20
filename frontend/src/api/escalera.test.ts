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

  it('un 500 del backend, con su estado dentro para dictarlo a soporte', () => {
    const peldano = peldanoDe(fallo(500, 'ERROR_INTERNO', 'Algo se rompio'));

    expect(peldano.clave).toBe('averia');
    expect(peldano.esAveria).toBe(true);
    expect(peldano.detalle).toContain('500');
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


/* ── Y la decision de #262, vigilada ────────────────────────────────────────────────────── */

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
 * **`escalera.ts` sigue sin consumidor de produccion, que es lo que su javadoc afirma** (#262).
 *
 * <h2>Por que esta guarda es de UN archivo y no del arbol entero</h2>
 *
 * Porque se midio la version ancha y no se sostiene. «Un modulo de `src/` cuyo unico importador
 * es su prueba» da hoy **un** candidato —este—, que es justo el que se decidio dejar: una guarda
 * cuya poblacion entera es su propia excepcion no protege nada. Y «ningun importador de
 * produccion», que es la version util, barre **ocho** de los 55 modulos que no son prueba, de los
 * cuales **siete** son legitimos por su clase —la entrada `main.tsx`, dos dobles `*DeMuestra.ts`,
 * dos capturas `*Medid[oa].ts`, el inventario `i18n/catalogo-de-claves.ts` y este—, o sea una
 * lista de excepciones mas larga que la senal. Para cinco de esos ocho **ya existe la guarda**
 * con otro nombre: el `CAPTURAS` de `verificaciones/camino-a-la-api.test.ts`, que exige que cada
 * captura «solo la importen archivos de prueba».
 *
 * Asi que el criterio se aplica donde es senal: **atado al modulo que eligio no tener
 * consumidor**. No prohibe nada; obliga a que el javadoc y el arbol digan lo mismo. El dia que
 * alguien enchufe la escalera —que es lo que se quiere—, esto sale rojo nombrando el archivo que
 * la importo, y quien lo lea sabe que hay un parrafo que corregir en vez de dejarlo afirmando lo
 * contrario de lo que pasa. Es el defecto de #255, con rojo esta vez.
 */
describe('#262 — la decision de dejar `escalera.ts` sin consumidor sigue siendo cierta', () => {
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

  it('ninguna fuente de produccion de `src/` la importa', () => {
    const importa = /from\s+'[^']*\/escalera\.ts'/;
    const culpables = fuentesDeProduccion().filter(
      (ruta) =>
        ruta !== 'api/escalera.ts' &&
        importa.test(readFileSync(join(RAIZ_DE_SRC, ruta), 'utf8')),
    );

    expect(
      culpables,
      'ALGUIEN ENCHUFO LA ESCALERA, Y SU JAVADOC SIGUE DICIENDO QUE NADIE LO HIZO:\n' +
        `${culpables.map((ruta) => `  src/${ruta}`).join('\n')}\n\n` +
        '  Es una buena noticia y hay que terminarla. `src/api/escalera.ts` lleva un apartado\n' +
        '  —«Y NINGUNA FUENTE DE PRODUCCION LO IMPORTA»— que explica por que se quedo en el\n' +
        '  arbol sin consumidor y hasta cuando: hasta que `Ausencia` pueda llevar el remedio, o\n' +
        '  hasta que una pantalla dibuje el peldano. Si ya pasa una de las dos, ese apartado\n' +
        '  sobra, y el javadoc de esta prueba y el de `datos/useDatosDeLaHoja.ts` —que tiene su\n' +
        '  propia escalera corta en `alFallar`— tienen que decir cual de las dos escaleras manda.\n' +
        '  Dejar los tres textos como estan es el defecto de #255: una afirmacion que ya no se\n' +
        '  sostiene y nadie ve caer.',
    ).toEqual([]);
  });
});
