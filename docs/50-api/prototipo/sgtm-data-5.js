/* SGTM — catálogo de pantallas (complemento 2): tesorería, consultas,
   cobranza coactiva, autorizaciones y licencias, seguridad.
   Campos tomados del Manual de Usuario SGTM (figuras 138-231). */
(function () {
  var T = function (label, o) { return Object.assign({ label: label, t: 'text', v: '', ph: '', opts: null, wide: 0, on: false }, o || {}); };
  var S = function (label, fields, hint) { return { label: label, fields: fields, hint: hint || '' }; };
  var W = window.SGTM_SCREENS = window.SGTM_SCREENS || {};
  var yrs = ['2026', '2025', '2024', '2023', '2022', '2021', '2020'];
  var tipoDeuda = ['TRIBUTARIA', 'P. TRÁNSITO', 'P. ADMINISTRATIVA', 'CLAUSURA DE LOCAL'];
  var tributos = ['(TODOS)', '00001 — PREDIAL', '00003 — VEHICULAR', '00007 — LIMPIEZA PÚBLICA', '00008 — PARQUES Y JARDINES', '00026 — SERENAZGO', '00101 — COSTAS PROCESALES'];

  Object.assign(W, {

    /* ── TESORERÍA ────────────────────────────────────────── */

    anulacion_convenio: {
      mod: 'Tesorería', title: 'Anulación de convenio',
      endpoint: 'POST /api/v1/tesoreria/convenios/{numero}/anulacion',
      desc: 'Anula, reforma o quiebra un convenio de fraccionamiento. La deuda acogida retorna a su estado original y el sistema conserva el motivo y el responsable de la anulación.',
      filters: [T('Num. Conv.', { v: '0000000643' }), T('Contribuyente', { v: '' }), T('Estado del convenio', { t: 'sel', v: 'Todos', opts: ['Todos', 'NORMAL', 'ANULADO', 'QUEBRADO', 'REFORMADO', 'CANCELADO'] }), T('Fecha de anulación', { t: 'date', v: '2026-08-13' })],
      table: {
        title: 'Anulaciones registradas', count: '3 registros',
        cols: ['Num. Anul.', 'Num. Conv.', 'Fecha Anul.', 'Contribuyente', 'Motivo', 'Responsable'], num: [],
        rows: [
          ['000016', '0000000643', '13/08/2026', 'SANTIAGO MOSCOL-GASPAR', 'PAGOS NO REALIZADOS', 'JC'],
          ['000015', '0000000618', '04/08/2026', 'CASTILLO PASCUALA, MARÍA E.', 'SOLICITUD DEL CONTRIBUYENTE', 'VRETO'],
          ['000014', '0000000602', '22/07/2026', 'INVERSIONES DEL NORTE SAC', 'QUIEBRA POR INCUMPLIMIENTO', 'MRIOS']
        ]
      },
      sections: [
        S('Detalle de la anulación', [
          T('Num. Anul.', { t: 'ro', v: '000016' }),
          T('Fecha Anul.', { t: 'date', v: '2026-08-13' }),
          T('Responsable Anul.', { t: 'ro', v: 'JC' }),
          T('Num. Conv.', { v: '0000000643' }),
          T('Estado del convenio', { t: 'ro', v: 'NORMAL' }),
          T('Contribuyente', { t: 'ro', v: '00000003542 — SANTIAGO MOSCOL-GASPAR', wide: 1 }),
          T('Motivo', { v: 'PAGOS NO REALIZADOS', wide: 1 }),
          T('Glosa', { t: 'area', v: 'PAGOS NO REALIZADOS', wide: 1 })
        ])
      ],
      actions: ['Nuevo', 'Modificar', 'Guardar', 'Deshacer', 'Anular', 'Reformar', 'Quebrar']
    },

    /* ── CONSULTAS ────────────────────────────────────────── */

    consulta_altas_bajas: {
      mod: 'Consultas', title: 'Consulta de altas y bajas',
      endpoint: 'GET /api/v1/consultas/altas-bajas',
      desc: 'Movimientos de alta y baja de deuda de un contribuyente, automáticos o manuales, con el documento que los aprueba y el detalle de las deudas afectadas.',
      filters: [T('Tipo de consulta', { t: 'sel', v: 'TRIBUTARIA', opts: ['TRIBUTARIA', 'P. TRÁNSITO', 'P. ADMINISTRATIVA'] }), T('Código Cont.', { v: '00000003542' }), T('Alta / Baja', { t: 'sel', v: '(TODAS)', opts: ['(TODAS)', 'A — ALTA', 'B — BAJA'] }), T('Auto / Manual', { t: 'sel', v: '(TODAS)', opts: ['(TODAS)', 'A — AUTOMÁTICA', 'M — MANUAL'] })],
      sections: [
        S('Contribuyente', [
          T('Nombre', { t: 'ro', v: 'SANTIAGO MOSCOL-GASPAR', wide: 1 }),
          T('Domicilio fiscal', { t: 'ro', v: 'SANTO TORIBIO 17', wide: 1 }),
          T('Desde', { t: 'date', v: '2026-07-13' }), T('Hasta', { t: 'date', v: '2026-08-13' }),
          T('Unidad', { v: '' }), T('Nº Docum.', { v: '' }), T('Nº HR', { v: '' })
        ])
      ],
      tabs: [
        { label: 'Altas y Bajas', sections: [
          S('Observaciones del movimiento', [
            T('Observación', { t: 'area', wide: 1, v: 'BAJA AUTOMÁTICA: POR NO CORRESPONDER DEUDA, DEUDA HA SIDO CANCELADA — PARQUES Y JARDINES — UNIDAD: 20' })
          ], 'Solo lectura')
        ] },
        { label: 'Detalle de Deudas', sections: [
          S('Filtros del detalle', [
            T('Año', { v: '' }), T('Cuota', { v: '' }),
            T('Tributo', { t: 'sel', v: '(TODOS)', opts: tributos }),
            T('Fase', { v: '' }), T('Conc.', { v: '' }), T('Cod. Unid.', { v: '' }),
            T('Tipo A/B', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'A — ALTA', 'B — BAJA'] })
          ])
        ] }
      ],
      table: {
        title: 'Relación de altas y bajas', count: '9 de 17 movimientos',
        cols: ['Num. Docum.', 'A/B', 'A/M', 'Cod. Municipal', 'Doc. Aprob.', 'Fec. Doc. Aprob.', 'Fecha Reg.', 'Est.'], num: [],
        rows: [
          ['000000694727', ['A', 'ok'], 'A', '00000003542', 'ALTA AUTOMÁTICA', '15/08/2026', '15/08/2026', ['A', 'ok']],
          ['000000694726', ['B', 'bad'], 'A', '00000003542', 'BAJA AUTOMÁTICA', '15/08/2026', '15/08/2026', ['A', 'ok']],
          ['000000694725', ['A', 'ok'], 'A', '00000003542', 'ALTA AUTOMÁTICA', '15/08/2026', '15/08/2026', ['A', 'ok']],
          ['000000694724', ['B', 'bad'], 'A', '00000003542', 'BAJA AUTOMÁTICA', '15/08/2026', '15/08/2026', ['A', 'ok']],
          ['000000694723', ['B', 'bad'], 'A', '00000003542', 'BAJA AUTOMÁTICA', '15/08/2026', '15/08/2026', ['A', 'ok']],
          ['000000694722', ['B', 'bad'], 'A', '00000003542', 'BAJA AUTOMÁTICA', '15/08/2026', '15/08/2026', ['A', 'ok']],
          ['000000694721', ['A', 'ok'], 'A', '00000003542', 'ALTA AUTOMÁTICA', '15/08/2026', '15/08/2026', ['A', 'ok']],
          ['000000694720', ['B', 'bad'], 'A', '00000003542', 'BAJA AUTOMÁTICA', '15/08/2026', '15/08/2026', ['A', 'ok']],
          ['000000694719', ['A', 'ok'], 'A', '00000003542', 'ALTA AUTOMÁTICA: REC 01 — TRIBUTARIA', '13/08/2026', '13/08/2026', ['A', 'ok']]
        ]
      },
      actions: ['Buscar', 'Imprimir', 'Excel']
    },

    consulta_unificada: {
      mod: 'Consultas', title: 'Consulta unificada predial-arbitrios',
      endpoint: 'GET /api/v1/consultas/unificada?contribuyente={codigo}',
      desc: 'Vista única del contribuyente: impuesto anual por ejercicio, impuesto por predio y, en pestañas, deudas, pagos, altas y bajas, movimientos del predio, fraccionamientos y valores emitidos.',
      filters: [T('Contribuyente', { v: '00000003542' }), T('Impresión', { t: 'sel', v: 'PREDIAL Y ARBITRIOS', opts: ['PREDIAL', 'ARBITRIOS', 'PREDIAL Y ARBITRIOS'] })],
      sections: [
        S('Datos del contribuyente', [
          T('Nombre', { t: 'ro', v: 'SANTIAGO MOSCOL-GASPAR', wide: 1 }),
          T('Domicilio fiscal', { t: 'ro', v: 'A.H. CUATRO DE NOVIEMBRE — CA. SANTO TORIBIO 17', wide: 1 })
        ])
      ],
      table: {
        title: 'Impuesto anual', count: '3 ejercicios',
        cols: ['Año', 'Numero HR', 'NumCálculo', 'Dirección', 'NumPredios', 'Valúo afecto', 'Valúo exonerado', 'Valúo total', 'Impto. predial', 'Limp. pública', 'Parq. y jardines', 'Rell. sanitario', 'Serenazgo'], num: [4, 5, 6, 7, 8, 9, 10, 11, 12],
        rows: [
          ['2026', '0000098252', '007', 'A.H. CUATRO', '1', '15,821.60', '0.00', '15,821.60', '31.64', '84.78', '25.20', '0.00', '37.08'],
          ['2025', '0000005821', '001', 'A.H. CUATRO', '1', '26,320.00', '0.00', '26,320.00', '52.80', '35.58', '0.00', '0.00', '60.00'],
          ['2024', '0000005579', '001', 'A.H. CUATRO', '1', '24,219.20', '0.00', '24,219.20', '48.40', '35.58', '0.00', '0.00', '60.00']
        ]
      },
      tabs: [
        { label: 'Resumen de Deudas', sections: [
          S('Resumen de saldos', [
            T('Insoluto', { t: 'ro', v: '186.48' }), T('Reajuste', { t: 'ro', v: '0.00' }),
            T('Interés', { t: 'ro', v: '0.00' }), T('Gasto', { t: 'ro', v: '92.55' }),
            T('Total', { t: 'ro', v: '279.03' }),
            T('Estado de la consulta', { t: 'ro', v: 'CONSULTA FINALIZADA', wide: 1 })
          ], 'Solo lectura')
        ] },
        { label: 'Deudas Pendientes', sections: [
          S('Filtros de deuda', [
            T('Año', { v: '' }), T('Cuota', { v: '' }),
            T('Tributo', { t: 'sel', v: '(TODOS)', opts: tributos }),
            T('Fase', { v: '' }), T('Conc.', { v: '' }), T('Cod. Unid.', { v: '' })
          ])
        ] },
        { label: 'Pagos Realizados', sections: [
          S('Criterios', [
            T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }),
            T('Nº de recibo', { v: '' }), T('Caja', { t: 'sel', v: 'Todas', opts: ['Todas', 'CAJA 01', 'CAJA 02', 'CAJA 03'] })
          ])
        ] },
        { label: 'Altas y Bajas', sections: [
          S('Criterios', [
            T('Alta / Baja', { t: 'sel', v: '(TODAS)', opts: ['(TODAS)', 'A — ALTA', 'B — BAJA'] }),
            T('Auto / Manual', { t: 'sel', v: '(TODAS)', opts: ['(TODAS)', 'A — AUTOMÁTICA', 'M — MANUAL'] }),
            T('Nº Docum.', { v: '' }), T('Unidad', { v: '' })
          ])
        ] },
        { label: 'Movimientos del Predio', sections: [
          S('Criterios', [
            T('Cod. Ref. Catastral', { v: '20060100567032' }),
            T('Tipo de movimiento', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'ALTA', 'TRANSFERENCIA', 'MODIFICACIÓN', 'BAJA'] }),
            T('Desde', { t: 'date', v: '' }), T('Hasta', { t: 'date', v: '' })
          ])
        ] },
        { label: 'Fraccionamientos', sections: [
          S('Criterios', [
            T('Nº de convenio', { v: '' }),
            T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'NORMAL', 'CANCELADO', 'ANULADO', 'QUEBRADO'] })
          ])
        ] },
        { label: 'Valores', sections: [
          S('Criterios', [
            T('Tipo de valor', { t: 'sel', v: 'Todos', opts: ['Todos', 'ORDEN DE PAGO', 'RES. DETERMINACIÓN', 'RES. DE MULTA', 'RES. EJE. COACTIVA'] }),
            T('Año', { t: 'sel', v: 'Todos', opts: ['Todos'].concat(yrs) })
          ])
        ] }
      ],
      actions: ['Buscar', 'Imprimir', 'Impuestos calculados por predio']
    },

    consulta_deudas_beneficio: {
      mod: 'Consultas', title: 'Consulta de deudas con beneficio',
      endpoint: 'GET /api/v1/consultas/deudas-con-beneficio',
      desc: 'Simula el acogimiento de la deuda a un beneficio vigente: muestra la deuda total, la deuda acogida y la deuda con beneficio, con la tasa aplicada y el ahorro resultante.',
      filters: [T('Tipo de papeleta', { t: 'sel', v: 'TRIBUTARIA', opts: ['TRIBUTARIA', 'P. TRÁNSITO', 'P. ADMINISTRATIVA'] }), T('Contribuyente', { v: '00000003542' }), T('Forma de pago', { t: 'sel', v: 'CONTADO TOTAL', opts: ['PRECONVENIO', 'CONTADO TOTAL'] }), T('Benef. aplicable', { t: 'sel', v: 'CONTADO TRIBUTARIO PERM', opts: ['CONTADO TRIBUTARIO PERM', 'AMNISTÍA ORDENANZA 018-2026', 'PRONTO PAGO ANUAL', 'CONVENIO PERMANENTE'] })],
      sections: [
        S('Búsqueda', [
          T('Papeleta', { v: '' }), T('Placa', { v: '' }),
          T('Contribuyente', { t: 'ro', v: 'SANTIAGO MOSCOL-GASPAR', wide: 1 }),
          T('Domicilio fiscal', { t: 'ro', v: 'A.H. CUATRO DE NOVIEMBRE — CA. SANTO TORIBIO 17', wide: 1 }),
          T('Incluir inquilinos', { t: 'chk', on: false }),
          T('Excluir deudas prescritas', { t: 'chk', on: false }),
          T('Fecha de consulta', { t: 'date', v: '2026-08-13' })
        ]),
        S('Filtros de deuda', [
          T('Año', { v: '' }), T('Cuota', { v: '' }),
          T('Tributo', { t: 'sel', v: '(TODOS)', opts: tributos }),
          T('Fase', { v: '' }), T('Conc.', { v: '' }), T('Cod. Unid.', { v: '' }),
          T('CodConv', { v: '' }), T('Recau', { v: '' }), T('Coac', { v: '' })
        ]),
        S('Resultado del acogimiento', [
          T('Deuda total (S/)', { t: 'ro', v: '1,848.66' }),
          T('Deuda acogida (S/)', { t: 'ro', v: '797.77' }),
          T('Deuda con beneficio (S/)', { t: 'ro', v: '250.15' }),
          T('Tasa aplicada (%)', { t: 'ro', v: '68.64' }),
          T('Beneficio (S/)', { t: 'ro', v: '547.62' }),
          T('Registros acogidos', { t: 'ro', v: '36 de 128' }),
          T('Impresión', { t: 'sel', v: 'BENEFICIO', opts: ['CONSOLIDADO', 'DETALLADO', 'BENEFICIO'] }),
          T('Impresora matricial', { t: 'chk', on: false })
        ])
      ],
      table: {
        title: 'Deudas seleccionables', count: '12 de 128 · acogidas 36',
        note: 'Deuda total 1,848.66 · acogida 797.77 · con beneficio 250.15',
        cols: ['Año', 'Unidad', 'Convenio', 'Cuota', 'Trib.', 'Nom. Trib.', 'Fase', 'Conc.', 'Est.', 'Insoluto', 'Reajuste', 'Interés', 'Gastos', 'Total'], num: [9, 10, 11, 12, 13],
        rows: [
          ['2019', '—', '—', '001', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.22', '0.00', '36.85', '7.80', '48.87'],
          ['2019', '—', '—', '002', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.22', '0.08', '35.04', '0.00', '39.34'],
          ['2019', '—', '—', '003', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.22', '0.15', '33.19', '0.00', '37.56'],
          ['2019', '—', '—', '004', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.21', '0.17', '31.04', '0.00', '35.42'],
          ['2020', '—', '—', '001', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.22', '0.00', '28.17', '8.40', '40.79'],
          ['2020', '—', '—', '002', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.22', '0.06', '26.63', '0.00', '30.91'],
          ['2020', '—', '—', '003', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.22', '0.09', '25.00', '0.00', '29.31'],
          ['2020', '—', '—', '004', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.21', '0.16', '23.43', '0.00', '27.80'],
          ['2021', '—', '—', '001', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.36', '0.00', '22.04', '8.70', '35.10'],
          ['2021', '—', '—', '002', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.36', '0.04', '20.71', '0.00', '25.11'],
          ['2021', '—', '—', '003', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.36', '0.08', '19.33', '0.00', '23.77'],
          ['2022', '—', '—', '001', '00001', 'PREDIAL-REG-E', '014', '081', ['P', 'warn'], '4.50', '0.00', '17.09', '9.00', '30.59']
        ]
      },
      actions: ['Filtrar', 'Limpiar', 'Imprimir', 'Bajar deuda']
    },

    consulta_resumen_predial: {
      mod: 'Consultas', title: 'Consulta resumen predial-arbitrios',
      endpoint: 'GET /api/v1/consultas/resumen-predial',
      desc: 'Resumen por predio: impuesto predial de cada ejercicio con su valúo afecto y el saldo de deuda, más el valúo de arbitrios y los movimientos del predio.',
      filters: [T('Cod. Catastral', { v: '' }), T('Cod. Contribuyente', { v: '00000003542' }), T('Uso', { t: 'sel', v: 'Todos', opts: ['Todos', 'CASA HABITACIÓN', 'COMERCIO', 'INDUSTRIA', 'TERRENO SIN CONSTRUIR', 'SERVICIOS'] }), T('Palabra', { v: '' })],
      sections: [
        S('Búsqueda del predio', [
          T('Nombre del lugar', { v: '' }), T('Calle', { v: '' }),
          T('Nro. Ficha', { v: '' }), T('Número', { v: '' }), T('Interior', { v: '' }), T('Num. Adic.', { v: '' }),
          T('Mz.', { v: '' }), T('Lt.', { v: '' }), T('St.', { v: '' })
        ])
      ],
      table: {
        title: 'Predios encontrados', count: '1 predio',
        cols: ['Cod. Catastral', 'Cod. Propietario', 'Nombre del propietario', 'Dirección del predio'], num: [],
        rows: [
          ['200601005670320A01...', '00000003542', 'SANTIAGO MOSCOL-GASPAR', 'A.H. CUATRO DE NOVIEMBRE — SANTO TORIBIO 17']
        ]
      },
      tabs: [
        { label: 'Impuesto Predial', sections: [
          S('Determinación por ejercicio', [
            T('Total deuda predial — insoluto (S/)', { t: 'ro', v: '319.32' }),
            T('Reajuste (S/)', { t: 'ro', v: '0.00' }),
            T('Interés (S/)', { t: 'ro', v: '0.00' }),
            T('Gasto (S/)', { t: 'ro', v: '141.50' }),
            T('Total (S/)', { t: 'ro', v: '460.82' })
          ], 'Solo lectura')
        ] },
        { label: 'Valúo Predial / Arbitrios', sections: [
          S('Valúo y arbitrios por ejercicio', [
            T('Ejercicio', { t: 'sel', v: '2026', opts: yrs }),
            T('Valúo afecto (S/)', { t: 'ro', v: '15,821.60' }),
            T('Limpieza pública (S/)', { t: 'ro', v: '84.78' }),
            T('Parques y jardines (S/)', { t: 'ro', v: '25.20' }),
            T('Serenazgo (S/)', { t: 'ro', v: '37.08' }),
            T('Relleno sanitario (S/)', { t: 'ro', v: '0.00' })
          ], 'Solo lectura')
        ] },
        { label: 'Movimientos del Predio', sections: [
          S('Criterios', [
            T('Tipo de movimiento', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'ALTA', 'TRANSFERENCIA', 'MODIFICACIÓN', 'BAJA'] }),
            T('Desde', { t: 'date', v: '' }), T('Hasta', { t: 'date', v: '' })
          ])
        ] }
      ],
      actions: ['Buscar', 'Limpiar', 'Detalle de deudas', 'Actualizar deuda']
    },

    resolucion_determinacion_fisc: {
      mod: 'Consultas', title: 'Resolución de determinación de fiscalización', kind: 'report',
      endpoint: 'GET /api/v1/fiscalizacion/resoluciones/{numero}',
      desc: 'Valor emitido al cierre de un procedimiento de fiscalización: determina la diferencia de tributo por ejercicio y la multa tributaria que corresponde.',
      report: {
        code: 'RD-2026-000418', date: '13 de agosto de 2026',
        title: 'Resolución de determinación',
        subtitle: 'Procedimiento de fiscalización tributaria — impuesto predial y arbitrios',
        meta: [
          { k: 'Nº de resolución', v: '000418-2026-SGFT/MPS' },
          { k: 'Contribuyente', v: 'INVERSIONES DEL NORTE SAC' },
          { k: 'R.U.C.', v: '20525118447' },
          { k: 'Predio', v: '02-014-D-14-01 — AV. JOSÉ DE LAMA 1180' },
          { k: 'Periodo fiscalizado', v: '2021 — 2026' },
          { k: 'Tipo de fiscalización', v: 'CIERTA' }
        ],
        cols: ['Ejercicio', 'Determinado S/', 'Declarado S/', 'Diferencia S/', 'Interés S/', 'Total S/'], num: [1, 2, 3, 4, 5],
        rows: [
          ['2021', '3,182.00', '1,120.00', '2,062.00', '618.60', '2,680.60'],
          ['2022', '3,410.00', '1,180.00', '2,230.00', '556.00', '2,786.00'],
          ['2023', '3,618.00', '1,240.00', '2,378.00', '441.00', '2,819.00'],
          ['2024', '3,880.00', '1,310.00', '2,570.00', '318.00', '2,888.00'],
          ['2025', '4,120.00', '1,380.00', '2,740.00', '182.00', '2,922.00'],
          ['2026', '4,412.00', '1,440.00', '2,972.00', '48.00', '3,020.00']
        ],
        footer: 'Contra la presente resolución procede recurso de reclamación dentro de los veinte días hábiles siguientes a su notificación, conforme al artículo 137 del Código Tributario. Vencido el plazo sin pago ni reclamación, la deuda queda firme y exigible coactivamente.'
      }
    },

    /* ── VALORES Y COACTIVA ───────────────────────────────── */

    pase_coactiva: {
      mod: 'Coactiva', title: 'Pase de valores a coactiva',
      endpoint: 'POST /api/v1/valores/{numero}/movimientos',
      desc: 'Registra el movimiento del valor hacia el área de cobranza coactiva: PCO — pase a coactivas, ACO — aceptado en coactivas o RCO — rechazado en coactivas.',
      filters: [T('Contrib.', { v: '00000329592' }), T('Tipo de valor', { t: 'sel', v: 'Todos', opts: ['Todos', 'RDP — RES. DETERMINACIÓN PREDIAL', 'RMLF — RM LICENCIA FUNCIONAMIENTO', 'REC — RES. EJE. COACTIVA', 'OP — ORDEN DE PAGO'] }), T('Tipo Mov.', { t: 'sel', v: 'Todos', opts: ['Todos', 'PCO — PASE A COACTIVAS', 'ACO — ACEPTADO EN COACTIVAS', 'RCO — RECHAZADO EN COACTIVAS'] }), T('Nro. Valor', { v: '' })],
      sections: [
        S('Búsqueda', [
          T('Emitido desde', { t: 'date', v: '2026-07-22' }), T('Emitido hasta', { t: 'date', v: '2026-08-13' }),
          T('Unidad / Placa', { v: '' }), T('Papeleta Nº', { v: '' })
        ]),
        S('Detalle de los movimientos', [
          T('Tipo de operación', { t: 'sel', v: 'INDIVIDUAL', opts: ['INDIVIDUAL', 'MASIVA'] }),
          T('Num. Recaudo', { t: 'ro', v: '0000000003' }),
          T('Año Deuda', { v: '1996' }),
          T('Fecha de emisión', { t: 'date', v: '1997-03-31' }),
          T('Tipo de recaudo', { t: 'ro', v: '081 — RM LICENCIA FUNCIONAMIENTO' }),
          T('Contribuyente', { t: 'ro', v: '00000329592 — MOLINO SULLANA — LICENCIA', wide: 1 }),
          T('Nro. Mov.', { v: '1' }), T('Fecha del movimiento', { t: 'date', v: '1997-05-19' }),
          T('Tipo de movimiento', { t: 'sel', v: 'PCO — PASE A COACTIVAS', opts: ['PCO — PASE A COACTIVAS', 'ACO — ACEPTADO EN COACTIVAS', 'RCO — RECHAZADO EN COACTIVAS'] }),
          T('Observación', { v: 'PASE A COACTIVAS', wide: 1 })
        ])
      ],
      table: {
        title: 'Valores por pasar a coactiva', count: '4 valores',
        cols: ['Recaudo', 'Año Rec.', 'Tipo', 'Cod. Contrib.', 'Nombre', 'Año Deu.', 'Vence', 'Coac', 'Mov', 'Est.'], num: [],
        rows: [
          ['0000000002', '2026', 'RDP', '00000009723', 'GARCÍA VALDIVIEZO-HILDEFREDO', '2021', '12/08/2026', '—', '—', ['N', 'warn']],
          ['0000000003', '1997', 'RMLF', '00000329592', 'MOLINO SULLANA — LICENCIA', '1996', '01/01/1900', 'S', '1', ['N', 'warn']],
          ['0000000003', '2024', 'REC', '00000002368', 'SUC. ALBERTO PANTA GONZALES', '2021', '—', 'S', '—', ['N', 'warn']],
          ['0000000003', '2026', 'REC', '00000072348', 'MADERERA ROLANDO CISNEROS GONZA...', '2023', '—', 'S', '—', ['N', 'warn']]
        ]
      },
      actions: ['Nuevo', 'Modificar', 'Generar', 'Inactivar', 'Imprimir']
    },

    coactiva_deudas_beneficio: {
      mod: 'Coactiva', title: 'Consulta de deudas en beneficio (coactiva)',
      endpoint: 'GET /api/v1/coactiva/deudas-en-beneficio',
      desc: 'Deuda en cobranza coactiva acogible a un beneficio vigente, con las costas procesales incorporadas al cálculo.',
      filters: [T('Tipo de deuda', { t: 'sel', v: 'TRIBUTARIA', opts: tipoDeuda }), T('Contribuyente', { v: '00000003542' }), T('Benef. aplicable', { t: 'sel', v: 'AMNISTÍA COACTIVA 2026', opts: ['AMNISTÍA COACTIVA 2026', 'CONTADO COACTIVO PERM', 'FRACCIONAMIENTO COACTIVO'] }), T('Fecha de cálculo', { t: 'date', v: '2026-08-13' })],
      table: {
        title: 'Deuda acogible en coactiva', count: '5 expedientes · S/ 6,412.80',
        note: 'Deuda total 6,412.80 · acogida 4,180.00 · con beneficio 2,090.00 · costas 412.50',
        cols: ['Expediente', 'Año', 'Tributo', 'Insoluto S/', 'Interés S/', 'Costas S/', 'Total S/', 'Con beneficio S/'], num: [3, 4, 5, 6, 7],
        rows: [
          ['2026-0001201', '2021', 'PREDIAL', '418.00', '182.40', '17.75', '618.15', '309.08'],
          ['2026-0001248', '2022', 'ARBITRIOS', '882.00', '312.80', '17.75', '1,212.55', '606.28'],
          ['2026-0001302', '2023', 'PREDIAL', '1,104.00', '284.10', '17.75', '1,405.85', '702.93'],
          ['2026-0001344', '2024', 'VEHICULAR', '940.64', '188.12', '17.75', '1,146.51', '573.26'],
          ['2026-0001388', '2025', 'ARBITRIOS', '1,682.00', '324.00', '17.75', '2,023.75', '1,011.88']
        ]
      },
      actions: ['Filtrar', 'Imprimir', 'Generar convenio coactivo']
    },

    coactiva_consulta_deudas: {
      mod: 'Coactiva', title: 'Consulta de deudas en coactiva',
      endpoint: 'GET /api/v1/coactiva/deudas',
      desc: 'Deuda en cobranza coactiva por contribuyente y expediente, con su estado procesal y la última actuación registrada.',
      filters: [T('Tipo de deuda', { t: 'sel', v: 'TRIBUTARIA', opts: tipoDeuda }), T('Contribuyente', { v: '' }), T('Nº Expediente', { v: '' }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'REC 01 EMITIDO', 'NOTIFICADO', 'MEDIDA CAUTELAR', 'FRACCIONADO', 'SUSPENDIDO', 'CONCLUIDO'] })],
      table: {
        title: 'Deudas en cobranza coactiva', count: '5 de 1,842 expedientes',
        cols: ['Expediente', 'Año', 'Contribuyente', 'Tributo', 'Deuda S/', 'Costas S/', 'Última actuación', 'Estado'], num: [4, 5],
        rows: [
          ['0000001201', '2026', 'SANTIAGO MOSCOL-GASPAR', 'PREDIAL, SERENAZGO', '279.03', '17.75', 'REC 01 notificada 21/05/2026', ['REC 01 emitido', 'warn']],
          ['0000000907', '2026', 'SANTIAGO MOSCOL-GASPAR', 'PREDIAL', '186.48', '17.75', 'Importación fiscalización', ['REC 01 emitido', 'warn']],
          ['0000005687', '2026', 'INFANTE CÁRCELEN RAÚL', 'PREDIAL, SERENAZGO', '344.68', '35.50', 'Embargo Nº 500 — 10/03/2026', ['Medida cautelar', 'bad']],
          ['0000003852', '2025', 'SUC. TOMÁS MAZA GÓMEZ', 'PREDIAL', '333.58', '17.75', 'Notificación de REC', ['Notificado', 'warn']],
          ['0000004841', '2025', 'CALDERÓN ESLAVA-JUAN ALBERTO', 'ARBITRIOS', '1,204.00', '17.75', 'Convenio coactivo 0000000643', ['Fraccionado', 'ok']]
        ]
      },
      actions: ['Buscar', 'Imprimir', 'Excel']
    },

    costas_procesales: {
      mod: 'Coactiva', title: 'Liquidación de costas procesales',
      endpoint: 'POST /api/v1/coactiva/liquidaciones-costas',
      desc: 'Liquida las costas y gastos del procedimiento coactivo por expediente, según el arancel de costas aprobado.',
      filters: [T('Nro. Liquidación', { v: '1000000004' }), T('Nro. Exped. Coact.', { v: '' }), T('Contribuyente', { v: '' }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'A — ACTIVA', 'N — NOTIFICADA', 'C — CANCELADA', 'X — ANULADA'] })],
      table: {
        title: 'Liquidaciones encontradas', count: '3 de 17 registros',
        cols: ['Nro. Liquidación', 'Cod. Contrib.', 'Fecha', 'Exped. Coact.', 'Observación', 'Estado'], num: [],
        rows: [
          ['1000000001', '00000015342', '28/05/2026', '0000000538', 'CASA 2 PISOS', ['A', 'ok']],
          ['1000000004', '00000019535', '14/06/2026', '0000001096', 'REC (01) NOTIFICADA EL 21/05/26', ['N', 'warn']],
          ['1000000005', '00000035180', '15/06/2026', '0000004841', 'REC (01), NOTIFICADA EL 02/06/26', ['N', 'warn']]
        ]
      },
      sections: [
        S('Detalle de liquidación', [
          T('Nro. Liquidación', { t: 'ro', v: '1000000004' }),
          T('Fecha', { t: 'date', v: '2026-06-14' }),
          T('Nro. Exped. Coact.', { t: 'ro', v: '0000001096' }),
          T('Contribuyente', { t: 'ro', v: '00000019535 — CALDERÓN ESLAVA-JUAN ALBERTO', wide: 1 }),
          T('Domicilio fiscal', { t: 'ro', v: 'UNIÓN 273', wide: 1 }),
          T('Observaciones', { t: 'area', v: 'REC (01) NOTIFICADA EL 21/05/26.-', wide: 1 })
        ]),
        S('Costas procesales', [
          T('Tributo', { t: 'sel', v: '00101 — COSTAS PROCESALES', opts: ['00101 — COSTAS PROCESALES', '00102 — GASTOS DE EJECUCIÓN'] }),
          T('Descripción', { t: 'ro', v: 'AUTO DE EJECUCIÓN COACTIVA' }),
          T('Monto (S/)', { v: '17.75' }),
          T('Total (S/)', { t: 'ro', v: '17.75' })
        ])
      ],
      actions: ['Nuevo', 'Modificar', 'Guardar', 'Anular', 'Imprimir']
    },

    importacion_valores: {
      mod: 'Coactiva', title: 'Importación de valores a coactiva',
      endpoint: 'POST /api/v1/coactiva/expedientes/importacion',
      desc: 'Ingresa a coactiva un valor ya generado en el módulo de valores y le asigna número de expediente coactivo, auxiliar y ejecutor para su tratamiento posterior.',
      filters: [T('Tipo de deuda', { t: 'sel', v: 'TRIBUTARIA', opts: tipoDeuda }), T('Contribuyente', { v: '00000031704' }), T('Cod. Unidad', { v: '' }), T('Filtro', { t: 'sel', v: 'TODOS', opts: ['TODOS', 'OP', 'RD', 'RG', 'RM'] })],
      sections: [
        S('Datos de búsqueda', [
          T('Contribuyente', { t: 'ro', v: 'GONZALES ÁVILA-PASCUAL / ESPINOZA ACHA-ZOILA IVONNE', wide: 1 }),
          T('Domicilio fiscal', { t: 'ro', v: 'A.H. QUINCE DE MARZO — AV. SAN FELIPE 0 — E1 LT 02 — Dir. Refer.: E1 LT 02', wide: 1 })
        ])
      ],
      table: {
        title: 'Lista de valores pendientes', count: '3 valores · S/ 115.11',
        cols: ['Seleccione', 'Año recaudo', 'Numero', 'Recaudo', 'TipoMov', 'CodPapel', 'Total recaudo', 'Cod. Contribuyente'], num: [6],
        rows: [
          ['✓', '2026', '0000000726', 'ORDEN DE PAGO — PREDIAL', '—', '—', '44.61', '00000031704'],
          ['—', '2026', '0000000727', 'ORDEN DE PAGO — PREDIAL', '—', '—', '40.62', '00000031704'],
          ['—', '2026', '0000000728', 'ORDEN DE PAGO — PREDIAL', '—', '—', '29.88', '00000031704']
        ]
      },
      tabs: [
        { label: 'Datos Expediente', sections: [
          S('Expediente coactivo', [
            T('Número', { v: '' }), T('Año', { t: 'sel', v: '2026', opts: yrs }),
            T('Asunto', { v: '', wide: 1 }),
            T('Dirección referencial del contribuyente', { t: 'area', wide: 1 }),
            T('Observaciones', { t: 'area', wide: 1 })
          ]),
          S('Encargados', [
            T('Auxiliar', { t: 'sel', v: 'GARCÍA NAVARRO-MARTHA ELENA', opts: ['GARCÍA NAVARRO-MARTHA ELENA', 'RÍOS MENDOZA-MARÍA', 'NO ESPECIFICADO'] }),
            T('Ejecutor', { t: 'sel', v: 'CHECA FERNÁNDEZ-HILTON ARTURO', opts: ['CHECA FERNÁNDEZ-HILTON ARTURO', 'QUISPE PEÑA-JORGE', 'NO ESPECIFICADO'] })
          ])
        ] },
        { label: 'Detalle de Recaudos', sections: [
          S('Recaudo seleccionado', [
            T('Num. recaudo', { t: 'ro', v: '0000000726' }),
            T('Tipo de recaudo', { t: 'ro', v: 'OP — ORDEN DE PAGO' }),
            T('Año deuda', { t: 'ro', v: '2026' }),
            T('Total recaudo (S/)', { t: 'ro', v: '44.61' })
          ], 'Solo lectura')
        ] },
        { label: 'Detalle de Deudas', sections: [
          S('Filtros de deuda', [
            T('Año', { v: '' }), T('Cuota', { v: '' }),
            T('Tributo', { t: 'sel', v: '(TODOS)', opts: tributos }),
            T('Cod. Unid.', { v: '' })
          ])
        ] }
      ],
      actions: ['Importar valores', 'Expedientes libres', 'Rechazar recaudo', 'Limpiar campos']
    },

    expediente_historial: {
      mod: 'Coactiva', title: 'Gestionar historial del expediente',
      endpoint: 'PATCH /api/v1/coactiva/expedientes/{numero}/estados',
      desc: 'Cambia el estado del expediente coactivo y conserva el historial de estados con su documento de respaldo, motivo y observaciones.',
      filters: [T('Contribuyente', { v: '00000031704' }), T('Nº Expediente', { v: '' }), T('Estado actual', { t: 'sel', v: 'Todos', opts: ['Todos', 'REC 01 EMITIDO', 'NOTIFICADO', 'MEDIDA CAUTELAR', 'SUSPENDIDO', 'CONCLUIDO'] }), T('Año', { t: 'sel', v: '2026', opts: yrs })],
      table: {
        title: 'Expedientes encontrados', count: '1 expediente',
        cols: ['Numero', 'Año', 'Cod. Contribuyente', 'Contribuyente', 'Exped. Ant.'], num: [],
        rows: [
          ['0000000906', '2026', '00000031704', 'GONZALES ÁVILA-PASCUAL / ESPINOZA ACHA-ZOILA IVONNE', '—']
        ]
      },
      sections: [
        S('Historial de estados', [
          T('Fec. Doc.', { t: 'ro', v: '11/10/2026' }),
          T('Num. Doc.', { t: 'ro', v: '—' }),
          T('Motivo', { t: 'ro', v: '—' }),
          T('Estado', { t: 'ro', v: 'REC 01 EMITIDO' }),
          T('Activo', { t: 'ro', v: 'Sí' }),
          T('Observaciones', { t: 'ro', v: '—', wide: 1 })
        ], 'Solo lectura'),
        S('Nuevo estado', [
          T('Nº Expediente — año', { t: 'ro', v: '2026' }),
          T('Nº Expediente — número', { t: 'ro', v: '0000000906' }),
          T('Nuevo estado', { t: 'sel', v: '011 — REC 01 EMITIDO', opts: ['011 — REC 01 EMITIDO', '012 — REC 01 NOTIFICADA', '021 — REC 02 EMITIDA', '031 — MEDIDA CAUTELAR', '041 — SUSPENDIDO', '051 — CONCLUIDO'] }),
          T('Activo', { t: 'chk', on: true }),
          T('Motivo', { t: 'area', wide: 1 }),
          T('Observaciones', { t: 'area', wide: 1 }),
          T('Documento de respaldo — fecha', { t: 'date', v: '2026-10-11' }),
          T('Documento de respaldo — número', { v: '' })
        ])
      ],
      actions: ['Nuevo', 'Modificar', 'Quitar', 'Guardar cambios', 'Limpiar']
    },

    cambiar_direccion_ref: {
      mod: 'Coactiva', title: 'Cambiar dirección referencial',
      endpoint: 'PATCH /api/v1/coactiva/expedientes/{numero}/direccion-referencial',
      desc: 'Reemplaza la dirección referencial del expediente coactivo, que es la que se usa para notificar al obligado cuando difiere del domicilio fiscal.',
      sections: [
        S('Datos de búsqueda', [
          T('Contribuyente', { v: '00000003542' }),
          T('Domicilio fiscal', { t: 'ro', v: 'A.H. CUATRO DE NOVIEMBRE — CA. SANTO TORIBIO 17', wide: 1 }),
          T('Dirección referencial actual (expediente)', { t: 'ro', v: '', wide: 1 })
        ]),
        S('Nueva dirección', [
          T('Hab. Urbana', { v: '' }),
          T('Vía', { v: '' }),
          T('Nueva dirección referencial', { v: '', wide: 1 })
        ])
      ],
      actions: ['Buscar', 'Limpiar', 'Cambiar']
    },

    proceso_coactivo: {
      mod: 'Coactiva', title: 'Proceso coactivo',
      endpoint: 'GET /api/v1/coactiva/expedientes/{numero}/proceso',
      desc: 'Seguimiento del expediente coactivo: datos generales, actuaciones del proceso y detalle de los valores que lo integran, con la deuda proyectada a la fecha.',
      filters: [T('Contribuyente', { v: '00000003542' }), T('Expediente — año', { v: '' }), T('Expediente — número', { v: '' }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'REC 01 EMITIDO', 'REC 02 EMITIDA', 'MEDIDA CAUTELAR', 'CONCLUIDO'] })],
      table: {
        title: 'Expedientes del contribuyente', count: '2 expedientes',
        cols: ['ExpCoact', 'Codigo', 'Nombre', 'Asunto', 'Estado', 'CodTipoRecaudo', 'ExpedAnt'], num: [],
        rows: [
          ['0000001201', '00000003542', 'SANTIAGO MOSCOL-GASPAR', '.', ['REC 01 emitido', 'warn'], '003', '701.08T1'],
          ['0000000907', '00000003542', 'SANTIAGO MOSCOL-GASPAR', 'IMPORTACIÓN FISCA', ['REC 01 emitido', 'warn'], '003', '—']
        ]
      },
      tabs: [
        { label: 'Datos Generales', sections: [
          S('Expediente', [
            T('Número', { t: 'ro', v: '0000001201' }),
            T('Año', { t: 'ro', v: '2022' }),
            T('Exp. anterior', { t: 'ro', v: '701.08T1' }),
            T('Asunto', { t: 'area', v: '.', wide: 1 }),
            T('Dirección referencial del contribuyente', { t: 'area', wide: 1 }),
            T('Observaciones', { t: 'area', v: '.', wide: 1 }),
            T('Fecha de creación', { t: 'date', v: '2022-10-01' })
          ]),
          S('Encargados', [
            T('Auxiliar', { t: 'sel', v: 'NO ESPECIFICADO', opts: ['NO ESPECIFICADO', 'GARCÍA NAVARRO-MARTHA ELENA', 'RÍOS MENDOZA-MARÍA'] }),
            T('Ejecutor', { t: 'sel', v: 'NO ESPECIFICADO', opts: ['NO ESPECIFICADO', 'CHECA FERNÁNDEZ-HILTON ARTURO', 'QUISPE PEÑA-JORGE'] })
          ]),
          S('Deuda del expediente', [
            T('Insoluto (S/)', { t: 'ro', v: '186.48' }),
            T('Reajuste (S/)', { t: 'ro', v: '0.00' }),
            T('Interés (S/)', { t: 'ro', v: '0.00' }),
            T('Gastos (S/)', { t: 'ro', v: '92.55' }),
            T('Total (S/)', { t: 'ro', v: '279.03' }),
            T('Proyectada al', { t: 'date', v: '2026-08-13' })
          ], 'Solo lectura')
        ] },
        { label: 'Proceso Coactivo', sections: [
          S('Medida cautelar — REC 2', [
            T('Tipo de medida', { t: 'sel', v: 'EMBARGO EN FORMA DE RETENCIÓN', opts: ['EMBARGO EN FORMA DE RETENCIÓN', 'EMBARGO EN FORMA DE INSCRIPCIÓN', 'EMBARGO EN FORMA DE DEPÓSITO', 'EMBARGO EN FORMA DE INTERVENCIÓN'] }),
            T('Nº de resolución (REC 2)', { v: '' }),
            T('Fecha de emisión', { t: 'date', v: '2026-08-13' }),
            T('Monto del embargo (S/)', { v: '500.00' }),
            T('Entidad financiera', { v: '' }),
            T('Bien embargado', { v: '', wide: 1 }),
            T('Monto retenido (S/)', { v: '0.00' }),
            T('Glosa', { t: 'area', wide: 1 })
          ])
        ] },
        { label: 'Detalle Valores', sections: [
          S('Valores del expediente', [
            T('Nro. de valor', { t: 'ro', v: '0000000726' }),
            T('Tipo de valor', { t: 'ro', v: 'ORDEN DE PAGO — PREDIAL' }),
            T('Año deuda', { t: 'ro', v: '2021' }),
            T('Monto (S/)', { t: 'ro', v: '44.61' })
          ], 'Solo lectura')
        ] }
      ],
      actions: ['Buscar', 'Actualizar deuda', 'Imprimir']
    },

    rec_impresion: {
      mod: 'Coactiva', title: 'Impresión de resolución de ejecución coactiva',
      endpoint: 'POST /api/v1/coactiva/rec/impresion',
      desc: 'Genera e imprime la REC de los expedientes pendientes de pago, con la deuda proyectada al día elegido. Permite imprimir la carátula y la REC 2.',
      filters: [T('Tipo de deuda', { t: 'sel', v: 'TRIBUTARIA', opts: tipoDeuda }), T('Contribuyente', { v: '00000003542' }), T('Año', { t: 'sel', v: '(Todos)', opts: ['(Todos)'].concat(yrs) }), T('Proyectar interés al', { t: 'date', v: '2026-08-13' })],
      sections: [
        S('Filtro de expedientes', [
          T('Rango Nro. expedientes — desde', { v: '0' }), T('Rango Nro. expedientes — hasta', { v: '0' }),
          T('Rango de montos — desde', { v: '0.00' }), T('Rango de montos — hasta', { v: '0.00' }),
          T('Nº expedientes seleccionados', { t: 'ro', v: '2' })
        ])
      ],
      table: {
        title: 'Expedientes pendientes de pago a imprimir', count: '2 expedientes seleccionados',
        cols: ['Seleccione', 'Numero', 'Año', 'CodContribuyente', 'Nombre', 'Estado', 'Asunto'], num: [],
        rows: [
          ['✓', '0000001201', '2022', '00000003542', 'SANTIAGO MOSCOL-GASPAR', ['REC 01 emitido', 'warn'], '—'],
          ['✓', '0000000907', '2026', '00000003542', 'SANTIAGO MOSCOL-GASPAR', ['REC 01 emitido', 'warn'], 'IMPORTACIÓN FISCA']
        ]
      },
      tabs: [
        { label: 'Datos Expediente', sections: [
          S('Expediente', [
            T('Número', { t: 'ro', v: '0000001201' }), T('Año', { t: 'ro', v: '2022' }),
            T('Asunto', { t: 'ro', v: '.', wide: 1 }),
            T('Dirección referencial del contribuyente', { t: 'ro', v: '', wide: 1 }),
            T('Observaciones', { t: 'ro', v: '.', wide: 1 }),
            T('Auxiliar', { v: '' }), T('Ejecutor', { v: '' })
          ])
        ] },
        { label: 'Detalle de Expediente', sections: [
          S('Actuaciones', [
            T('Estado', { t: 'ro', v: 'REC 01 EMITIDO' }),
            T('Fecha del estado', { t: 'ro', v: '11/10/2026' }),
            T('Documento de respaldo', { t: 'ro', v: '—' })
          ], 'Solo lectura')
        ] },
        { label: 'Detalle de Deudas', sections: [
          S('Carga de deudas', [
            T('Proyectar interés al', { t: 'date', v: '2026-08-13' }),
            T('Insoluto (S/)', { t: 'ro', v: '186.48' }),
            T('Interés (S/)', { t: 'ro', v: '0.00' }),
            T('Gastos y costas (S/)', { t: 'ro', v: '92.55' }),
            T('Total (S/)', { t: 'ro', v: '279.03' })
          ])
        ] }
      ],
      actions: ['Listar expedientes', 'Seleccionar todos', 'Generar', 'Imprimir', 'Carátula', 'REC 2']
    },

    fraccionamiento_coactivo: {
      mod: 'Coactiva', title: 'Fraccionamiento coactivo',
      endpoint: 'POST /api/v1/coactiva/convenios',
      desc: 'Convenio tributario coactivo. Se inicia con un pago inicial y sobre el saldo se elabora el cronograma de cuotas, con el beneficio aplicable a la deuda acogida.',
      filters: [T('Forma de pago', { t: 'sel', v: 'CONVENIO TRIBUTARIO PERMA', opts: ['CONVENIO TRIBUTARIO PERMA', 'CONVENIO COACTIVO ORDENANZA', 'PRECONVENIO'] }), T('Benef. aplicable', { t: 'sel', v: 'CONVENIO PERMANENTE', opts: ['CONVENIO PERMANENTE', 'AMNISTÍA COACTIVA 2026', 'SIN BENEFICIO'] }), T('Contribuyente', { v: '00000003542' }), T('Coact.', { t: 'sel', v: 'SÍ', opts: ['SÍ', 'NO'] })],
      sections: [
        S('Contribuyente', [
          T('Nombre', { t: 'ro', v: 'SANTIAGO MOSCOL-GASPAR', wide: 1 }),
          T('Domicilio fiscal', { t: 'ro', v: 'A.H. CUATRO DE NOVIEMBRE — CA. SANTO TORIBIO 17', wide: 1 })
        ]),
        S('Filtros de deuda', [
          T('Año desde', { v: '' }), T('Año hasta', { v: '' }), T('Cuota', { v: '' }),
          T('Tributo', { t: 'sel', v: '(TODOS)', opts: tributos }),
          T('Fase', { v: '' }), T('Conc.', { v: '' }), T('Cod. Unid.', { v: '' }), T('PreConv', { v: '' })
        ]),
        S('Resultado del convenio', [
          T('Deuda total (S/)', { t: 'ro', v: '1,848.66' }),
          T('Deuda acogida (S/)', { t: 'ro', v: '1,848.66' }),
          T('Deuda con beneficio (S/)', { t: 'ro', v: '1,845.51' }),
          T('Registros', { t: 'ro', v: '128 de 128' }),
          T('Tasa (%)', { t: 'ro', v: '0.17' }),
          T('Beneficio (S/)', { t: 'ro', v: '3.15' }),
          T('Pago inicial (S/)', { v: '200.00' }),
          T('Nº de cuotas', { t: 'sel', v: '12', opts: ['3', '6', '9', '12', '18', '24', '36'] })
        ])
      ],
      table: {
        title: 'Deudas acogidas', count: '10 de 128 registros',
        note: 'Deuda total 1,848.66 · acogida 1,848.66 · con beneficio 1,845.51',
        cols: ['Año', 'Unidad', 'Cuota', 'Trib.', 'Nom. Trib.', 'Fase', 'Conc.', 'Est.', 'Insoluto', 'Reajuste', 'Interés', 'Gastos', 'Total'], num: [8, 9, 10, 11, 12],
        rows: [
          ['2026', '200601005670320A010100', '001', '00008', 'JARDINES-REG', '014', '081', ['P', 'warn'], '2.10', '0.00', '0.51', '0.00', '2.61'],
          ['2026', '200601005670320A010100', '001', '00026', 'SERENAZGO-RE', '014', '081', ['P', 'warn'], '3.09', '0.00', '0.75', '0.00', '3.84'],
          ['2026', '200601005670320A010100', '002', '00007', 'LIMPIEZA-REG-E', '014', '081', ['P', 'warn'], '7.07', '0.00', '1.71', '0.00', '8.78'],
          ['2026', '200601005670320A010100', '002', '00008', 'JARDINES-REG', '014', '081', ['P', 'warn'], '2.10', '0.00', '0.51', '0.00', '2.61'],
          ['2026', '200601005670320A010100', '002', '00026', 'SERENAZGO-RE', '014', '081', ['P', 'warn'], '3.09', '0.00', '0.75', '0.00', '3.84'],
          ['2026', '200601005670320A010100', '003', '00007', 'LIMPIEZA-REG-E', '014', '081', ['P', 'warn'], '7.07', '0.00', '1.71', '0.00', '8.78'],
          ['2026', '200601005670320A010100', '003', '00008', 'JARDINES-REG', '014', '081', ['P', 'warn'], '2.10', '0.00', '0.51', '0.00', '2.61'],
          ['2026', '200601005670320A010100', '003', '00026', 'SERENAZGO-RE', '014', '081', ['P', 'warn'], '3.09', '0.00', '0.75', '0.00', '3.84'],
          ['2026', '200601005670320A010100', '004', '00007', 'LIMPIEZA-REG-E', '014', '081', ['P', 'warn'], '7.07', '0.00', '0.94', '0.00', '8.01'],
          ['2026', '200601005670320A010100', '004', '00008', 'JARDINES-REG', '014', '081', ['P', 'warn'], '2.10', '0.00', '0.28', '0.00', '2.38']
        ]
      },
      actions: ['Filtrar', 'Limpiar', 'Fraccionamiento']
    },

    actos_coactivos: {
      mod: 'Coactiva', title: 'Registro de actos coactivos',
      endpoint: 'POST /api/v1/coactiva/expedientes/{numero}/actos',
      desc: 'Registra y emite los documentos de las medidas coactivas adoptadas: embargos, retenciones y demás actos, con su archivo digital adjunto.',
      filters: [T('Exp. — año', { v: '2026' }), T('Exp. — número', { v: '0000005687' }), T('Contrib.', { v: '' }), T('Tributo', { t: 'sel', v: '(TODOS)', opts: tributos })],
      table: {
        title: 'Actos registrados', count: '3 actos',
        cols: ['Expediente', 'Codigo', 'Obligado', 'Deuda S/', 'Referencia', 'Tributo'], num: [3],
        rows: [
          ['2025 3852', '00000004491', 'SUC. TOMÁS MAZA GÓMEZ', '333.58', '—', 'PREDIAL'],
          ['2026 5687', '00000003035', 'INFANTE CÁRCELEN RAÚL', '344.68', 'PRUEBA', 'PREDIAL, SERENAZGO'],
          ['2026 5687', '00000003035', 'INFANTE CÁRCELEN RAÚL', '344.68', 'ACTO DE PRUEBA', 'PREDIAL, SERENAZGO']
        ]
      },
      sections: [
        S('Datos principales', [
          T('Expediente — año', { t: 'ro', v: '2026' }),
          T('Expediente — número', { t: 'ro', v: '0000005687' }),
          T('Obligado', { t: 'ro', v: '00000003035 — INFANTE CÁRCELEN RAÚL', wide: 1 }),
          T('Domicilio', { t: 'ro', v: 'CENTRO DE SULLANA — AV. DE LAMA, JOSÉ 587', wide: 1 }),
          T('D.N.I.', { t: 'ro', v: '02867895' }), T('R.U.C.', { t: 'ro', v: '' }),
          T('Referencia', { v: 'PRUEBA', wide: 1 }),
          T('Tributo', { t: 'ro', v: 'PREDIAL, SERENAZGO' }),
          T('Periodo', { t: 'ro', v: '2026' }),
          T('Deuda (S/)', { t: 'ro', v: '344.68' })
        ]),
        S('Medida cautelar', [
          T('Embargo Nº', { v: '500' }),
          T('Fecha Emb.', { t: 'date', v: '2026-03-10' }),
          T('Monto Emb. (S/)', { v: '500.00' }),
          T('Domic. Emb.', { v: 'CENTRO DE SULLANA — AV. DE LAMA, JOSÉ 587', wide: 1 }),
          T('Bien Emb.', { v: '' }),
          T('Monto retenido (S/)', { v: '0.00' }),
          T('Entidad financiera', { v: '' }),
          T('Glosa', { t: 'area', wide: 1 })
        ]),
        S('Actos administrativos', [
          T('Documento', { t: 'sel', v: 'RESOLUCIÓN COACTIVA', opts: ['RESOLUCIÓN COACTIVA', 'OFICIO DE EMBARGO', 'ACTA DE EMBARGO', 'CARTA', 'NOTIFICACIÓN'] }),
          T('Nº Doc.', { v: '' }), T('Fec. Doc.', { t: 'date', v: '2026-08-13' }),
          T('Nombre de archivo', { t: 'ro', v: '' }),
          T('Glosa del acto', { v: '', wide: 1 })
        ])
      ],
      actions: ['Nuevo', 'Modificar', 'Guardar', 'Imprimir', 'Padrón']
    },

    notificaciones_coactivas: {
      mod: 'Coactiva', title: 'Emisión de notificaciones coactivas',
      endpoint: 'POST /api/v1/coactiva/notificaciones',
      desc: 'Registra y emite las notificaciones de las resoluciones de ejecución coactiva. Admite una o varias notificaciones por expediente según el tratamiento del caso.',
      filters: [T('Contribuyente', { v: '' }), T('Tipo de valor', { t: 'sel', v: 'RES. EJE. COACTIVA - 004', opts: ['RES. EJE. COACTIVA - 004', 'RES. DETERMINACIÓN - 002', 'ORDEN DE PAGO - 001', 'RES. DE MULTA - 035'] }), T('Valor Nº', { v: '' }), T('Exp. Coac.', { v: '' })],
      table: {
        title: 'Valores por notificar', count: '4 valores',
        cols: ['Tipo notif.', 'Cod. Municipal', 'Contribuyente', 'Tipo', 'Año', 'Nº Valor', 'Fec. Emisión', 'Tipo Recaudo', 'Exp. Coac.'], num: [],
        rows: [
          ['DE RET', '00000015099', 'ENCALADA VERA-LIDIO ALBERTO', 'REC', '2026', '0000003985', '19/01/2026', 'RES. EJE. COACTIVA', '0000004505'],
          ['DE RET', '00000327930', 'LEIGH ARBULÚ CARLOS', 'REC', '2026', '0000001404', '13/08/2026', 'RES. EJE. COACTIVA', '0000007669'],
          ['DE RET', '00000009757', 'AGROINDUSTRIAL S.R.L.', 'REC', '2026', '0000001403', '13/08/2026', 'RES. EJE. COACTIVA', '0000000518'],
          ['DE RET', '00000005598', 'LÓPEZ GARCÍA-ARNALDO', 'REC', '2026', '0000001402', '13/08/2026', 'RES. EJE. COACTIVA', '0000004416']
        ]
      },
      sections: [
        S('Notificación', [
          T('Nº Notificación — serie', { v: '' }), T('Nº Notificación — número', { v: '' }),
          T('Nro. visita', { v: '1' }),
          T('Fecha', { t: 'date', v: '2026-08-13' }), T('Vence', { t: 'date', v: '2026-08-20' }),
          T('Representante', { v: '', wide: 1 }),
          T('Notificador', { v: '', wide: 1 }),
          T('Domicilio', { v: '', wide: 1 }),
          T('Recibido por', { t: 'sel', v: 'CONTRIBUYENTE', opts: ['CONTRIBUYENTE', 'REPRESENTANTE', 'FAMILIAR', 'DEPENDIENTE', 'NEGATIVA A RECIBIR', 'CEDULÓN'] }),
          T('D.N.I. del receptor', { v: '' }), T('Nombre del receptor', { v: '' }),
          T('Tipo de notificación', { t: 'sel', v: 'NOTIFICACIÓN CON ÉXITO', opts: ['NOTIFICACIÓN CON ÉXITO', 'NOTIFICACIÓN POR CEDULÓN', 'NOTIFICACIÓN NEGATIVA', 'DIRECCIÓN NO EXISTE', 'DESTINATARIO DESCONOCIDO'] }),
          T('Con firma', { t: 'chk', on: false }),
          T('Características de la vivienda', { t: 'area', wide: 1 }),
          T('Testigo 01', { v: '' }), T('DNI testigo 01', { v: '' }),
          T('Testigo 02', { v: '' }), T('DNI testigo 02', { v: '' }),
          T('Testigo 03', { v: '' }), T('DNI testigo 03', { v: '' })
        ])
      ],
      actions: ['Nuevo', 'Modificar', 'Grabar', 'Deshacer', 'Vista', 'Resol. consentida']
    },

    /* ── AUTORIZACIONES Y LICENCIAS ───────────────────────── */

    anuncios_reportes: {
      mod: 'Autorizaciones y licencias', title: 'Reportes de anuncio y propaganda',
      endpoint: 'POST /api/v1/autorizaciones/anuncios/reportes',
      desc: 'Emite el padrón de autorizaciones de anuncio y propaganda por contribuyente, dirección, estado o intervalo de fechas.',
      sections: [
        S('Tipo de reporte', [
          T('Reporte', { t: 'sel', v: 'PADRÓN DE ANUNCIOS Y PROPAGANDAS', opts: ['PADRÓN DE ANUNCIOS Y PROPAGANDAS'], wide: 1 })
        ]),
        S('Criterios', [
          T('Nº anuncio — serie', { v: '' }), T('Nº anuncio — número', { v: '' }),
          T('Estado', { t: 'sel', v: 'ACTIVA', opts: ['ACTIVA', 'VENCIDA', 'ANULADA', 'TODAS'] }),
          T('Contribuyente', { v: '', wide: 1 }),
          T('Dirección', { v: '', wide: 1 }),
          T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' })
        ])
      ],
      table: {
        title: 'Autorizaciones del padrón', count: '4 autorizaciones · S/ 1,842.00',
        cols: ['Nº autorización', 'Contribuyente', 'Dirección', 'Tipo de anuncio', 'Área m²', 'Tasa S/', 'Vigencia', 'Estado'], num: [4, 5],
        rows: [
          ['001-000418', 'NOBLECILLA ARISMENDIZ SAC', 'AV. JOSÉ DE LAMA 1180', 'LUMINOSO', '12.00', '840.00', '31/12/2026', ['Activa', 'ok']],
          ['001-000419', 'COMERCIAL SULLANA EIRL', 'CALLE BOLÍVAR 318', 'SIMPLE', '4.50', '162.00', '31/12/2026', ['Activa', 'ok']],
          ['001-000420', 'CASTILLO PASCUALA, MARÍA E.', 'CALLE LAMA 482', 'TOLDO', '6.00', '180.00', '31/12/2026', ['Activa', 'ok']],
          ['001-000402', 'RESTAURANT EL PARAÍSO', 'AV. CHAMPAGNAT 118', 'PANEL MONUMENTAL', '18.00', '660.00', '31/12/2025', ['Vencida', 'bad']]
        ]
      },
      actions: ['Exportar', 'Imprimir', 'Pantalla', 'Cancelar']
    },

    licencia_padron: {
      mod: 'Autorizaciones y licencias', title: 'Padrón de licencias de funcionamiento',
      endpoint: 'POST /api/v1/licencias/funcionamiento/reportes/padron',
      desc: 'Padrón de licencias municipales con agrupación por año y subagrupación por giro, dirección o contribuyente. El orden y los filtros se definen antes de emitir.',
      sections: [
        S('Agrupado por', [
          T('Agrupar', { t: 'chk', on: false }),
          T('Año', { t: 'sel', v: '2026', opts: yrs })
        ]),
        S('Subagrupado por', [
          T('Subagrupar', { t: 'chk', on: false }),
          T('Criterio', { t: 'sel', v: 'GIRO COMERCIAL', opts: ['GIRO COMERCIAL', 'DIRECCIÓN', 'NOMBRE CONTRIBUYENTE'] })
        ]),
        S('Ordenado por', [
          T('Ordenar', { t: 'chk', on: true }),
          T('Criterio', { t: 'sel', v: 'NÚMERO DE LICENCIA', opts: ['NÚMERO DE LICENCIA', 'NOMBRE CONTRIBUYENTE', 'GIRO COMERCIAL', 'DIRECCIÓN'] })
        ]),
        S('Filtrado por', [
          T('Filtrar', { t: 'chk', on: false }),
          T('Nº licencia — serie', { v: '' }), T('Nº licencia — número', { v: '' }),
          T('Estado', { t: 'sel', v: 'ACTIVA', opts: ['ACTIVA', 'CANCELADA', 'DUPLICADA', 'VENCIDA', 'TODAS'] }),
          T('Tipo Lic.', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'INDETERMINADA', 'TEMPORAL', 'CESIONARIO', 'MERCADO'] }),
          T('CIIU', { v: '' }),
          T('Dirección', { v: '', wide: 1 }),
          T('Fec. Lic. desde', { t: 'date', v: '2026-01-01' }), T('Fec. Lic. hasta', { t: 'date', v: '2026-08-13' })
        ])
      ],
      table: {
        title: 'Licencias del padrón', count: '5 de 4,182 licencias',
        cols: ['Nº licencia', 'Fecha', 'Contribuyente', 'Nombre comercial', 'CIIU', 'Giro', 'Dirección', 'Estado'], num: [],
        rows: [
          ['LF-2026-00418', '12/02/2026', 'CASTILLO PASCUALA, MARÍA E.', 'BODEGA MARÍA', '4711', 'Bodega', 'CALLE LAMA 482', ['Activa', 'ok']],
          ['LF-2026-00419', '18/02/2026', 'NOBLECILLA ARISMENDIZ SAC', 'RESTAURANT EL NORTE', '5610', 'Restaurante', 'AV. JOSÉ DE LAMA 1180', ['Activa', 'ok']],
          ['LF-2026-00420', '02/03/2026', 'INVERSIONES DEL NORTE SAC', 'FERRETERÍA EL SOL', '4752', 'Ferretería', 'AV. CHAMPAGNAT 220', ['Activa', 'ok']],
          ['LF-2025-00318', '14/07/2025', 'COMERCIAL SULLANA EIRL', 'BOTICA SALUD', '4772', 'Farmacia', 'CALLE BOLÍVAR 318', ['Cancelada', 'bad']],
          ['LF-2024-00812', '20/09/2024', 'SUC. RUFINA MEDINA MEDINA', 'BODEGA SANTA ROSA', '4711', 'Bodega', 'URB. SANTA ROSA 116', ['Activa', 'ok']]
        ]
      },
      actions: ['Exportar', 'Imprimir', 'Pantalla', 'Cancelar']
    },

    licencia_resumen_anual: {
      mod: 'Autorizaciones y licencias', title: 'Resumen de licencias por año',
      endpoint: 'GET /api/v1/licencias/funcionamiento/reportes/resumen-anual',
      desc: 'Cantidades de licencias emitidas, canceladas y duplicadas por año, con la recaudación por derecho de trámite.',
      filters: [T('Desde el año', { t: 'sel', v: '2021', opts: yrs }), T('Hasta el año', { t: 'sel', v: '2026', opts: yrs }), T('Tipo de licencia', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'INDETERMINADA', 'TEMPORAL', 'CESIONARIO', 'MERCADO'] }), T('Agrupado por', { t: 'sel', v: 'AÑO', opts: ['AÑO', 'GIRO COMERCIAL', 'TIPO DE LICENCIA'] })],
      table: {
        title: 'Licencias por año', count: '2021 — 2026',
        cols: ['Año', 'Emitidas', 'Canceladas', 'Duplicados', 'Vigentes al cierre', 'Derecho de trámite S/'], num: [1, 2, 3, 4, 5],
        rows: [
          ['2026', '418', '42', '18', '4,182', '58,420.00'],
          ['2025', '512', '68', '24', '3,806', '71,680.00'],
          ['2024', '488', '54', '21', '3,362', '68,320.00'],
          ['2023', '442', '61', '19', '2,928', '61,880.00'],
          ['2022', '396', '48', '16', '2,547', '55,440.00'],
          ['2021', '318', '39', '12', '2,199', '44,520.00']
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    licencia_resolucion_cancelacion: {
      mod: 'Autorizaciones y licencias', title: 'Resolución de cancelación de licencia', kind: 'report',
      endpoint: 'POST /api/v1/licencias/funcionamiento/{id}/cancelacion',
      desc: 'Resolución que deja sin efecto la licencia de funcionamiento, por solicitud del titular o por cierre del establecimiento.',
      report: {
        code: 'RC-2026-000118', date: '13 de agosto de 2026',
        title: 'Resolución de cancelación de licencia',
        subtitle: 'Licencia municipal de funcionamiento',
        meta: [
          { k: 'Nº de resolución', v: '000118-2026-SGCL/MPS' },
          { k: 'Licencia cancelada', v: 'LF-2025-00318' },
          { k: 'Titular', v: 'COMERCIAL SULLANA EIRL' },
          { k: 'R.U.C.', v: '20525118447' },
          { k: 'Nombre comercial', v: 'BOTICA SALUD' },
          { k: 'Establecimiento', v: 'CALLE BOLÍVAR 318 — SULLANA' }
        ],
        cols: ['Concepto', 'Detalle'], num: [],
        rows: [
          ['Motivo de la cancelación', 'CESE DE ACTIVIDADES SOLICITADO POR EL TITULAR'],
          ['Fecha de cese declarada', '31/07/2026'],
          ['Expediente', '2026-004182'],
          ['Recibo de trámite', '000000049180 — S/ 36.00'],
          ['Deuda pendiente por licencia', 'S/ 0.00']
        ],
        footer: 'Queda sin efecto la licencia municipal de funcionamiento señalada. El titular debe cesar toda actividad comercial en el establecimiento a partir de la fecha de cese declarada.'
      }
    },

    licencia_resolucion_duplicado: {
      mod: 'Autorizaciones y licencias', title: 'Resolución de duplicado de licencia', kind: 'report',
      endpoint: 'POST /api/v1/licencias/funcionamiento/{id}/duplicado',
      desc: 'Resolución que autoriza la emisión de un duplicado de la licencia de funcionamiento, con el número de duplicado que corresponde.',
      report: {
        code: 'RD-2026-000042', date: '13 de agosto de 2026',
        title: 'Resolución de duplicado de licencia',
        subtitle: 'Licencia municipal de funcionamiento',
        meta: [
          { k: 'Nº de resolución', v: '000042-2026-SGCL/MPS' },
          { k: 'Licencia', v: 'LF-2024-00812' },
          { k: 'Duplicado Nº', v: '2' },
          { k: 'Titular', v: 'SUC. RUFINA MEDINA MEDINA' },
          { k: 'Nombre comercial', v: 'BODEGA SANTA ROSA' },
          { k: 'Establecimiento', v: 'URB. SANTA ROSA 116 — SULLANA' }
        ],
        cols: ['Concepto', 'Detalle'], num: [],
        rows: [
          ['Motivo', 'PÉRDIDA DEL ORIGINAL — DECLARACIÓN JURADA ADJUNTA'],
          ['Expediente', '2026-004244'],
          ['Recibo de trámite', '000000049211 — S/ 24.00'],
          ['Giro autorizado', '4711 — Bodega'],
          ['Vigencia', 'INDETERMINADA']
        ],
        footer: 'El duplicado conserva el número, el giro y la vigencia de la licencia original. Su emisión no implica nueva autorización ni modificación de las condiciones del establecimiento.'
      }
    },

    edificacion_reporte: {
      mod: 'Autorizaciones y licencias', title: 'Reporte general de licencias de edificación',
      endpoint: 'GET /api/v1/licencias/edificacion/reportes/general',
      desc: 'Relación de licencias de edificación por modalidad, con el área a construir, el valor de obra declarado y el estado del expediente.',
      filters: [T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Modalidad', { t: 'sel', v: 'Todas', opts: ['Todas', 'A — APROBACIÓN AUTOMÁTICA', 'B — VERIFICACIÓN TÉCNICA', 'C — REVISIÓN POR COMISIÓN', 'D — REVISIÓN POR COMISIÓN'] }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'EN TRÁMITE', 'APROBADA', 'OBSERVADA', 'DENEGADA', 'CONFORME DE OBRA'] })],
      table: {
        title: 'Licencias de edificación', count: '5 de 218 expedientes',
        cols: ['Nº licencia', 'Expediente', 'Fecha', 'Administrado', 'Predio', 'Modalidad', 'Área a construir m²', 'Valor de obra S/', 'Estado'], num: [6, 7],
        rows: [
          ['LE-2026-00118', '2026-001842', '18/02/2026', 'INVERSIONES DEL NORTE SAC', 'AV. CHAMPAGNAT 220', 'C', '842.00', '1,284,000.00', ['Aprobada', 'ok']],
          ['LE-2026-00119', '2026-001918', '02/03/2026', 'CASTILLO PASCUALA, MARÍA E.', 'CALLE LAMA 482', 'A', '84.50', '112,400.00', ['Conforme de obra', 'ok']],
          ['LE-2026-00120', '2026-002044', '14/04/2026', 'DÍAZ MADRID, JULIO CÉSAR', 'C.P. BARRIO BUENOS AIRES', 'B', '164.00', '208,800.00', ['En trámite', 'warn']],
          ['LE-2026-00121', '2026-002188', '28/05/2026', 'NOBLECILLA ARISMENDIZ SAC', 'AV. JOSÉ DE LAMA 1180', 'D', '1,412.00', '2,840,000.00', ['Observada', 'warn']],
          ['LE-2026-00122', '2026-002302', '11/07/2026', 'SUC. RUFINA MEDINA MEDINA', 'URB. SANTA ROSA 116', 'A', '48.00', '62,800.00', ['Aprobada', 'ok']]
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    /* ── SEGURIDAD ────────────────────────────────────────── */

    modulos: {
      mod: 'Seguridad', title: 'Módulos del sistema',
      endpoint: 'GET /api/v1/seguridad/modulos',
      desc: 'Sistemas controlados por el módulo de seguridad integrada. Cada módulo agrupa sus grupos, accesos y permisos.',
      filters: [T('Módulo', { v: '', wide: 1 })],
      table: {
        title: 'Módulos registrados', count: '3 módulos',
        cols: ['Codigo', 'Abreviatura', 'Nombre del módulo', 'Estado'], num: [],
        rows: [
          ['1', 'SIGTM', 'SISTEMA TRIBUTARIO MUNICIPAL', ['Activa', 'ok']],
          ['2', 'SIGAM', 'SISTEMA ADMINISTRATIVO MUNICIPAL', ['Activa', 'ok']],
          ['3', 'SISEG', 'SISTEMA DE SEGURIDAD MUNICIPAL', ['Activa', 'ok']]
        ]
      },
      sections: [
        S('Datos del módulo', [
          T('Código', { t: 'ro', v: '1' }),
          T('Nombre del módulo', { v: 'SISTEMA TRIBUTARIO MUNICIPAL', wide: 1 }),
          T('Abreviatura', { v: 'SIGTM' }),
          T('Estado', { t: 'sel', v: 'ACTIVA', opts: ['ACTIVA', 'INACTIVA'] }),
          T('Descripción', { t: 'area', v: 'SISTEMA TRIBUTARIO MUNICIPAL', wide: 1 })
        ])
      ],
      actions: ['Nuevo', 'Modificar', 'Guardar', 'Deshacer', 'Limpiar', 'Imprimir']
    },

    accesos: {
      mod: 'Seguridad', title: 'Accesos y políticas',
      endpoint: 'GET /api/v1/seguridad/accesos',
      desc: 'Opciones de menú y políticas del sistema controlado. La búsqueda admite filtrar por tipo y por parte del nombre del acceso.',
      filters: [T('Tipo', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'OPCIÓN MENÚ', 'POLÍTICA'] }), T('Nombre del acceso', { v: 'cambiar', wide: 1 })],
      table: {
        title: 'Accesos coincidentes', count: '2 accesos',
        cols: ['Código', 'Tipo', 'Nombre del acceso', 'Modulo', 'Nivel', 'Estado'], num: [],
        rows: [
          ['824', 'MENU', 'Cambiar Password', 'SIGTM', '01.05', ['Activa', 'ok']],
          ['823', 'MENU', 'Cambiar Usuario', 'SIGTM', '01.04', ['Activa', 'ok']]
        ]
      },
      tabs: [
        { label: 'Datos del Acceso', sections: [
          S('Acceso', [
            T('Código', { t: 'ro', v: '824' }),
            T('Modulo', { t: 'sel', v: 'SIGTM', opts: ['SIGTM', 'SIGAM', 'SISEG'] }),
            T('Tipo', { t: 'sel', v: 'OPCIÓN MENÚ', opts: ['OPCIÓN MENÚ', 'POLÍTICA'] }),
            T('Objeto control', { v: 'miCambiarPassword' }),
            T('Nivel', { v: '01.05' }),
            T('Nombre del acceso', { v: 'Cambiar Password', wide: 1 }),
            T('Estado', { t: 'sel', v: 'ACTIVA', opts: ['ACTIVA', 'INACTIVA'] }),
            T('Descripción', { t: 'area', v: 'Archivo - Cambiar Password', wide: 1 })
          ])
        ] },
        { label: 'Usuarios y Grupos Autorizados', sections: [
          S('Autorizados sobre este acceso', [
            T('Buscar usuario o grupo', { v: '', wide: 1 }),
            T('Grupo', { t: 'sel', v: 'ADMINISTRADORES', opts: ['ADMINISTRADORES', 'CAJA', 'EJECUCIÓN PO', 'PLANEAMIENTO', 'FISCALIZACIÓN'] }),
            T('Usuario', { v: '' })
          ])
        ] }
      ],
      actions: ['Nuevo', 'Modificar', 'Guardar', 'Deshacer', 'Eliminar', 'Imprimir']
    },

    miembros: {
      mod: 'Seguridad', title: 'Gestión de miembros',
      endpoint: 'POST /api/v1/seguridad/grupos/{grupo}/miembros',
      desc: 'Afiliación de usuarios a uno o varios grupos, base de la posterior asignación de permisos a nivel de grupo. El árbol de la izquierda lista los grupos del módulo y sus usuarios.',
      filters: [T('Usuario', { v: '' }), T('Grupo', { t: 'sel', v: 'ADMINISTRADORES', opts: ['ADMINISTRADORES', 'CAJA', 'EJECUCIÓN PO', 'PLAN', 'PLANEAMIENTO', 'FISCALIZACIÓN'] }), T('Modulo', { t: 'sel', v: 'SIGAM', opts: ['SIGTM', 'SIGAM', 'SISEG'] }), T('Estado', { t: 'sel', v: 'ACTIVA', opts: ['ACTIVA', 'INACTIVA', 'TODAS'] })],
      table: {
        title: 'Asignación de usuarios y grupos', count: '5 miembros',
        cols: ['Usuario', 'Grupo', 'Fec. Alta', 'Fec. Baja', 'Estado'], num: [],
        rows: [
          ['aayca', 'ADMINISTRADORES', '03/04/2021', '01/01/1900', ['ACTIVA', 'ok']],
          ['ehurtado', 'ADMINISTRADORES', '15/09/2021', '01/01/1900', ['ACTIVA', 'ok']],
          ['fruiz', 'ADMINISTRADORES', '31/10/2021', '01/01/1900', ['ACTIVA', 'ok']],
          ['jquispep', 'ADMINISTRADORES', '03/04/2021', '01/01/1900', ['ACTIVA', 'ok']],
          ['vrosales', 'ADMINISTRADORES', '15/09/2021', '01/01/1900', ['ACTIVA', 'ok']]
        ]
      },
      sections: [
        S('Grupos y usuarios del módulo', [
          T('SIGAM › ADMINISTRADORES', { t: 'ro', v: 'aayca · ehurtado · fruiz · jquispep · vrosales', wide: 1 }),
          T('SIGAM › EJECUCIÓN PO', { t: 'ro', v: 'EjePO', wide: 1 }),
          T('SIGAM › PLAN', { t: 'ro', v: 'avaldez · jquispep · PLAN', wide: 1 }),
          T('SIGAM › PLANEAMIENTO', { t: 'ro', v: 'fmita · jquispep', wide: 1 })
        ], 'Solo lectura')
      ],
      actions: ['Nuevo', 'Agregar', 'Guardar', 'Eliminar', 'Imprimir']
    }

  });
})();
