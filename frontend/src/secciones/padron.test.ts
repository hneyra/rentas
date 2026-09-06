import { describe, expect, it } from 'vitest';

import type {
  ContribuyenteDelPadron,
  DeudaEnCoactiva,
  ObservadoDeLaCorrida,
} from '../datos/lecturas.ts';
import { componerPadron, filtrar, ordenar } from './padron.ts';

/**
 * La aritmetica del padron, sin montar nada.
 *
 * Componer, filtrar y ordenar son funciones puras y se prueban como tales: montar la seccion
 * para comprobar que «Nombre» ordena por nombre diria menos —la lista de la pantalla depende
 * ademas de tres peticiones— y costaria un `render`. Lo que si se prueba montado, en
 * `Contribuyentes.test.tsx`, es que la pantalla las use.
 */

const uno = (
  codigo: string,
  nombre: string,
  extra: Partial<ContribuyenteDelPadron> = {},
): ContribuyenteDelPadron => ({
  id: Number(codigo),
  codigo,
  tipoDocumento: 'DNI',
  numeroDocumento: '03593174',
  tipoPersona: 'Persona natural',
  nombreRazonSocial: nombre,
  condicionEspecial: null,
  activo: true,
  ...extra,
});

const PADRON: readonly ContribuyenteDelPadron[] = [
  uno('00000025673', 'Suc. Rufina Medina Medina'),
  uno('00000003541', 'Castillo Pascuala, María Elena', { numeroDocumento: '44218937' }),
  uno('00000006550', 'Díaz Madrid, Julio César', { numeroDocumento: '02718844' }),
  uno('00000006551', 'Noblecilla Arismendiz S.A.C.', {
    tipoDocumento: 'RUC',
    numeroDocumento: '20525118447',
    tipoPersona: 'Persona jurídica',
  }),
  uno('00000152614', 'Valdez Ríos, Oliver Fabián', {
    numeroDocumento: '41182844',
    activo: false,
  }),
];

const COACTIVA: readonly DeudaEnCoactiva[] = [
  {
    expediente: '2026-0418',
    ano: 2026,
    codContribuyente: '00000006550',
    contribuyente: 'Díaz Madrid, Julio César',
    deudaS: '9412.15',
    costasS: '0.00',
    totalS: '9412.15',
    aLaFecha: '2026-08-12',
    estado: 'En coactiva',
  },
];

const OBSERVADOS: readonly ObservadoDeLaCorrida[] = [
  {
    codContribuyente: '00000006551',
    nombre: 'Noblecilla Arismendiz S.A.C.',
    motivo: 'El predio no tiene arancel de vía',
  },
];

const filas = componerPadron(PADRON, COACTIVA, OBSERVADOS);

describe('componer la fila del padron de las tres respuestas', () => {
  it('quien tiene expediente coactivo se ensena «En coactiva», con su importe y su fecha', () => {
    const diaz = filas.find((fila) => fila.contribuyente.codigo === '00000006550');

    expect(diaz?.estado).toBe('En coactiva');
    expect(diaz?.expediente).toBe('2026-0418');
    // El importe NO viaja solo: es la regla 9, y aqui es lo unico que permite ensenar una cifra
    // en la lista sin inventarla.
    expect(diaz?.importe).toEqual({ importe: '9412.15', actualizadoA: '2026-08-12' });
  });

  it('quien quedo fuera de la emision se ensena «Observado», con su motivo', () => {
    const noblecilla = filas.find((fila) => fila.contribuyente.codigo === '00000006551');

    expect(noblecilla?.estado).toBe('Observado');
    expect(noblecilla?.motivo).toBe('El predio no tiene arancel de vía');
    // Y sin importe: la corrida dice quien quedo fuera, no cuanto debe.
    expect(noblecilla?.importe).toBeNull();
  });

  it('quien no esta en ninguna de las dos se ensena con lo que SI publica el padron', () => {
    // «Activo» y «De baja» salen de `activo`, que es el unico estado que la operacion del
    // padron publica. No es «Al día»: nadie ha dicho que este al dia.
    expect(filas.map((fila) => fila.estado)).toEqual([
      'Activo',
      'Activo',
      'En coactiva',
      'Observado',
      'De baja',
    ]);
  });

  it('ninguna fila se inventa un importe', () => {
    expect(filas.filter((fila) => fila.importe !== null)).toHaveLength(1);
  });

  it('conserva el orden en que llego el padron', () => {
    expect(filas.map((fila) => fila.contribuyente.codigo)).toEqual(
      PADRON.map((quien) => quien.codigo),
    );
  });
});

describe('el buscador mira lo que la caja promete: «Nombre, DNI, RUC o código»', () => {
  it('por nombre, sin distinguir mayusculas', () => {
    expect(filtrar(filas, 'castillo', 'Todos').map((fila) => fila.contribuyente.codigo)).toEqual([
      '00000003541',
    ]);
  });

  it('por numero de documento', () => {
    expect(filtrar(filas, '20525118447', 'Todos').map((fila) => fila.contribuyente.codigo)).toEqual(
      ['00000006551'],
    );
  });

  it('por codigo, aunque se teclee un trozo', () => {
    expect(filtrar(filas, '152614', 'Todos').map((fila) => fila.contribuyente.codigo)).toEqual([
      '00000152614',
    ]);
  });

  it('por tipo de documento: «RUC» saca a la persona juridica', () => {
    expect(filtrar(filas, 'RUC', 'Todos').map((fila) => fila.contribuyente.codigo)).toEqual([
      '00000006551',
    ]);
  });

  it('lo que no casa con nadie devuelve la lista vacia, que es lo que dispara el AC3', () => {
    expect(filtrar(filas, 'Zzz', 'Todos')).toEqual([]);
  });

  it('los espacios de los lados no cuentan', () => {
    expect(filtrar(filas, '   castillo  ', 'Todos')).toHaveLength(1);
  });
});

describe('los cuatro chips del artboard', () => {
  it('«Todos» no filtra', () => {
    expect(filtrar(filas, '', 'Todos')).toHaveLength(5);
  });

  it('«En coactiva» saca al que tiene expediente', () => {
    expect(filtrar(filas, '', 'En coactiva').map((fila) => fila.contribuyente.codigo)).toEqual([
      '00000006550',
    ]);
  });

  it('«Observado» saca al que quedo sin emision', () => {
    expect(filtrar(filas, '', 'Observado').map((fila) => fila.contribuyente.codigo)).toEqual([
      '00000006551',
    ]);
  });

  it('«Con deuda» no saca a nadie, porque nadie publica quien debe', () => {
    // **Es el hallazgo, y esta escrito como prueba a proposito.** Ninguna de las 181 operaciones
    // publica, por contribuyente y en una lista, el estado de cobranza con su deuda; el chip
    // existe porque el artboard lo dibuja, filtra, y sale vacio con su motivo escrito en la
    // pantalla. El dia que el padron publique el saldo, esta prueba es la que hay que cambiar.
    expect(filtrar(filas, '', 'Con deuda')).toEqual([]);
  });

  it('el chip y la busqueda se aplican los DOS, no uno u otro', () => {
    expect(filtrar(filas, 'Noblecilla', 'En coactiva')).toEqual([]);
    expect(filtrar(filas, 'Noblecilla', 'Observado')).toHaveLength(1);
  });
});

describe('los tres ordenes', () => {
  it('«Código» deja el orden en que llego el padron', () => {
    expect(ordenar(filas, 'Código')).toEqual(filas);
  });

  it('«Nombre» ordena en castellano: la «Í» de Díaz va antes que la «N» de Noblecilla', () => {
    expect(ordenar(filas, 'Nombre').map((fila) => fila.contribuyente.nombreRazonSocial)).toEqual([
      'Castillo Pascuala, María Elena',
      'Díaz Madrid, Julio César',
      'Noblecilla Arismendiz S.A.C.',
      'Suc. Rufina Medina Medina',
      'Valdez Ríos, Oliver Fabián',
    ]);
  });

  it('«Deuda» pone delante al que mas debe, y al que nadie publica lo deja al final', () => {
    const dos = componerPadron(
      PADRON,
      [
        ...COACTIVA,
        {
          ...COACTIVA[0]!,
          expediente: '2026-0002',
          codContribuyente: '00000025673',
          contribuyente: 'Suc. Rufina Medina Medina',
          deudaS: '1842.60',
          totalS: '1842.60',
        },
      ],
      OBSERVADOS,
    );

    expect(ordenar(dos, 'Deuda').map((fila) => fila.contribuyente.codigo).slice(0, 2)).toEqual([
      '00000006550',
      '00000025673',
    ]);
    // Los tres sin importe van detras, y en su orden: no es que deban cero, es que nadie
    // publica cuanto deben — colocarlos entre los ceros diria que estan al dia.
    expect(
      ordenar(dos, 'Deuda')
        .slice(2)
        .every((fila) => fila.importe === null),
    ).toBe(true);
  });

  it('ordenar no muta la lista que recibe', () => {
    const antes = filas.map((fila) => fila.contribuyente.codigo);
    ordenar(filas, 'Nombre');
    expect(filas.map((fila) => fila.contribuyente.codigo)).toEqual(antes);
  });
});
