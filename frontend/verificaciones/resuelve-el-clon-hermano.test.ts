// @vitest-environment node

import { describe, expect, it } from 'vitest';

import { peldanoDe } from '@kamayuk/sesion';
import { ErrorDeLaApi } from '@kamayuk/api';
import { formatearImporte, sumarImportes } from '@kamayuk/formato';

/**
 * **El enlace con `kamayuk-lib` resuelve de verdad** (#74).
 *
 * Un `link:` que nadie importa no demuestra que el enlace funcione: demuestra que yarn escribio
 * un symlink. Esto lo atraviesa — `tsc` compila las fuentes del hermano, `vitest` las ejecuta y
 * `vite build` las empaqueta, los tres resolviendo el symlink a su ruta real.
 *
 * <h2>Este archivo SI puede morir sin el clon hermano, y es correcto</h2>
 *
 * Sin `kamayuk-lib`, estos tres imports fallan en la recoleccion y las pruebas de aqui
 * desaparecen. Es aceptable **porque `enlace-con-kamayuk-lib.test.ts` ya hablo**: aquel no
 * importa nada del hermano, asi que carga siempre y dice el `git clone` que falta. Separarlos es
 * la leccion que costo una vuelta: cuando los dos vivian juntos, el archivo moria entero y la
 * guarda se callaba justo cuando tenia que hablar.
 *
 * <h2>Y desde #75, `src/` tambien puede</h2>
 *
 * Este archivo nacio importando desde `verificaciones/` y no desde `src/` porque el destino del
 * `link:` quedaba fuera del contexto de Docker. #75 lo cerro con un contexto con nombre de
 * BuildKit, asi que esa abstencion ya no hace falta.
 */

describe('y el enlace RESUELVE: los tres paquetes se importan y se ejecutan', () => {
  // Un `link:` que nadie importa no demuestra que el enlace funcione: demuestra que yarn
  // escribio un symlink. Esto lo atraviesa de verdad — `tsc` lo compila, `vitest` lo ejecuta y
  // `vite build` lo empaqueta, los tres resolviendo el symlink a su ruta real.

  it('@kamayuk/formato formatea un importe sin tocar `Number`', () => {
    expect(formatearImporte('1842.6')).toBe('S/ 1,842.60');
    expect(sumarImportes(['1842.60', '0.40'])).toBe('1843.00');
  });

  it('@kamayuk/api construye el error del contrato con su codigo', () => {
    const fallo = new ErrorDeLaApi(403, 'GET /seguridad/sesion', {
      codigo: 'SIN_MUNICIPALIDAD',
      mensaje: 'El token no identifica una municipalidad',
    });

    expect(fallo.estado).toBe(403);
    expect(fallo.codigo).toBe('SIN_MUNICIPALIDAD');
  });

  it('@kamayuk/sesion traduce ese error a su peldano, con su remedio', () => {
    const peldano = peldanoDe(
      new ErrorDeLaApi(403, 'GET /seguridad/sesion', { codigo: 'SIN_MUNICIPALIDAD' }),
    );

    expect(peldano.clave).toBe('sin-municipalidad');
    // Y lo que sostiene la escalera entera: esto NO es una averia. El backend contesto lo que
    // tenia que contestar, y pintarlo de rojo manda a mirar un despliegue cuando lo que falta
    // es una fila en una tabla de permisos.
    expect(peldano.esAveria).toBe(false);
    expect(peldano.pideIdentidad).toBe(false);
  });

  it('los tres paquetes cruzan la frontera de repositorio, y eso es lo que se esta probando', () => {
    // `@kamayuk/sesion` importa a `@kamayuk/api` por ruta relativa dentro del hermano
    // (`kamayuk-lib`#4), asi que esta cadena atraviesa dos paquetes del otro repositorio sin
    // que este tenga que saber nada de su disposicion interna.
    const peldano = peldanoDe(new TypeError('sin red'));
    expect(peldano.clave).toBe('averia');
    expect(peldano.esAveria).toBe(true);
  });
});
