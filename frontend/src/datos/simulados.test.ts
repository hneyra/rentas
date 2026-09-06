import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { OPERACIONES, claveDe } from './operaciones.ts';
import { SIMULADOS, simulado } from './simulados.ts';
import { YA_SERVIDAS, laSirveElBackend } from './servidas.ts';

/**
 * La invencion esta apartada de la captura, y cada pieza nombra quien se la llevara.
 *
 * «Si no puedes nombrar la operacion que lo sustituira, no pertenece aqui» es la regla del
 * archivo, y esta prueba es lo que impide que sea solo una frase: la operacion de cada entrada
 * tiene que existir en `docs/50-api/formas-de-la-api.json`, que es el catalogo de lo que este
 * backend publica de verdad.
 *
 * Y al reves: una invencion que nadie usa es peor que una que se usa, porque nadie la revisa.
 * Por eso tambien se comprueba que el proxy pida cada clave declarada.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FORMAS = join(AQUI, '../../../docs/50-api/formas-de-la-api.json');

const declaradas = Object.keys(JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, unknown>);
const fuenteDeLasOperaciones = readFileSync(join(AQUI, 'operaciones.ts'), 'utf8');

describe('cada invencion nombra la operacion que la sustituira', () => {
  it.each(SIMULADOS.map((s) => ({ ...s })))(
    '«$clave» → $operacion',
    ({ clave, operacion, porQue }) => {
      expect(
        declaradas,
        `El simulado «${clave}» dice que lo sustituira «${operacion}», que no es una\n` +
          'operacion de este backend. Un valor inventado sin operacion que lo reemplace no es\n' +
          'un hueco temporal: es una decision de producto tomada en el frontend.',
      ).toContain(operacion);

      expect(porQue.length, `El simulado «${clave}» no dice por que el prototipo no lo trae.`)
        .toBeGreaterThan(20);
    },
  );

  it('ninguna clave esta declarada dos veces', () => {
    const claves = SIMULADOS.map((s) => s.clave);
    expect(claves).toEqual([...new Set(claves)]);
  });
});

describe('no hay invenciones muertas', () => {
  it.each(SIMULADOS.map((s) => s.clave))('el proxy pide «%s»', (clave) => {
    expect(
      fuenteDeLasOperaciones,
      `Nadie usa el simulado «${clave}».\n` +
        'Una invencion que no se sirve no la revisa nadie, y sobrevive a la operacion que\n' +
        'venia a sustituirla. Borrala, o sirvela.',
    ).toContain(`'${clave}'`);
  });

  it('pedir una clave que no esta declarada revienta, y dice por que', () => {
    expect(() => simulado('loQueSeMeOcurra')).toThrowError(/no esta declarado en simulados.ts/);
  });
});

describe('lo capturado no se mezcla con lo inventado', () => {
  it('ninguna operacion inventa por su cuenta: todo pasa por «simulado(...)»', () => {
    // Las claves que el codigo pide tienen que ser las declaradas, ni una mas.
    const pedidas = [...fuenteDeLasOperaciones.matchAll(/simulado<[^>]+>\('([^']+)'\)/g)].map(
      (encontrada) => encontrada[1],
    );
    const declaradasAqui = new Set(SIMULADOS.map((s) => s.clave));

    expect(
      [...new Set(pedidas)].filter((clave) => clave !== undefined && !declaradasAqui.has(clave)),
    ).toEqual([]);
  });
});

describe('la lista de rutas que ya sirve el backend', () => {
  /**
   * **Ya no esta vacia: I-1 encendio las dos primeras.**
   *
   * Hasta entonces esta prueba afirmaba `toEqual([])` y el javadoc de `servidas.ts` daba los dos
   * motivos, los dos comprobables: sin token el backend contesta 401, y sin `server.proxy` la
   * peticion la atiende el servidor de Vite y devuelve el `index.html` con un 200. Los dos
   * estan cerrados, asi que lo que se afirma ahora es **cuales** son y que no son mas: encender
   * una ruta es una decision, y una lista que crece sin que nada lo diga vuelve a convertir la
   * integracion en el salto que este mecanismo existe para evitar.
   */
  it('son las dos lecturas de sesion, y el proxy sigue contestando las dieciocho', () => {
    expect(YA_SERVIDAS.map((o) => `${o.metodo} ${o.ruta}`)).toEqual([
      'GET /seguridad/sesion',
      'GET /seguridad/sesion/municipalidad',
    ]);
    // Trece las trajo F-4; las cuatro de F-5 son las que alimentan el panel del modulo y el
    // estado de cobranza del padron; la de F-6 es la procedencia del conjunto sellado, que es
    // lo unico que el contrato dice de la tabla de valores del ejercicio. **Ninguna de las
    // dieciocho se apaga con esto**: las dos de sesion no estaban entre ellas, asi que el
    // proxy sigue simulando exactamente lo mismo que antes de I-1 (AC9).
    expect(OPERACIONES).toHaveLength(18);
    const simuladas = new Set(OPERACIONES.map((o) => `${o.metodo} ${o.ruta}`));
    expect(YA_SERVIDAS.filter((o) => simuladas.has(`${o.metodo} ${o.ruta}`))).toEqual([]);
  });

  it('y por que son ESAS dos esta escrito, con lo que hizo falta antes', () => {
    const fuente = readFileSync(join(AQUI, 'servidas.ts'), 'utf8');

    // Los dos motivos que la mantenian vacia eran comprobables, y siguen escritos porque son la
    // razon del orden: primero el token, despues el camino, y entonces una entrada aqui.
    expect(fuente).toContain('401');
    expect(fuente).toContain('server.proxy');
  });

  it('el mecanismo funciona aunque la lista este vacia', () => {
    expect(laSirveElBackend(YA_SERVIDAS, 'GET', '/rentas/contribuyentes')).toBe(false);
    expect(
      laSirveElBackend(
        [{ metodo: 'get', ruta: '/rentas/contribuyentes/{id}/ficha' }],
        'GET',
        '/rentas/contribuyentes/25673/ficha',
      ),
    ).toBe(true);
    // Un parametro no se come una barra: la ruta con dos segmentos no es la de uno.
    expect(
      laSirveElBackend(
        [{ metodo: 'GET', ruta: '/rentas/vehiculos/{placa}' }],
        'GET',
        '/rentas/vehiculos/T2R-418/actos',
      ),
    ).toBe(false);
  });

  it('toda operacion servida por el proxy tiene su clave en el catalogo del backend', () => {
    expect(OPERACIONES.map((o) => claveDe(o)).filter((c) => !declaradas.includes(c))).toEqual([]);
  });
});
