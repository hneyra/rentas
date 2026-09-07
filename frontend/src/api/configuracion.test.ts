import { afterEach, describe, expect, it, vi } from 'vitest';

import { configuracion, procedencia } from './configuracion.ts';

/**
 * Los tres escalones de las senias del ambiente (#44).
 *
 * Lo que se prueba aqui no es que haya valores por omision —eso se ve leyendo el archivo— sino
 * las dos decisiones que se pueden romper sin que nada cambie de aspecto:
 *
 *   · que lo SERVIDO gane a lo horneado, que es lo unico que hace que una imagen sirva para
 *     varias municipalidades;
 *   · y que una cadena en blanco cuente como ausencia y no como valor.
 */

afterEach(() => {
  delete window.__KAMAYUK_RENTAS__;
  vi.unstubAllEnvs();
  vi.resetModules();
});

describe('lo que sirve el contenedor gana a lo que Vite horneo', () => {
  it('toma la senia servida', () => {
    window.__KAMAYUK_RENTAS__ = { oidcRealm: 'https://muni.example/keycloak/realms/sgtm' };
    expect(configuracion('oidcRealm')).toBe('https://muni.example/keycloak/realms/sgtm');
    expect(procedencia('oidcRealm')).toBe('servida');
  });

  it('sin nada servido cae al valor por omision, que es el de la instalacion local', () => {
    expect(configuracion('oidcRealm')).toBe('http://localhost:8181/realms/sgtm');
    expect(configuracion('oidcCliente')).toBe('sgtm-backoffice');
    expect(configuracion('oidcAlcance')).toBe('openid profile');
    expect(procedencia('oidcRealm')).toBe('omision');
  });

  /** Cada senia se resuelve sola: servir una no puede arrastrar a las otras dos. */
  it('una senia servida no afecta a las demas', () => {
    window.__KAMAYUK_RENTAS__ = { oidcCliente: 'rentas-web' };
    expect(configuracion('oidcCliente')).toBe('rentas-web');
    expect(procedencia('oidcRealm')).toBe('omision');
  });
});

/**
 * Una llave puesta y sin rellenar es un error de despliegue, no un valor.
 *
 * Y hay que separarlo porque el dano es concreto: con el realm en blanco, `identidad.ts` compone
 * `"/protocol/openid-connect/auth"` —una ruta de la propia interfaz— y el navegador la pide a su
 * mismo origen. El `try_files` del nginx contesta **200 con el `index.html` dentro**, o sea que
 * el rebote a Keycloak se convierte en una pagina en blanco sin un solo error. Es el «200 que
 * miente» de #44 aplicado a la puerta de identidad.
 */
describe('una cadena en blanco cuenta como ausencia', () => {
  it.each(['', '   ', '\n'])('«%s» no se toma como valor', (vacia) => {
    window.__KAMAYUK_RENTAS__ = { oidcRealm: vacia };
    expect(configuracion('oidcRealm')).toBe('http://localhost:8181/realms/sgtm');
    expect(procedencia('oidcRealm')).toBe('omision');
  });

  it('y una senia con espacios alrededor se limpia en vez de rechazarse', () => {
    window.__KAMAYUK_RENTAS__ = { oidcRealm: '  https://muni.example/realms/sgtm \n' };
    expect(configuracion('oidcRealm')).toBe('https://muni.example/realms/sgtm');
  });
});

/**
 * El escalon de en medio: lo que Vite horneo al construir.
 *
 * Se prueba con `resetModules` + `import()` porque `DE_LA_CONSTRUCCION` se evalua al importar el
 * modulo, que es exactamente lo que Vite hace al empaquetar.
 */
describe('el escalon horneado sigue existiendo, y queda por debajo del servido', () => {
  it('se usa cuando no hay nada servido', async () => {
    vi.stubEnv('VITE_KAMAYUK_OIDC_REALM', 'https://horneado.example/realms/sgtm');
    vi.resetModules();
    const modulo = await import('./configuracion.ts');
    expect(modulo.configuracion('oidcRealm')).toBe('https://horneado.example/realms/sgtm');
    expect(modulo.procedencia('oidcRealm')).toBe('construccion');
  });

  it('pero lo servido lo gana, que es lo que hace que una imagen sirva para varias municipalidades', async () => {
    vi.stubEnv('VITE_KAMAYUK_OIDC_REALM', 'https://horneado.example/realms/sgtm');
    vi.resetModules();
    const modulo = await import('./configuracion.ts');
    window.__KAMAYUK_RENTAS__ = { oidcRealm: 'https://servido.example/realms/sgtm' };
    expect(modulo.configuracion('oidcRealm')).toBe('https://servido.example/realms/sgtm');
    expect(modulo.procedencia('oidcRealm')).toBe('servida');
  });
});
