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
import { TIPOS_DE_DOCUMENTO } from '../dominio/documento.ts';

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
  it('los tres que la operacion admite, y ninguno mas (#35)', () => {
    // `dNI` y `rUC` se fueron con #35: no eran camelCase de nada y, sobre todo, con dos filtros
    // solo se podian comprobar dos de los seis tipos de documento. Ahora es un numero, y el
    // tipo lo manda quien sabe cual es —la compuerta del alta—.
    expect(CRITERIOS.map((uno) => [uno.rotulo, uno.parametro])).toEqual([
      ['Nombre', 'nombreRazonSocial'],
      ['Código', 'codigo'],
      ['Documento', 'numeroDocumento'],
    ]);
    expect(CRITERIOS.map((uno) => uno.parametro)).not.toContain('dNI');
    expect(CRITERIOS.map((uno) => uno.parametro)).not.toContain('rUC');
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

  it('los dos que no son aproximacion mandan el suyo, y el orden si viaja', () => {
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
        criterio: 'Documento',
        texto: '29614026',
        orden: 'Código',
        pagina: 0,
      }),
    ).toContain('numeroDocumento=29614026');
  });

  it('#35 — media cifra del codigo tambien se manda: el backend busca por prefijo', () => {
    // Antes de #35 esto era una busqueda que no encontraba nada: `codigo_contribuyente =
    // :codigo` sobre un padron cuyos codigos empiezan todos por ceros. Medido contra Catacaos,
    // `?codigo=000000000` devolvia 0 de 10 603 filas y sin error.
    expect(
      rutaDelPadron('/rentas/contribuyentes', {
        criterio: 'Código',
        texto: '000000000',
        orden: 'Código',
        pagina: 0,
      }),
    ).toContain('codigo=000000000');
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

  it('cada criterio declara COMO compara el backend, y son tres cosas distintas (#35)', () => {
    // La pantalla lo dice antes de que alguien teclee medio dato y concluya que no existe. Y son
    // tres y no dos: el codigo dejo de ser una igualdad y pasa a ser un rango `~>=~` / `~<~`,
    // mientras que el documento sigue comparandose entero.
    expect(CRITERIOS.map((uno) => [uno.rotulo, uno.comparacion])).toEqual([
      ['Nombre', 'aproximacion'],
      ['Código', 'prefijo'],
      ['Documento', 'igualdad'],
    ]);
    expect(criterioDe('Nombre').comparacion).toBe('aproximacion');
  });

  it('y solo la aproximacion sustituye al orden: el prefijo no ordena por nada', () => {
    // Con nombre el backend ordena por parecido, asi que pedirle otro orden dejaria el mejor
    // parecido fuera de la primera pagina. Con un prefijo no hay parecido que respetar, y sin
    // esta distincion buscar por codigo dejaria de poder ordenarse.
    const porNombre = rutaDelPadron('/rentas/contribuyentes', {
      criterio: 'Nombre',
      texto: 'sulon',
      orden: 'Nombre',
      pagina: 0,
    });
    const porPrefijo = rutaDelPadron('/rentas/contribuyentes', {
      criterio: 'Código',
      texto: '0000',
      orden: 'Nombre',
      pagina: 0,
    });

    expect(porNombre).not.toContain('ordenarPor');
    expect(porPrefijo).toContain('ordenarPor=nombreRazonSocial');
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

describe('#35 — la compuerta del documento pregunta por LOS TRES tipos, no por dos', () => {
  it('un DNI y un RUC se preguntan, con su tipo y su numero', () => {
    expect(rutaDelDocumento('/rentas/contribuyentes', 'DNI', '29614026')).toBe(
      '/rentas/contribuyentes?tipoDocumento=DNI&numeroDocumento=29614026',
    );
    expect(rutaDelDocumento('/rentas/contribuyentes', 'RUC', '20602546391')).toBe(
      '/rentas/contribuyentes?tipoDocumento=RUC&numeroDocumento=20602546391',
    );
  });

  it('y el carne de extranjeria TAMBIEN, que es lo que #35 desbloquea', () => {
    // Hasta #35 esto devolvia `null` y la compuerta decia «Sin comprobar en el padrón»: el
    // controlador publicaba `dNI` y `rUC` y ningun parametro para los demas tipos, asi que el
    // alta no tenia como saber si ese extranjero ya estaba — y dar de alta dos veces a la misma
    // persona parte su deuda en dos cuentas que nadie cruza.
    expect(rutaDelDocumento('/rentas/contribuyentes', 'Carnet de extranjería', '001234567890')).toBe(
      '/rentas/contribuyentes?tipoDocumento=CE&numeroDocumento=001234567890',
    );
  });

  it('los TRES tipos que la compuerta ofrece se pueden preguntar: 3 de 3, antes 2 de 3', () => {
    // Contado sobre la lista del artboard y no sobre tres literales: el dia que se anada un
    // cuarto tipo, esto dice si se puede preguntar o si hay que traducirlo primero.
    const preguntables = TIPOS_DE_DOCUMENTO.filter(
      (tipo) => rutaDelDocumento('/rentas/contribuyentes', tipo, '12345678') !== null,
    );

    expect(preguntables).toEqual([...TIPOS_DE_DOCUMENTO]);
  });

  it('y el tipo que el backend no conoce NO se manda: seria un 422 en la compuerta', () => {
    // El vocabulario de `?tipoDocumento=` es el del enumerado `TipoDocumento`, y «Carnet de
    // extranjería» no es una de sus seis palabras: por eso la traduccion se escribe.
    expect(rutaDelDocumento('/rentas/contribuyentes', 'Partida de nacimiento', '12345678')).toBeNull();
  });

  it('y sin numero tampoco se pregunta', () => {
    expect(rutaDelDocumento('/rentas/contribuyentes', 'DNI', '')).toBeNull();
  });
});
