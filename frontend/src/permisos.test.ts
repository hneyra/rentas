import { describe, expect, it } from 'vitest';

import { CATALOGO, CODIGO_POR_CLAVE } from './catalogo.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
  sinLosAccesosDe,
} from './datos/seguridadMedida.ts';
import { DE_OTRO_SISTEMA, componer } from './permisos.ts';

/**
 * **Lo que la cuenta no puede abrir, no se ofrece** (#105).
 *
 * Se prueba contra `seguridadMedida.ts`, que son **respuestas de `curl` a la instalacion de
 * verdad** —134 accesos, doce modulos, la matriz entera— y no invenciones. Un doble inventado
 * probaria que la funcion hace lo que la funcion hace; esto prueba que hace lo correcto **con lo
 * que el backend contesta**.
 */

const codigoDe = (m: { readonly clave: string }) => CODIGO_POR_CLAVE.get(m.clave) ?? '';
const componerMedido = (permisos = PERMISOS_MEDIDOS) =>
  componer(CATALOGO, MODULOS_MEDIDOS, ACCESOS_MEDIDOS, permisos, codigoDe);

describe('el catalogo se compone de lo que la cuenta puede abrir', () => {
  it('EL CENTINELA: lo medido trae doce modulos y 134 accesos', () => {
    // Sin esto, unas capturas vacias dejarian todo lo de abajo comprobando que de nada sale nada.
    expect(MODULOS_MEDIDOS).toHaveLength(12);
    expect(ACCESOS_MEDIDOS).toHaveLength(134);
    expect(Object.keys(PERMISOS_MEDIDOS).length).toBeGreaterThan(100);
  });

  it('con la cuenta de la instalacion salen los DIEZ de este sistema', () => {
    const { catalogo } = componerMedido();
    expect(catalogo).toHaveLength(10);
    expect(catalogo.map((m) => m.clave)).toEqual([
      'inicio',
      'rentas',
      'fisc',
      'transito',
      'infra',
      'consultas',
      'valores-mod',
      'coactiva',
      'autoriz',
      'seguridad',
    ]);
  });

  it('y el ORDEN es el del backend, no el del arbol', () => {
    // En el arbol, `coactiva` va antes que `valores-mod`; el backend los publica al reves. Quien
    // decide en que orden se ensenan los modulos es el backend.
    const { catalogo } = componerMedido();
    const enElArbol = CATALOGO.map((m) => m.clave);
    expect(catalogo.map((m) => m.clave)).not.toEqual(enElArbol);
    expect(catalogo.indexOf(catalogo.filter((m) => m.clave === 'valores-mod')[0]!)).toBeLessThan(
      catalogo.indexOf(catalogo.filter((m) => m.clave === 'coactiva')[0]!),
    );
  });

  it('los dos modulos de OTRO SISTEMA se restan, con su motivo', () => {
    // El backend los publica y es correcto: el catalogo de seguridad es del clúster, que los
    // cuatro sistemas comparten. Lo que este marco no tiene es una pantalla que abrirles.
    const { deOtroSistema, catalogo } = componerMedido();
    expect(deOtroSistema).toEqual(['CATASTRO', 'TESORERIA']);
    expect([...DE_OTRO_SISTEMA.keys()]).toEqual(['CATASTRO', 'TESORERIA']);
    expect(catalogo.map((m) => m.clave)).not.toContain('catastro');
  });

  it('un modulo que el backend publique y este sistema NO tenga no se cuela', () => {
    const conUnoNuevo = [
      ...MODULOS_MEDIDOS,
      { id: 999, codigo: 'PADRON_ELECTORAL', nombre: 'Padron electoral', orden: 99, activo: true },
    ];
    const { catalogo, sinCatalogo } = componer(
      CATALOGO,
      conUnoNuevo,
      ACCESOS_MEDIDOS,
      PERMISOS_MEDIDOS,
      codigoDe,
    );
    // No se cuela, y **se cuenta**: descartarlo en silencio dejaria un modulo publicado que esta
    // interfaz ignora sin que nadie lo sepa.
    expect(catalogo).toHaveLength(10);
    expect(sinCatalogo).toEqual(['PADRON_ELECTORAL']);
  });

  it('un modulo INACTIVO no se ofrece', () => {
    const conUnoApagado = MODULOS_MEDIDOS.map((m) =>
      m.codigo === 'TRANSITO' ? { ...m, activo: false } : m,
    );
    const { catalogo } = componer(
      CATALOGO,
      conUnoApagado,
      ACCESOS_MEDIDOS,
      PERMISOS_MEDIDOS,
      codigoDe,
    );
    expect(catalogo.map((m) => m.clave)).not.toContain('transito');
    expect(catalogo).toHaveLength(9);
  });

  it('sin permiso de LECTURA sobre ninguno de sus accesos, el modulo no se ofrece — y se cuenta', () => {
    const { catalogo, sinPermiso } = componerMedido(sinLosAccesosDe('TRANSITO'));
    expect(catalogo.map((m) => m.clave)).not.toContain('transito');
    expect(sinPermiso).toContain('TRANSITO');
  });

  it('sin NINGUN permiso, el catalogo sale vacio y los diez se cuentan', () => {
    // Que es distinto de un error: es una cuenta sin permisos, y quien la mire tiene que poder
    // distinguirlo de un backend caido.
    const { catalogo, sinPermiso } = componerMedido({});
    expect(catalogo).toEqual([]);
    expect(sinPermiso).toHaveLength(10);
  });

  it('el ROTULO es el del backend, no el del artboard', () => {
    const renombrado = MODULOS_MEDIDOS.map((m) =>
      m.codigo === 'TRANSITO' ? { ...m, nombre: 'Movilidad urbana' } : m,
    );
    const { catalogo } = componer(
      CATALOGO,
      renombrado,
      ACCESOS_MEDIDOS,
      PERMISOS_MEDIDOS,
      codigoDe,
    );
    // El dia que la municipalidad renombre un modulo, el arbol dice el nombre nuevo sin que nadie
    // toque este repositorio. Es la mitad util de haber conectado el arbol.
    const transito = catalogo.find((m) => m.clave === 'transito');
    expect(transito?.rotulo).toBe('Movilidad urbana');
    expect(CATALOGO.find((m) => m.clave === 'transito')?.rotulo).not.toBe('Movilidad urbana');
  });

  it('un privilegio que no sea lista no tumba el arbol', () => {
    // Esto viene de la red y el contrato promete «objeto». Un valor raro tiene que descartar ese
    // acceso, no la aplicacion entera.
    const raros = { ...PERMISOS_MEDIDOS, papeletas: 'lectura' as unknown as readonly string[] };
    expect(() => componerMedido(raros)).not.toThrow();
  });
});
