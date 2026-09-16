import type { ConstanciaDeNoAdeudo, DeudaConBeneficio, FichaUnificada } from '../lecturas.ts';

/**
 * **Las respuestas con que se prueban las dos hojas de Consultas** (#169).
 *
 * <h2>Que son, y que NO son</h2>
 *
 * **No son capturas de la instalacion**, al contrario que sus tres hermanos —`sesionMedida.ts`,
 * `seguridadMedida.ts` y `backendMedido.ts`, que son bytes de un `curl`—. Son respuestas
 * construidas **campo a campo desde el contrato** —`docs/50-api/formas-de-la-api.json`, que
 * genera `FormasDeLaApiTest` del tipo de retorno de cada controlador—, y de ahi sale su valor y
 * tambien su limite: sostienen que la interfaz lea los nombres que el backend declara, y **no**
 * que el backend conteste lo que declara. Eso se mide con la instalacion levantada, y cuando se
 * mida, estos tres se sustituyen por lo que conteste.
 *
 * <h2>Y por que viven en un archivo aparte, y no dentro de la prueba</h2>
 *
 * Porque las importan CUATRO pruebas —la del conector, la del registro, la del gancho y la del
 * recorrido de los cuarenta destinos—, y un `import` de un archivo `.test.ts` **vuelve a
 * registrar sus `describe`**: medido, los doce casos del conector se ejecutaban cuatro veces y un
 * solo rojo salia cuatro veces con cuatro nombres de archivo distintos.
 *
 * <h2>Solo lo importan las pruebas, y hay una guarda</h2>
 *
 * `verificaciones/camino-a-la-api.test.ts` lo vigila con la misma lista que a los otros tres, y
 * por el mismo motivo: un `ficha ?? FICHA` en produccion ensenaria la cuenta corriente de un
 * contribuyente inventado con la cara de un dato medido.
 */

/** Lo que `GET /consultas/unificada` publica de la cabecera y el resumen. */
export const FICHA: FichaUnificada = {
  contribuyente: { codigo: '00000025673', nombre: 'SULLON VILCHEZ-JOSE RAUL', documento: 'DNI 29614026' },
  aLaFecha: '2026-09-12',
  resumenDeSaldos: {
    insoluto: { importe: '3041.92', actualizadoA: '2026-09-12' },
    reajuste: { importe: '13.32', actualizadoA: '2026-09-12' },
    interes: { importe: '400.00', actualizadoA: '2026-09-12' },
    gasto: { importe: '108.00', actualizadoA: '2026-09-12' },
    total: { importe: '3563.24', actualizadoA: '2026-09-12' },
    estadoDeLaConsulta: 'Deuda a la fecha de consulta.',
  },
};

/** La misma ficha con OTRAS cifras: lo que demuestra que nada esta fijado. */
export const OTRA_FICHA: FichaUnificada = {
  contribuyente: { codigo: '00000003541', nombre: 'CASTILLO PASCUALA-MARIA ELENA', documento: 'DNI 44218937' },
  aLaFecha: '2026-01-31',
  resumenDeSaldos: {
    insoluto: { importe: '500.10', actualizadoA: '2026-01-31' },
    reajuste: { importe: '1.00', actualizadoA: '2026-01-31' },
    interes: { importe: '80.84', actualizadoA: '2026-01-31' },
    gasto: { importe: '10.00', actualizadoA: '2026-01-31' },
    total: { importe: '591.94', actualizadoA: '2026-01-31' },
    estadoDeLaConsulta: 'Deuda a la fecha de consulta.',
  },
};

/** Sin campana elegida, que es lo que contesta la operacion cuando nadie la elige. */
export const SIN_CAMPANIA: DeudaConBeneficio = {
  contribuyente: {
    codigo: '00000025673',
    nombre: 'SULLON VILCHEZ-JOSE RAUL',
    documento: 'DNI 29614026',
    domicilioFiscal: null,
  },
  aLaFecha: '2026-09-12',
  deudaTotal: { importe: '3563.24', actualizadoA: '2026-09-12' },
  deudaAcogida: { importe: '3563.24', actualizadoA: '2026-09-12' },
  registrosAcogidos: 2,
  simulacion: null,
  campaniasAplicables: [],
  estadoDeLaSimulacion:
    'No hay ninguna campaña de beneficio publicada para el ejercicio 2026: la deuda se muestra sin acogimiento.',
  obligaciones: { contenido: [], pagina: 0, tamano: 20, totalElementos: 0, totalPaginas: 0, hayMas: false },
};

/** La misma, con campana: es la rama que escribe «Beneficio vigente». */
export const CON_CAMPANIA: DeudaConBeneficio = {
  ...SIN_CAMPANIA,
  simulacion: {
    campania: 'Amnistia tributaria 2026',
    alicuotaAplicada: '50.00',
    baseDelBeneficio: 'INTERES_Y_REAJUSTE',
    baseDelBeneficioImporte: { importe: '413.32', actualizadoA: '2026-09-12' },
    ahorro: { importe: '206.66', actualizadoA: '2026-09-12' },
    deudaConBeneficio: { importe: '3356.58', actualizadoA: '2026-09-12' },
  },
  campaniasAplicables: [{ nombre: 'Amnistia tributaria 2026', alicuota: '50.00', base: 'INTERES_Y_REAJUSTE' }],
  estadoDeLaSimulacion: 'Acogimiento simulado a «Amnistia tributaria 2026».',
};

/** Una constancia que se NIEGA, con las dos obligaciones que lo impiden. */
export const CONSTANCIA_NEGADA: ConstanciaDeNoAdeudo = {
  codigoContribuyente: '00000025673',
  fechaDeCorte: '2026-09-12',
  seNiega: true,
  obligaciones: [
    {
      tributo: 'Impuesto predial',
      ejercicio: 2024,
      predioId: 41,
      vehiculoId: null,
      periodoDesde: 1,
      periodoHasta: 4,
      fase: 'Vencida',
      deuda: {
        insoluto: { importe: '1800.00', actualizadoA: '2026-09-12' },
        reajuste: { importe: '67.04', actualizadoA: '2026-09-12' },
        interes: { importe: '200.00', actualizadoA: '2026-09-12' },
        gasto: { importe: '0.00', actualizadoA: '2026-09-12' },
        total: { importe: '2067.04', actualizadoA: '2026-09-12' },
      },
    },
    {
      tributo: 'Patrimonio vehicular',
      ejercicio: 2024,
      predioId: null,
      vehiculoId: 7,
      periodoDesde: 1,
      periodoHasta: 1,
      fase: 'En coactiva',
      deuda: {
        insoluto: { importe: '800.00', actualizadoA: '2026-09-12' },
        reajuste: { importe: '2.44', actualizadoA: '2026-09-12' },
        interes: { importe: '40.00', actualizadoA: '2026-09-12' },
        gasto: { importe: '50.00', actualizadoA: '2026-09-12' },
        total: { importe: '892.44', actualizadoA: '2026-09-12' },
      },
    },
  ],
};

/** Y una que SI procede: lista vacia, que no es una averia sino el dato. */
export const CONSTANCIA_QUE_PROCEDE: ConstanciaDeNoAdeudo = {
  codigoContribuyente: '00000003541',
  fechaDeCorte: '2026-01-31',
  seNiega: false,
  obligaciones: [],
};
