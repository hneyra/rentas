import { describe, expect, it } from 'vitest';

import type {
  ContribuyenteDelPadron,
  DeudaEnCoactiva,
  ObservadoDeLaCorrida,
  Paginado,
} from '../datos/lecturas.ts';
import {
  CRITERIOS,
  ORDENES_DEL_PADRON,
  componerPadron,
  criterioDe,
  rutaDelDocumento,
  rutaDelPadron,
} from './padron.ts';

/**
 * La aritmetica del padron y **la consulta que se le manda al backend**, sin montar nada.
 *
 * Componer y componer la ruta son funciones puras y se prueban como tales: montar la seccion
 * para comprobar que el criterio viaja en la URL diria menos —la lista de la pantalla depende
 * ademas de tres peticiones— y costaria un `render`. Lo que si se prueba montado, en
 * `Contribuyentes.test.tsx`, es que la pantalla las use y que lo que salio por el cable sea esto.
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
  tipoPersona: 'NATURAL',
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
    tipoPersona: 'JURIDICA',
  }),
  uno('00000152614', 'Valdez Ríos, Oliver Fabián', {
    numeroDocumento: '41182844',
    activo: false,
  }),
];

/** Envuelve una lista como la envuelve el backend. `total` por omision: la lista entera. */
function ventana<T>(contenido: readonly T[], total = contenido.length): Paginado<T> {
  return {
    contenido,
    pagina: 0,
    tamano: 20,
    totalElementos: total,
    totalPaginas: Math.ceil(total / 20),
    hayMas: contenido.length < total,
  };
}

const EN_COACTIVA: DeudaEnCoactiva = {
  expediente: '2026-0418',
  ano: 2026,
  codContribuyente: '00000006550',
  contribuyente: 'Díaz Madrid, Julio César',
  deudaS: '9412.15',
  costasS: '0.00',
  totalS: '9412.15',
  aLaFecha: '2026-08-12',
  estado: 'En coactiva',
};

const OBSERVADO: ObservadoDeLaCorrida = {
  codContribuyente: '00000006551',
  nombre: 'Noblecilla Arismendiz S.A.C.',
  motivo: 'El predio no tiene arancel de vía',
};

const compuesto = componerPadron(PADRON, ventana([EN_COACTIVA]), ventana([OBSERVADO]));
const filas = compuesto.filas;

describe('componer la fila del padron de las tres respuestas', () => {
  it('quien tiene expediente coactivo se ensena «En coactiva», con su importe y su fecha', () => {
    const diaz = filas.find((fila) => fila.contribuyente.codigo === '00000006550');

    expect(diaz?.estado).toBe('En coactiva');
    expect(diaz?.expediente).toBe('2026-0418');
    // El importe NO viaja solo: es la regla 9, y aqui es lo unico que permite ensenar una cifra
    // en la lista sin inventarle una fecha.
    expect(diaz?.importe).toEqual({ importe: '9412.15', actualizadoA: '2026-08-12' });
  });

  it('quien quedo fuera de la emision se ensena «Observado», con su motivo', () => {
    const noblecilla = filas.find((fila) => fila.contribuyente.codigo === '00000006551');

    expect(noblecilla?.estado).toBe('Observado');
    expect(noblecilla?.motivo).toBe('El predio no tiene arancel de vía');
    // Nadie publica cuanto debe: la fila no lleva importe, y no lleva un cero.
    expect(noblecilla?.importe).toBeNull();
  });

  it('a quien nadie clasifica se le ensena lo que el padron publica de el, y nada mas', () => {
    const rufina = filas.find((fila) => fila.contribuyente.codigo === '00000025673');
    const oliver = filas.find((fila) => fila.contribuyente.codigo === '00000152614');

    // «Activo» y «De baja» salen de `activo`. **No es «Al día»**: nadie ha dicho que lo esten.
    expect(rufina?.estado).toBe('Activo');
    expect(oliver?.estado).toBe('De baja');
    expect(filas.map((fila) => fila.estado)).not.toContain('Al día');
  });

  it('el orden es el que llego, y no se reordena', () => {
    expect(filas.map((fila) => fila.contribuyente.codigo)).toEqual(
      PADRON.map((quien) => quien.codigo),
    );
  });
});

describe('AC5 — la insignia solo se afirma si la lista que la sostiene llego ENTERA', () => {
  it('con las dos listas completas, el estado se da por bueno', () => {
    expect(compuesto.estadoCompleto).toBe(true);
  });

  it('con las DOS vacias y completas tambien: vacio no es incompleto', () => {
    // Es el caso medido contra la instalacion: las dos contestan 200 con lista vacia. «No hay
    // nadie en coactiva» es un dato, y un dato completo.
    const sinNadie = componerPadron(PADRON, ventana([]), ventana([]));

    expect(sinNadie.estadoCompleto).toBe(true);
    expect(sinNadie.filas.map((fila) => fila.estado)).toEqual([
      'Activo',
      'Activo',
      'Activo',
      'Activo',
      'De baja',
    ]);
  });

  it('con una RECORTADA, no: no aparecer en una pagina no es no estar en la lista', () => {
    // 1 de 400. Quien esta en coactiva y cae en la pagina 7 se dibujaria «Activo», que es una
    // afirmacion sobre una persona hecha por omision.
    const recortada = componerPadron(PADRON, ventana([EN_COACTIVA], 400), ventana([OBSERVADO]));

    expect(recortada.estadoCompleto).toBe(false);
    // Y lo que SI aparece en la ventana se sigue marcando: lo que no se puede afirmar es la
    // ausencia, no la presencia.
    expect(recortada.filas.find((fila) => fila.contribuyente.codigo === '00000006550')?.estado).toBe(
      'En coactiva',
    );
  });

  it('sin ninguna de las dos —todavia no llegaron— tampoco se afirma nada', () => {
    expect(componerPadron(PADRON, null, null).estadoCompleto).toBe(false);
  });
});

describe('AC3 — el criterio viaja en la URL, con el nombre que el backend lee', () => {
  it('los cuatro que la operacion admite, y ninguno mas', () => {
    // `dNI` y `rUC` con la mayuscula corrida: es como los declara `ContribuyenteController`, y
    // escribirlos «bien» haria que el filtro no filtrara y volviera el padron entero.
    expect(CRITERIOS.map((uno) => [uno.rotulo, uno.parametro])).toEqual([
      ['Nombre', 'nombreRazonSocial'],
      ['Código', 'codigo'],
      ['DNI', 'dNI'],
      ['RUC', 'rUC'],
    ]);
  });

  it('buscar por nombre manda `nombreRazonSocial`, y NO manda orden', () => {
    const ruta = rutaDelPadron('/rentas/contribuyentes', {
      criterio: 'Nombre',
      texto: 'sulon vilchez',
      orden: 'Código',
      pagina: 0,
    });

    expect(ruta).toBe(
      '/rentas/contribuyentes?nombreRazonSocial=sulon%20vilchez&pagina=0&tamano=20',
    );
    // Sin `ordenarPor`: con nombre el backend ordena por parecido, y pedirle otro orden dejaria
    // el mejor parecido fuera de la primera pagina.
    expect(ruta).not.toContain('ordenarPor');
  });

  it('los tres exactos mandan el suyo, y el orden si viaja', () => {
    const porCodigo = rutaDelPadron('/rentas/contribuyentes', {
      criterio: 'Código',
      texto: '00000000008',
      orden: 'Nombre',
      pagina: 0,
    });

    expect(porCodigo).toContain('codigo=00000000008');
    expect(porCodigo).toContain('ordenarPor=nombreRazonSocial');
    expect(
      rutaDelPadron('/rentas/contribuyentes', {
        criterio: 'DNI',
        texto: '29614026',
        orden: 'Código',
        pagina: 0,
      }),
    ).toContain('dNI=29614026');
    expect(
      rutaDelPadron('/rentas/contribuyentes', {
        criterio: 'RUC',
        texto: '20602546391',
        orden: 'Código',
        pagina: 0,
      }),
    ).toContain('rUC=20602546391');
  });

  it('sin texto no se manda criterio ninguno: el padron entero, paginado', () => {
    expect(
      rutaDelPadron('/rentas/contribuyentes', {
        criterio: 'Nombre',
        texto: '   ',
        orden: 'Código',
        pagina: 3,
      }),
    ).toBe('/rentas/contribuyentes?pagina=3&tamano=20&ordenarPor=codigoContribuyente');
  });

  it('«Código» y «DNI» y «RUC» se declaran EXACTOS, que es lo que hace el SQL', () => {
    // Medido: `?codigo=000000000` sobre codigos que todos empiezan por ceros devuelve 0, porque
    // la condicion es `codigo_contribuyente = :codigo`. La pantalla lo dice antes de que alguien
    // teclee medio codigo y concluya que no existe.
    expect(CRITERIOS.filter((uno) => uno.exacto).map((uno) => uno.rotulo)).toEqual([
      'Código',
      'DNI',
      'RUC',
    ]);
    expect(criterioDe('Nombre').exacto).toBe(false);
  });
});

describe('AC3 — los ordenes son los que el backend admite, y «Deuda» no esta', () => {
  it('dos, con el campo que los pide', () => {
    // Medido: `?ordenarPor=deuda` contesta 422 `ORDEN_NO_ADMITIDO`, «Campo pedido: deuda». No
    // esta en la lista blanca porque esta operacion no publica la deuda.
    expect(ORDENES_DEL_PADRON.map((uno) => [uno.rotulo, uno.campo])).toEqual([
      ['Código', 'codigoContribuyente'],
      ['Nombre', 'nombreRazonSocial'],
    ]);
    expect(ORDENES_DEL_PADRON.map((uno) => uno.rotulo)).not.toContain('Deuda');
  });
});

describe('AC2 — la pagina viaja, y el tamano tambien', () => {
  it('la pagina se cuenta desde 0, como la cuenta el backend', () => {
    expect(
      rutaDelPadron('/rentas/contribuyentes', {
        criterio: 'Nombre',
        texto: '',
        orden: 'Código',
        pagina: 530,
      }),
    ).toContain('pagina=530');
  });
});

describe('la compuerta del documento pregunta por lo que se puede preguntar', () => {
  it('un DNI y un RUC se preguntan, con su parametro', () => {
    expect(rutaDelDocumento('/rentas/contribuyentes', 'DNI', '29614026')).toBe(
      '/rentas/contribuyentes?dNI=29614026',
    );
    expect(rutaDelDocumento('/rentas/contribuyentes', 'RUC', '20602546391')).toBe(
      '/rentas/contribuyentes?rUC=20602546391',
    );
  });

  it('un carne de extranjeria NO, y por eso devuelve `null` en vez de una ruta', () => {
    // El controlador publica `dNI` y `rUC` y ningun parametro para los demas tipos. Devolver
    // una ruta sin filtro traeria el padron entero y su primera fila se leeria como «ya existe».
    expect(rutaDelDocumento('/rentas/contribuyentes', 'Carnet de extranjería', '001234567890')).toBeNull();
  });

  it('y sin numero tampoco se pregunta', () => {
    expect(rutaDelDocumento('/rentas/contribuyentes', 'DNI', '')).toBeNull();
  });
});
