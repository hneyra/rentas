import { describe, expect, it } from 'vitest';

import { totalesDelPredial } from '../datos/operaciones.ts';
import type { DeterminacionIndividual, EtapaDeLaCorrida } from '../datos/lecturas.ts';
import {
  conteo,
  cuadreDelPredial,
  filasDeLaCorrida,
  filasDelPredialIndividual,
} from './determinacion.ts';

/**
 * La composicion de la memoria, sin montar nada.
 *
 * <h2>Por que estas pruebas y no solo las de pantalla (AC4)</h2>
 *
 * Porque **un total copiado y un total derivado se ven igual**. La pantalla montada contra el
 * proxy dibuja 587.44 en los dos casos: el artboard cuadra consigo mismo, asi que copiar sus
 * dos totales daria exactamente la misma imagen. La unica manera de distinguirlos es llamar a
 * la funcion **con otros sumandos** y exigir que diga otra cosa — y eso solo se puede hacer si
 * la funcion es pura y esta expuesta.
 *
 * Lo mismo con los detalles: «2 predios, al 100 % y al 50 %» copiado y compuesto se leen igual
 * hasta que el contribuyente tiene tres.
 */

/** Una memoria de calculo con los valores que se le pasen, para poder cambiarlos de uno en uno. */
function memoriaCon(cambios: Partial<DeterminacionIndividual> = {}): DeterminacionIndividual {
  return {
    ejercicio: '2026',
    codContribuyente: '00000003541',
    sujeto: 'Castillo Pascuala, María Elena',
    conjunto: 'Conjunto 2026 sellado',
    fechaCalculo: '2026-08-12',
    predios: [
      {
        predioId: 4101,
        codigoPredial: '02-014-D-14-01',
        ubicacion: 'Calle Santa Rosa 116',
        uso: 'Casa habitación',
        porcentajePropiedad: '100.00',
        autovaluo: '132196.75',
      },
      {
        predioId: 4102,
        codigoPredial: '04-021-B-07-00',
        ubicacion: 'Mz. B Lt. 7 — Bellavista',
        uso: 'Terreno sin construir',
        porcentajePropiedad: '50.00',
        autovaluo: '38420.00',
      },
    ],
    valuoTotal: '170616.75',
    valuoExonerado: '0.00',
    valuoAfecto: '151406.75',
    uit: '5350.00',
    tramos: [
      { orden: 1, limiteSuperior: '80250.00', alicuota: '0.2', porcionGravada: '80250.00', aporte: '160.50' },
      { orden: 2, limiteSuperior: '321000.00', alicuota: '0.6', porcionGravada: '71156.75', aporte: '426.94' },
      { orden: 3, limiteSuperior: null, alicuota: '1.0', porcionGravada: '0.00', aporte: '0.00' },
    ],
    minimoImponible: '32.10',
    impuestoInsoluto: '587.44',
    derechoDeEmision: '4.50',
    totalAPagar: '591.94',
    modalidad: 'Fraccionada',
    cuotas: [1, 2, 3, 4].map((numero) => ({
      numero,
      vencimiento: '2026-02-27',
      importe: '147.98',
    })),
    reglasAplicadas: [
      'Escala progresiva acumulativa sobre el autovalúo de todos los predios del contribuyente en el distrito, con el mínimo imponible de 0.6 % de la UIT.',
      'Tramo 1 — hasta 15 UIT · 0.2 %',
      'Tramo 2 — de 15 a 60 UIT · 0.6 %',
      'Tramo 3 — más de 60 UIT · 1.0 %',
    ],
    ...cambios,
  };
}

/** El texto de la celda `columna` de la fila `clave`. */
function celda(memoria: DeterminacionIndividual, clave: string, columna: number): string | null {
  const fila = filasDelPredialIndividual(memoria).find((una) => una.clave === clave);
  if (fila === undefined) {
    throw new Error(`La memoria no trae la fila «${clave}».`);
  }
  const celdas = fila.celdas[columna];
  if (celdas === undefined) {
    throw new Error(`La fila «${clave}» no tiene columna ${String(columna)}.`);
  }
  return celdas.texto ?? celdas.importe?.importe ?? null;
}

describe('AC4 — los dos totales se DERIVAN de sus sumandos', () => {
  it('con los tramos del artboard dan lo que el artboard dice', () => {
    expect(totalesDelPredial(['160.50', '426.94', '0.00'], '4.50')).toEqual({
      impuestoInsoluto: '587.44',
      totalAPagar: '591.94',
    });
  });

  it('cambiar un tramo mueve el insoluto Y el total', () => {
    // ES LA PRUEBA QUE DISTINGUE. Una implementacion que copiara los dos totales de la memoria
    // del artboard pasaria la de arriba —dice lo mismo— y fallaria esta diciendo 587.44/591.94.
    expect(totalesDelPredial(['260.50', '426.94', '0.00'], '4.50')).toEqual({
      impuestoInsoluto: '687.44',
      totalAPagar: '691.94',
    });
  });

  it('cambiar el derecho de emision mueve el total y NO el insoluto', () => {
    expect(totalesDelPredial(['160.50', '426.94', '0.00'], '9.00')).toEqual({
      impuestoInsoluto: '587.44',
      totalAPagar: '596.44',
    });
  });

  it('un cuarto tramo entra en la suma sin tocar ninguna otra linea', () => {
    expect(totalesDelPredial(['160.50', '426.94', '0.00', '12.06'], '4.50').totalAPagar).toBe(
      '604.00',
    );
  });
});

describe('AC4 — la pantalla no suma: comprueba que lo publicado cuadre', () => {
  it('la memoria del artboard cuadra por los dos lados', () => {
    const cuadre = cuadreDelPredial(memoriaCon());
    expect(cuadre.sumaDeLosTramos).toBe('587.44');
    expect(cuadre.sumaDeLasPartidas).toBe('591.94');
    expect(cuadre.insolutoCuadra).toBe(true);
    expect(cuadre.totalCuadra).toBe(true);
  });

  it('un insoluto que no es la suma de sus tramos NO cuadra, y el total tampoco', () => {
    // Es el caso que la pantalla tiene que poder ver: tres tramos a la vista y un insoluto que
    // no es su suma. Sin esta comprobacion se dibujarian los cuatro numeros tan tranquilos.
    const cuadre = cuadreDelPredial(memoriaCon({ impuestoInsoluto: '600.00' }));
    expect(cuadre.insolutoCuadra).toBe(false);
    expect(cuadre.sumaDeLosTramos).toBe('587.44');
  });

  it('un total que no es el insoluto mas el derecho NO cuadra, y el insoluto si', () => {
    const cuadre = cuadreDelPredial(memoriaCon({ totalAPagar: '600.00' }));
    expect(cuadre.insolutoCuadra).toBe(true);
    expect(cuadre.totalCuadra).toBe(false);
    expect(cuadre.sumaDeLasPartidas).toBe('591.94');
  });

  it('«4.5» y «4.50» cuadran: se compara la cifra, no el texto', () => {
    // El backend puede publicar cualquiera de las dos escrituras. Comparando el texto, esta
    // memoria —que cuadra— saldria como descuadrada, y un falso rojo se acaba borrando.
    const cuadre = cuadreDelPredial(memoriaCon({ derechoDeEmision: '4.5' }));
    expect(cuadre.sumaDeLasPartidas).toBe('591.94');
    expect(cuadre.totalCuadra).toBe(true);
  });
});

describe('AC3 — la memoria trae las nueve filas de la escala', () => {
  it('el valuo, los tres tramos, el insoluto, el derecho y el total', () => {
    expect(filasDelPredialIndividual(memoriaCon()).map((fila) => fila.clave)).toEqual([
      'valuo-total',
      'valuo-exonerado',
      'valuo-afecto',
      'tramo-1',
      'tramo-2',
      'tramo-3',
      'insoluto',
      'derecho-de-emision',
      'total',
    ]);
  });

  it('cada tramo lleva su rotulo publicado y la porcion del afecto que grava', () => {
    const memoria = memoriaCon();
    expect(celda(memoria, 'tramo-1', 1)).toBe('Tramo 1 — hasta 15 UIT · 0.2 %');
    expect(celda(memoria, 'tramo-1', 2)).toBe('S/ 80,250.00 del afecto');
    expect(celda(memoria, 'tramo-2', 2)).toBe('S/ 71,156.75 del afecto');
    expect(celda(memoria, 'tramo-3', 3)).toBe('0.00');
  });

  it('los cuatro importes de la escala son los de la respuesta', () => {
    const memoria = memoriaCon();
    expect(celda(memoria, 'valuo-total', 3)).toBe('170616.75');
    expect(celda(memoria, 'valuo-afecto', 3)).toBe('151406.75');
    expect(celda(memoria, 'insoluto', 3)).toBe('587.44');
    expect(celda(memoria, 'total', 3)).toBe('591.94');
  });

  it('con dos tramos hay dos filas de tramo, y la memoria sigue teniendo ocho', () => {
    const memoria = memoriaCon({
      tramos: [
        { orden: 1, limiteSuperior: '80250.00', alicuota: '0.2', porcionGravada: '80250.00', aporte: '160.50' },
        { orden: 2, limiteSuperior: null, alicuota: '0.6', porcionGravada: '71156.75', aporte: '426.94' },
      ],
    });
    expect(filasDelPredialIndividual(memoria)).toHaveLength(8);
    expect(celda(memoria, 'insoluto', 2)).toBe('Suma de los dos tramos');
  });
});

describe('los detalles que hablan del dato se componen, y no se copian', () => {
  it('«2 predios, al 100 % y al 50 %» sale de los predios de la respuesta', () => {
    expect(celda(memoriaCon(), 'valuo-total', 2)).toBe('2 predios, al 100 % y al 50 %');
  });

  it('con un solo predio dice «1 predio», en singular', () => {
    const memoria = memoriaCon({
      predios: [
        {
          predioId: 4101,
          codigoPredial: '02-014-D-14-01',
          ubicacion: 'Calle Santa Rosa 116',
          uso: 'Casa habitación',
          porcentajePropiedad: '25.50',
          autovaluo: '132196.75',
        },
      ],
    });
    expect(celda(memoria, 'valuo-total', 2)).toBe('1 predio, al 25.5 %');
  });

  it('«Suma de los tres tramos» cuenta los tramos que hay', () => {
    expect(celda(memoriaCon(), 'insoluto', 2)).toBe('Suma de los tres tramos');
  });

  it('«Sin beneficio aplicado» solo se dice cuando el valuo exonerado es cero', () => {
    expect(celda(memoriaCon(), 'valuo-exonerado', 2)).toBe('Sin beneficio aplicado este ejercicio');
    expect(celda(memoriaCon({ valuoExonerado: '19210.00' }), 'valuo-exonerado', 2)).toBe(
      'Deducciones y exoneraciones del ejercicio',
    );
  });

  it('el pie del total cuenta las cuotas del cronograma servido', () => {
    expect(celda(memoriaCon(), 'total', 2)).toBe('En 4 cuotas de S/ 147.98');
    const enUna = memoriaCon({
      cuotas: [{ numero: 1, vencimiento: '2026-02-27', importe: '591.94' }],
    });
    expect(celda(enUna, 'total', 2)).toBe('En 1 cuota de S/ 591.94');
  });

  it('con cuotas desiguales no se inventa un importe unico', () => {
    const desiguales = memoriaCon({
      cuotas: [
        { numero: 1, vencimiento: '2026-02-27', importe: '148.00' },
        { numero: 2, vencimiento: '2026-05-29', importe: '147.98' },
      ],
    });
    expect(celda(desiguales, 'total', 2)).toBe('En 2 cuotas');
  });
});

describe('AC7 — cada importe de la memoria viaja con su fecha', () => {
  it('las nueve filas ponen la fecha de calculo de la respuesta en su celda de dinero', () => {
    const fechas = filasDelPredialIndividual(memoriaCon({ fechaCalculo: '2026-09-06' }))
      .flatMap((fila) => fila.celdas)
      .filter((una) => una.importe !== null)
      .map((una) => una.importe?.actualizadoA);

    expect(fechas.length).toBeGreaterThan(0);
    expect(new Set(fechas)).toEqual(new Set(['2026-09-06']));
  });
});

describe('AC5 — la corrida masiva ensena TODAS sus etapas', () => {
  const ETAPAS: readonly EtapaDeLaCorrida[] = [
    { etapa: 'Lectura del padrón', registros: 62418, monto: '', observados: 0, estado: 'Completa' },
    {
      etapa: 'Generación de cuponeras',
      registros: 61350,
      monto: '',
      observados: 534,
      estado: 'Con observados',
    },
  ];

  it('no se filtra ninguna, y la de «Con observados» conserva sus observados', () => {
    const filas = filasDeLaCorrida(ETAPAS, '2026-08-12');
    expect(filas).toHaveLength(2);
    expect(filas[1]?.celdas[3]?.texto).toBe('534');
    expect(filas[1]?.celdas[4]?.texto).toBe('Con observados');
  });

  it('la etapa que no mueve dinero no dibuja un cero: no dibuja nada', () => {
    // El backend publica la cadena vacia ahi, y «no se emitio nada» no es «esta etapa no
    // emite». Un cero en esa columna se leeria como una emision de cero soles.
    const celdaDelMonto = filasDeLaCorrida(ETAPAS, '2026-08-12')[0]?.celdas[2];
    expect(celdaDelMonto?.importe).toBeNull();
    expect(celdaDelMonto?.texto).toBeNull();
  });

  it('la etapa que si mueve dinero lo dibuja con la fecha de la corrida', () => {
    const conMonto: readonly EtapaDeLaCorrida[] = [
      {
        etapa: 'Determinación del impuesto',
        registros: 61884,
        monto: '9418204.60',
        observados: 534,
        estado: 'Completa',
      },
    ];
    expect(filasDeLaCorrida(conMonto, '2026-08-12')[0]?.celdas[2]?.importe).toEqual({
      importe: '9418204.60',
      actualizadoA: '2026-08-12',
    });
  });
});

describe('AC1 — el conteo se compone de la cifra servida, y pluraliza', () => {
  it('los seis del artboard salen de sus cifras', () => {
    expect(conteo(1, 'contribuyente')).toBe('1 contribuyente');
    expect(conteo(62418, 'cuenta')).toBe('62,418 cuentas');
    expect(conteo(4, 'servicio')).toBe('4 servicios');
    expect(conteo(3, 'ejercicio')).toBe('3 ejercicios');
    expect(conteo(1, 'transferencia')).toBe('1 transferencia');
    expect(conteo(84, 'evento')).toBe('84 eventos');
  });

  it('sin cifra no se escribe nada, y menos un cero', () => {
    // Un «0 cuentas» mientras la corrida no ha llegado diria que el padron esta vacio.
    expect(conteo(null, 'cuenta')).toBe('');
  });
});
