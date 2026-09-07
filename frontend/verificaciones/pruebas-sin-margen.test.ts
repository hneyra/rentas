import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * **Las pruebas que no caben en el techo de Vitest cuando la maquina va cargada** (#36, AC-4).
 *
 * El AC-4 pide que lo que no se pueda arreglar **se enumere con su motivo**, como
 * `POR_DEBAJO_DEL_MINIMO` en la prueba de contraste, en vez de esconderse en un tiempo mas
 * largo. Esto es esa lista, y esto es su guarda.
 *
 * **Por que hace falta una lista y no un parrafo.** Un parrafo en el cuerpo de un PR se pudre:
 * dentro de tres meses nadie sabra si los rojos que salieron son estos o unos nuevos. Con la
 * lista escrita, el dia que una prueba entre en la familia hay que anadirla **a mano** y se ve
 * en el diff; el dia que salga, la lista se pone roja sola.
 *
 * **Que tienen en comun, que no es «son lentas».** Las diez montan el marco entero —diez
 * modulos, cuarenta destinos— y **encima** conducen el alta del padron: abrirla, teclear en
 * ella y cerrar la pestana. Eso son de 0,9 a 1,4 s de trabajo real, medido como mediana de tres
 * corridas en una maquina descargada. No es una espera mal escrita —las once que si lo eran se
 * arreglaron en este mismo PR— sino el precio de lo que montan.
 *
 * **La tolerancia, medida en dos maquinas distintas.** El techo de Vitest son 5 000 ms de reloj
 * de pared. Con 1 361 ms de coste, «Descartar y cerrar» aguanta que la maquina vaya **3,7 veces
 * mas lenta**; con 907 ms, «el asterisco tambien sale en el arbol» aguanta 5,5. Medido, empiezan
 * a caer entre **4x y 5x**: 1 346 ms → 5 930 ms en la maquina de quien reviso, y 1 390 ms →
 * 6 600 ms en la mia. Por eso `MARGEN_SEGURO` es 6 y no 4: es el primer margen entero que deja
 * fuera la banda en la que se las ha visto caer.
 *
 * **Lo que NO se hace, y por que.** No se sube `testTimeout`. Y el motivo bueno no es que un
 * techo mas alto ralentice la suite —eso es falso, y esta medido: un techo es un techo, no una
 * espera, y no entra en el tiempo de ejecucion mientras nada se cuelgue— sino que **un techo mas
 * alto esconde que una prueba tarda treinta segundos, no hace que tarde menos**. Tampoco se las
 * marca `skip` ni `retry`: una prueba que se reintenta hasta pasar es justo la senal falsa que
 * #36 existe para quitar.
 *
 * **Y esta guarda no mide tiempo a proposito.** Un rojo que dependa de lo cargada que este la
 * maquina de quien lo lea seria otra prueba inestable, que es lo que se venia a quitar. Lo que
 * comprueba es que **la lista cuadre con lo que el arbol tiene**, por las dos direcciones.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const RAIZ = join(AQUI, '..');

/** El `testTimeout` de `vitest.config.ts`, que es el de por omision. */
const TECHO_DE_VITEST_MS = 5_000;

/**
 * El margen por debajo del cual una prueba hay que declararla.
 *
 * Seis, y no cuatro, porque se las ha visto caer entre 4x y 5x en dos maquinas distintas: un
 * margen de 4 dejaria fuera de la lista justo a las que ya se sabe que caen.
 */
const MARGEN_SEGURO = 6;

interface PruebaSinMargen {
  /** Ruta desde `frontend/`. */
  readonly archivo: string;
  /** El titulo literal del `it(...)`. */
  readonly prueba: string;
  /** Mediana de tres corridas en maquina descargada, en milisegundos. */
  readonly costeMs: number;
}

/**
 * Las diez, con su coste medido. Ordenadas de mas cara a menos.
 *
 * Si una prueba nueva monta el marco y conduce el alta, **hay que anadirla aqui** o la guarda de
 * abajo se pone roja nombrandola. Y si una deja de hacerlo, hay que quitarla, o se pone roja
 * igual: las dos direcciones, como en la prueba de contraste.
 */
const SIN_MARGEN: readonly PruebaSinMargen[] = [
  {
    archivo: 'src/marco/Marco.test.tsx',
    prueba: '«Descartar y cerrar» cierra, y no dice que guardo nada',
    costeMs: 1_361,
  },
  {
    archivo: 'src/marco/Marco.test.tsx',
    prueba: '«Guardar y cerrar» cierra y lo dice',
    costeMs: 1_346,
  },
  {
    archivo: 'src/marco/Marco.test.tsx',
    prueba: 'cerrarla pregunta, y ofrece las TRES salidas',
    costeMs: 1_179,
  },
  {
    archivo: 'src/secciones/Contribuyentes.test.tsx',
    prueba: 'montado en el marco, pone el asterisco y cerrar pregunta',
    costeMs: 1_121,
  },
  {
    archivo: 'src/secciones/Contribuyentes.test.tsx',
    prueba: 'lo tecleado sobrevive a irse al panel y volver',
    costeMs: 1_107,
  },
  {
    archivo: 'src/marco/Marco.test.tsx',
    prueba: 'el menu de sesion avisa de las pestanas con cambios sin guardar',
    costeMs: 1_087,
  },
  {
    archivo: 'src/marco/Marco.test.tsx',
    prueba: 'editar un campo marca la pestana activa con un asterisco',
    costeMs: 1_070,
  },
  {
    archivo: 'src/marco/Marco.test.tsx',
    prueba: '«Seguir editando» deja la pestana, y sigue sucia',
    costeMs: 963,
  },
  {
    archivo: 'src/marco/Marco.test.tsx',
    prueba: 'Escape cierra el dialogo de confirmacion, y no cierra la pestana',
    costeMs: 915,
  },
  {
    archivo: 'src/marco/Marco.test.tsx',
    prueba: 'el asterisco tambien sale en el arbol, donde se elige la seccion',
    costeMs: 907,
  },
];

/**
 * Como se reconoce a una de la familia **leyendo el fuente**, que es lo unico reproducible.
 *
 * En `Marco.test.tsx` la senal es la llamada a `ensuciar(`, que es el ayudante que abre el alta
 * del padron y teclea en ella. En `Contribuyentes.test.tsx` es montar `<Marco`, porque ahi la
 * seccion se prueba suelta salvo en las dos que la montan dentro del marco entero.
 *
 * No se usa el coste como criterio de pertenencia **a proposito**: el coste cambia de una
 * corrida a otra —de 1,8 a 2,8 s la mas lenta de la suite, medido el mismo dia— y una lista cuya
 * pertenencia dependa de eso seria otra fuente de rojos que no se reproducen.
 */
const SENAL_POR_ARCHIVO: Readonly<Record<string, string>> = {
  'src/marco/Marco.test.tsx': 'ensuciar(',
  'src/secciones/Contribuyentes.test.tsx': '<Marco',
};

/** Los titulos de los `it(...)` de ese archivo cuyo cuerpo lleva la senal. */
function pruebasQueLlevanLaSenal(archivo: string, senal: string): string[] {
  const fuente = readFileSync(join(RAIZ, archivo), 'utf8');
  const trozos = fuente.split(/\n {2}it\(/);
  const titulos: string[] = [];
  for (const trozo of trozos.slice(1)) {
    const titulo = /^(['"])(.+?)\1/.exec(trozo);
    if (titulo !== null && trozo.includes(senal)) {
      titulos.push(titulo[2] as string);
    }
  }
  return titulos;
}

/** Todos los titulos de `it(...)` de ese archivo, lleven o no la senal. */
function todasLasPruebas(archivo: string): string[] {
  const fuente = readFileSync(join(RAIZ, archivo), 'utf8');
  return fuente
    .split(/\n {2}it\(/)
    .slice(1)
    .flatMap((trozo) => {
      const titulo = /^(['"])(.+?)\1/.exec(trozo);
      return titulo === null ? [] : [titulo[2] as string];
    });
}

const declaradasDe = (archivo: string) =>
  SIN_MARGEN.filter((una) => una.archivo === archivo).map((una) => una.prueba);

describe('AC-4 de #36 — las que montan el marco y no caben en el techo, enumeradas', () => {
  it('la lista no esta vacia, que es lo unico que la haria trivial', () => {
    expect(SIN_MARGEN.length).toBeGreaterThan(0);
  });

  it.each(SIN_MARGEN.map((una) => ({ ...una })))(
    'la declarada «$prueba» existe en su archivo',
    ({ archivo, prueba }) => {
      expect(
        todasLasPruebas(archivo),
        `«${prueba}» esta declarada en la lista del AC-4 y no existe en ${archivo}.\n` +
          'O se renombro, o se borro. Si se renombro, actualiza la lista; si se borro, quitala.\n' +
          'Una lista que nombra pruebas que no existen no declara nada.',
      ).toContain(prueba);
    },
  );

  it('ninguna de la familia se queda SIN declarar: la lista no crece en silencio', () => {
    const sinDeclarar: string[] = [];
    for (const [archivo, senal] of Object.entries(SENAL_POR_ARCHIVO)) {
      const declaradas = declaradasDe(archivo);
      for (const prueba of pruebasQueLlevanLaSenal(archivo, senal)) {
        if (!declaradas.includes(prueba)) {
          sinDeclarar.push(`${archivo} :: ${prueba}`);
        }
      }
    }

    expect(
      sinDeclarar,
      'Estas pruebas montan el marco entero y conducen el alta —la senal que las hace caras—\n' +
        'y NO estan en la lista del AC-4 de #36. Mide su coste y decláralas, o haz que dejen\n' +
        'de montar el marco. Lo que no vale es que la familia crezca sin que nadie lo vea.',
    ).toEqual([]);
  });

  it('ninguna declarada ha dejado de ser de la familia: la lista tampoco se queda vieja', () => {
    const yaNoLoSon: string[] = [];
    for (const [archivo, senal] of Object.entries(SENAL_POR_ARCHIVO)) {
      const conSenal = pruebasQueLlevanLaSenal(archivo, senal);
      for (const prueba of declaradasDe(archivo)) {
        if (todasLasPruebas(archivo).includes(prueba) && !conSenal.includes(prueba)) {
          yaNoLoSon.push(`${archivo} :: ${prueba}`);
        }
      }
    }

    expect(
      yaNoLoSon,
      'Estas estan declaradas como caras y ya no montan el marco ni conducen el alta.\n' +
        'Si dejaron de hacerlo, ya no hay nada que declarar: quitalas de la lista.\n' +
        'Es la direccion que la prueba de contraste llama «ninguno que ya cumpla sigue en la lista».',
    ).toEqual([]);
  });

  it.each(SIN_MARGEN.map((una) => ({ ...una })))(
    'el coste declarado de «$prueba» sigue justificando que este en la lista',
    ({ prueba, costeMs }) => {
      const margen = TECHO_DE_VITEST_MS / costeMs;
      expect(
        margen,
        `«${prueba}» declara ${String(costeMs)} ms, o sea un margen de ${margen.toFixed(1)}x\n` +
          `contra el techo de ${String(TECHO_DE_VITEST_MS)} ms. Con ${String(MARGEN_SEGURO)}x o mas ya no hace falta\n` +
          'declararla: sacala de la lista en vez de dejarla ahi diciendo que es un problema.',
      ).toBeLessThan(MARGEN_SEGURO);
    },
  );

  it('el techo declarado es el que vitest.config.ts deja puesto', () => {
    // Si alguien sube `testTimeout`, esta lista deja de decir la verdad y hay que rehacerla
    // entera: los margenes de arriba se calculan contra ESTE numero. Hoy no se declara, o sea
    // que son los 5 000 ms de por omision de Vitest.
    const config = readFileSync(join(RAIZ, 'vitest.config.ts'), 'utf8');

    expect(
      /testTimeout/.test(config),
      'vitest.config.ts declara ahora un `testTimeout`. La lista del AC-4 de #36 calcula sus\n' +
        `margenes contra ${String(TECHO_DE_VITEST_MS)} ms, que es el de por omision. Actualiza TECHO_DE_VITEST_MS\n` +
        'y vuelve a medir los costes, o los margenes de esta lista mienten.',
    ).toBe(false);
  });
});
