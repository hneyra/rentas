import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

/**
 * Las propiedades del arnes de extremo a extremo que **`vitest` si puede comprobar** (I-2, #28).
 *
 * <h2>Por que un arnes de Playwright tiene pruebas en `vitest`</h2>
 *
 * Lo que el arnes MIDE necesita un navegador y una instalacion. Lo que el arnes ES —donde vive,
 * que no se cuela en la orden rapida, que no lleva ninguna clave escrita y que levanta su
 * servidor con el proxy de datos apagado— son propiedades del arbol de archivos, y esas se
 * comprueban gratis en cada `yarn verificar`.
 *
 * La diferencia importa: si estas propiedades solo se comprobaran corriendo `yarn e2e`, se
 * comprobarian **solo donde hay instalacion levantada**, que es exactamente donde menos falta
 * hacen. Una clave que se cuela en un commit tiene que salir roja en el PR, no el dia que
 * alguien levante Docker.
 */

// `fileURLToPath` y no `new URL(...).pathname`, y esto costo una corrida: bajo el servidor de
// Vite `import.meta.url` llega con el prefijo `/@fs/`, asi que el `pathname` da una ruta que no
// existe en el disco y el archivo ENTERO se cae al recogerlo — sin un solo caso rojo, porque no
// llega a haber casos. Es la misma forma de fallo que F-1 anoto en su octava rotura.
const RAIZ = join(dirname(fileURLToPath(import.meta.url)), '..');
const leer = (ruta: string) => readFileSync(join(RAIZ, ruta), 'utf8');

const CONFIG = leer('playwright.config.ts');
const INSTALACION = leer('e2e/instalacion.ts');
const PAQUETE = JSON.parse(leer('package.json')) as {
  scripts: Record<string, string>;
  devDependencies: Record<string, string>;
};

/** Los archivos del arnes, sin los de estado ni los informes. */
function archivosDelArnes(): readonly string[] {
  const dentro = join(RAIZ, 'e2e');
  return readdirSync(dentro, { recursive: true, encoding: 'utf8' }).filter(
    (nombre) => nombre.endsWith('.ts') && !nombre.startsWith('.estado'),
  );
}

describe('AC1 — el arnes existe, sobre Chromium, y levanta su propio servidor', () => {
  it('hay caminos en «e2e/» y una configuracion de Playwright', () => {
    const archivos = archivosDelArnes();
    expect(archivos.filter((a) => a.endsWith('.spec.ts')).length).toBeGreaterThan(0);
    expect(archivos).toContain('identidad.setup.ts');
    expect(PAQUETE.devDependencies['@playwright/test']).toBeDefined();
  });

  it('corre sobre Chromium y no sobre los tres navegadores', () => {
    expect(CONFIG).toContain("devices['Desktop Chrome']");
    expect(CONFIG).not.toContain('Desktop Firefox');
    expect(CONFIG).not.toContain('Desktop Safari');
  });

  it('el `webServer` levanta Vite por si mismo, para no arrancarlo ni apagarlo a mano', () => {
    expect(CONFIG).toContain('webServer');
    expect(CONFIG).toContain('yarn dev --port');
  });
});

describe('AC1 — el puerto es el 5173, y no es una preferencia', () => {
  it('la entrada se sirve en 5173, que es el unico origen que el realm admite', () => {
    // `src/api/identidad.ts` compone `redirect_uri` como `origin + import.meta.env.BASE_URL`,
    // asi que el puerto de Vite ES el redirect. El cliente `kamayuk-backoffice` admite
    // `http://localhost:5173/*` y nada mas: en cualquier otro puerto Keycloak contesta
    // `invalid_redirect_uri` y no hay canje que medir.
    //
    // El literal se remidio con #71: era `origin + '/'` —la raiz del SITIO— y devolvia al
    // usuario a un 404 en `prod`, donde la aplicacion cuelga de `/rentas/`. El comodin del
    // cliente cubre las dos, asi que esta guarda no lo habria visto: lo que fija es el
    // PUERTO, y por eso el literal va con ella y no suelto.
    expect(INSTALACION).toContain('export const PUERTO = 5173');
    expect(leer('src/api/identidad.ts')).toContain('window.location.origin + import.meta.env.BASE_URL');
  });

  it('y la entrada cuelga de «/rentas/», que es la `base` que declara Vite', () => {
    expect(leer('vite.config.ts')).toContain("base: '/rentas/'");
    expect(INSTALACION).toContain('${ORIGEN}/rentas/');
  });
});

describe('AC2 — ninguna clave se escribe en el repositorio', () => {
  it('la clave sale del entorno, y de ningun otro sitio', () => {
    expect(INSTALACION).toContain('process.env.KAMAYUK_E2E_CLAVE');
  });

  it('ningun archivo del arnes asigna una clave literal', () => {
    // Busca la forma de escribir una credencial a mano: cualquier `clave`/`password`/`secret`
    // seguido de una cadena literal no vacia. Lo que se admite es leerla del entorno.
    const sospechosa =
      /\b(clave|password|passwd|secret|credencial|contrasena)\w*\s*[:=]\s*['"`][^'"`\s]+['"`]/i;
    for (const archivo of archivosDelArnes()) {
      const texto = leer(join('e2e', archivo));
      expect(
        sospechosa.exec(texto),
        `«e2e/${archivo}» parece llevar una clave escrita a mano`,
      ).toBeNull();
    }
  });

  it('y el estado de acceso —que SI lleva cookies vivas— esta fuera del control de versiones', () => {
    // Un `storageState` guarda la cookie de sesion de Keycloak. Versionarla seria publicar una
    // credencial viva, que es peor que escribir la clave: la cookie ya esta canjeada.
    const ignorados = leer('../.gitignore');
    expect(ignorados).toContain('frontend/e2e/.estado/');
    expect(INSTALACION).toContain('e2e/.estado/');
  });
});

describe('AC3 — el arnes guarda su estado en un archivo suyo, y no cambia donde vive el token', () => {
  it('la aplicacion sigue sin tocar el almacenamiento para el token', () => {
    // La prohibicion `token-en-almacenamiento` no gana ninguna excepcion por culpa del arnes:
    // lo que se reusa es la cookie del emisor, que es de Keycloak y no de esta aplicacion.
    const prohibiciones = leer('eslint.prohibiciones.mjs');
    expect(prohibiciones).toContain("clave: 'token-en-almacenamiento'");
    const bloque = prohibiciones.slice(prohibiciones.indexOf("clave: 'token-en-almacenamiento'"));
    expect(bloque.slice(0, bloque.indexOf('},'))).not.toContain('salvo');
  });

  it('y el arnes lo dice donde se lee: reusa el FORMULARIO, no el redirect', () => {
    // Si esta explicacion se perdiera, el proximo lector concluiria que el token se persiste,
    // que es lo contrario de lo que este sistema decidio.
    expect(leer('e2e/identidad.setup.ts')).toContain('cookie de sesion de Keycloak');
  });
});

describe('AC6 — la comprobacion previa mira las DOS piezas y dice cual falta', () => {
  it('sonda el backend y el emisor, cada uno por su lado', () => {
    expect(INSTALACION).toContain('SONDA_DEL_BACKEND');
    expect(INSTALACION).toContain('SONDA_DEL_EMISOR');
    expect(INSTALACION).toContain('/.well-known/openid-configuration');
  });

  it('exige 401 del backend y no «cualquier respuesta»', () => {
    // Un 200 aqui significa que contesta Vite con su `index.html`, o sea que falta
    // `server.proxy`: el modo de fallo que `servidas.ts` lleva escrito desde F-4.
    expect(INSTALACION).toContain('respuesta.status() !== 401');
  });

  it('y el mensaje lleva la orden que hay que correr', () => {
    expect(INSTALACION).toContain('docker compose -f despliegue/plataforma.compose.yaml up -d --wait');
  });

  it('la ausencia se puede EXIGIR, para que no se salte donde tenia que estar', () => {
    expect(INSTALACION).toContain('KAMAYUK_E2E_EXIGIR');
  });
});

describe('AC8 — `yarn verificar` no se ralentiza, y lo sostiene la configuracion', () => {
  it('`vitest` no recoge nada de «e2e/»', () => {
    const vitest = leer('vitest.config.ts');
    expect(vitest).toContain("include: ['{src,verificaciones}/**/*.test.{ts,tsx}']");
    expect(vitest).not.toContain('e2e');
  });

  it('y en «e2e/» no hay ningun archivo que `vitest` pudiera recoger', () => {
    // El patron de `vitest` es por directorio, asi que un `e2e/algo.test.ts` no lo recogeria
    // igualmente — pero uno en `src/` que importara Playwright si. Esto vigila lo que se puede
    // vigilar barato: que los caminos se llamen `.spec.ts` y vivan donde dicen vivir.
    for (const archivo of archivosDelArnes()) {
      expect(archivo, `«e2e/${archivo}» se llama como una prueba de vitest`).not.toMatch(
        /\.test\.tsx?$/,
      );
    }
  });

  it('`verificar` encadena lint, tipos y pruebas, y NO el arnes', () => {
    expect(PAQUETE.scripts.verificar).toBe('yarn lint && yarn typecheck && yarn test');
    expect(PAQUETE.scripts.verificar).not.toContain('e2e');
    expect(PAQUETE.scripts.e2e).toBe('playwright test');
  });

  it('pero `tsc` SI lo compila: un camino mal tipado tiene que verse en la orden rapida', () => {
    expect(leer('tsconfig.json')).toContain('"e2e/**/*.ts"');
  });
});

describe('el arnes mide la INSTALACION y no el proxy de datos', () => {
  it('levanta Vite con la bandera del proxy APAGADA', () => {
    // Es la propiedad que da sentido al issue entero: `.env.development` enciende el proxy, asi
    // que un `yarn dev` a secas dibujaria las cuatro secciones con las cifras del artboard sin
    // salir a la red. Medir eso es el estado que el plan §6 llama insuficiente.
    expect(CONFIG).toContain("VITE_KAMAYUK_PROXY_DE_DATOS: 'false'");
    expect(leer('.env.development')).toContain('VITE_KAMAYUK_PROXY_DE_DATOS=true');
  });

  it('y no reusa un servidor que no levanto el', () => {
    // Un servidor ajeno pudo arrancarse con el proxy encendido, y desde fuera no hay forma de
    // saberlo: la bandera se resuelve al construir el modulo y no viaja en ninguna cabecera.
    expect(CONFIG).toContain('reuseExistingServer: false');
    expect(CONFIG).toContain('--strictPort');
  });

  it('los caminos exigen que ninguna cifra del artboard aparezca', () => {
    const arnes = leer('e2e/arnes.ts');
    expect(arnes).toContain('Rufina Medina Medina');
    expect(leer('e2e/secciones.spec.ts')).toContain('CIFRAS_DEL_ARTBOARD');
  });
});

describe('AC7 — CI', () => {
  const FLUJO = '../.github/workflows/frontend.yml';

  it('el workflow del frontend existe y declara un trabajo para el arnes', () => {
    expect(existsSync(join(RAIZ, FLUJO))).toBe(true);
    expect(leer(FLUJO)).toContain('arnes');
  });

  it('y el trabajo dice lo que le falta, en vez de fingir que corre', () => {
    // Un trabajo que se declara y no puede correr todavia tiene que decir POR QUE y QUE le
    // falta. Mejor un job que dice que no puede correr que uno que pasa sin mirar.
    const flujo = leer(FLUJO);
    expect(flujo).toContain('KAMAYUK_E2E_EXIGIR');
    expect(flujo).toContain('plataforma.compose.yaml');
  });
});
