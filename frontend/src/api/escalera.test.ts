import { describe, expect, it } from 'vitest';

import { ErrorDeLaApi } from './cliente.ts';
import { peldanoDe } from './escalera.ts';

/**
 * Los cuatro peldanos, sin montar nada (AC6).
 *
 * Aqui se mide **el mapa**: que estado y que codigo llevan a que remedio. Que ademas se vea en
 * pantalla se mide en `aplicacion.test.tsx`, y las dos mitades hacen falta: un mapa correcto que
 * nadie dibuja no ayuda a nadie, y una pantalla que dibuja el peldano equivocado se ve bien.
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
