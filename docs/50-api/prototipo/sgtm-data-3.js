/* SGTM — catálogo de pantallas: Valores y coactiva, Autorizaciones y licencias,
   Seguridad, panel de inicio, portal ciudadano y navegación.
   Campos tomados del Manual de Usuario SGTM (figuras 180-231). */
(function () {
  var T = function (label, o) { return Object.assign({ label: label, t: 'text', v: '', ph: '', opts: null, wide: 0, on: false }, o || {}); };
  var S = function (label, fields, hint) { return { label: label, fields: fields, hint: hint || '' }; };
  var W = window.SGTM_SCREENS = window.SGTM_SCREENS || {};
  var yrs = ['2026', '2025', '2024', '2023', '2022', '2021', '2020'];

  Object.assign(W, {

    /* ── VALORES Y COACTIVA ───────────────────────────────── */

    valores_individual: {
      mod: 'Valores y coactiva', title: 'Generación individual de valores',
      endpoint: 'POST /api/v1/valores',
      desc: 'Emisión de una orden de pago, resolución de determinación o resolución de multa para un contribuyente, con la base legal que la sustenta.',
      sections: [
        S('Datos del valor', [
          T('Tipo de valor', { t: 'sel', v: 'ORDEN DE PAGO', opts: ['ORDEN DE PAGO', 'RESOLUCIÓN DE DETERMINACIÓN', 'RESOLUCIÓN DE MULTA'] }),
          T('Nro. de valor', { t: 'ro', v: 'OP-2026-004182' }),
          T('Fecha de emisión', { t: 'date', v: '2026-07-10' }),
          T('Cod. Contribuyente', { v: '00000003541' }),
          T('Nombre', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA', wide: 1 }),
          T('Base legal', { t: 'sel', v: 'ART. 78º NUM. 1 DEL CÓDIGO TRIBUTARIO', opts: ['ART. 78º NUM. 1 DEL CÓDIGO TRIBUTARIO', 'ART. 76º — DETERMINACIÓN', 'ART. 180º — MULTA'] }),
          T('Tributo', { t: 'sel', v: 'IMPUESTO PREDIAL', opts: ['IMPUESTO PREDIAL', 'ARBITRIOS', 'PATRIMONIO VEHICULAR', 'ALCABALA', 'MULTA'] }),
          T('Unidad (predio / placa)', { v: '02-014-D-14-01' }),
          T('Periodo', { v: '2025 — cuota 3' })
        ]),
        S('Importes', [
          T('Insoluto (S/)', { t: 'ro', v: '144.20' }),
          T('Reajuste (S/)', { t: 'ro', v: '8.60' }),
          T('Interés moratorio (S/)', { t: 'ro', v: '31.18' }),
          T('Gastos (S/)', { t: 'ro', v: '12.00' }),
          T('Total del valor (S/)', { t: 'ro', v: '195.98' }),
          T('Plazo para reclamar', { t: 'ro', v: '20 días hábiles' })
        ])
      ],
      totals: [
        { label: 'Insoluto', value: 'S/ 144.20' },
        { label: 'Reajuste', value: 'S/ 8.60' },
        { label: 'Interés', value: 'S/ 31.18' },
        { label: 'Total del valor', value: 'S/ 195.98', strong: 1 }
      ],
      actions: ['Previsualizar', 'Imprimir', 'Emitir valor']
    },

    valores_masivo: {
      mod: 'Valores y coactiva', title: 'Generación masiva de valores',
      endpoint: 'POST /api/v1/valores/masivo',
      desc: 'Emite órdenes de pago en bloque para toda la deuda vencida que cumpla el filtro, respetando el monto mínimo de emisión fijado por ordenanza.',
      sections: [
        S('Criterios de selección', [
          T('Tipo de valor', { t: 'sel', v: 'ORDEN DE PAGO', opts: ['ORDEN DE PAGO', 'RESOLUCIÓN DE DETERMINACIÓN'] }),
          T('Ejercicio desde', { t: 'sel', v: '2023', opts: yrs }),
          T('Ejercicio hasta', { t: 'sel', v: '2025', opts: yrs }),
          T('Tributo', { t: 'sel', v: 'TODOS', opts: ['TODOS', 'IMPUESTO PREDIAL', 'ARBITRIOS', 'PATRIMONIO VEHICULAR'] }),
          T('Sector', { t: 'sel', v: 'Todos', opts: ['Todos', '01', '02', '03', '04', '05'] }),
          T('Monto mínimo de emisión (S/)', { v: '50.00' }),
          T('Excluye contribuyentes con convenio', { t: 'chk', on: true, ph: 'No emitir a deuda fraccionada vigente' }),
          T('Excluye deuda reclamada', { t: 'chk', on: true, ph: 'No emitir sobre expedientes en trámite' }),
          T('Fecha de emisión', { t: 'date', v: '2026-08-13' })
        ])
      ],
      table: {
        title: 'Simulación de la emisión', count: '4,182 valores · S/ 3.84 M',
        cols: ['Tributo', 'Ejercicios', 'Contribuyentes', 'Valores', 'Insoluto S/', 'Interés S/', 'Total S/'], num: [2, 3, 4, 5, 6],
        rows: [
          ['IMPUESTO PREDIAL', '2023 — 2025', '2,184', '2,412', '1,412,880.00', '284,120.40', '1,697,000.40'],
          ['ARBITRIOS MUNICIPALES', '2023 — 2025', '1,418', '1,418', '1,120,400.00', '241,882.20', '1,362,282.20'],
          ['PATRIMONIO VEHICULAR', '2023 — 2025', '352', '352', '648,000.00', '132,884.60', '780,884.60']
        ],
        note: 'Los valores generados quedan en estado EMITIDO hasta que se registre su notificación; solo entonces empieza a correr el plazo de reclamación.'
      },
      actions: ['Simular', 'Ver excluidos', 'Generar valores']
    },

    valores_busqueda: {
      mod: 'Valores y coactiva', title: 'Búsqueda y mantenimiento de valores',
      endpoint: 'GET /api/v1/valores',
      desc: 'Localiza un valor por número, contribuyente o periodo para consultarlo, anularlo o derivarlo a cobranza coactiva.',
      filters: [T('Nro. de valor', { v: '' }), T('Cod. Contribuyente', { v: '' }), T('Tipo', { t: 'sel', v: 'Todos', opts: ['Todos', 'ORDEN DE PAGO', 'RES. DETERMINACIÓN', 'RES. DE MULTA'] }), T('Ejercicio', { t: 'sel', v: '2026', opts: yrs }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'EMITIDO', 'NOTIFICADO', 'FIRME', 'RECLAMADO', 'COACTIVA', 'ANULADO'] })],
      table: {
        title: 'Valores emitidos', count: '4 de 1,284',
        cols: ['Nro. valor', 'Tipo', 'Contribuyente', 'Tributo', 'Periodo', 'Monto S/', 'Notificación', 'Estado'], num: [5],
        rows: [
          ['OP-2026-004182', 'ORDEN DE PAGO', 'CASTILLO PASCUALA, MARÍA E.', 'IMPUESTO PREDIAL', '2025 — cuota 3', '195.98', '18/07/2026', ['Firme', 'bad']],
          ['RD-2026-000418', 'RES. DETERMINACIÓN', 'INVERSIONES DEL NORTE SAC', 'IMPUESTO PREDIAL', '2021 — 2026', '18,412.00', '02/08/2026', ['Reclamado', 'warn']],
          ['RM-2026-000912', 'RES. DE MULTA', 'NOBLECILLA ARISMENDIZ SAC', 'MULTA ADMINISTRATIVA', '2026', '2,675.00', 'Pendiente', ['Emitido', 'warn']],
          ['OP-2026-004044', 'ORDEN DE PAGO', 'DÍAZ MADRID, JULIO CÉSAR', 'PATRIMONIO VEHICULAR', '2024', '940.64', '11/06/2026', ['Coactiva', 'bad']]
        ]
      },
      sections: [
        S('Anulación del valor', [
          T('Motivo de anulación', { t: 'sel', v: '—', opts: ['—', 'ERROR EN LA DETERMINACIÓN', 'PAGO PREVIO NO IMPUTADO', 'DUPLICIDAD', 'RESOLUCIÓN QUE LO DEJA SIN EFECTO', 'PRESCRIPCIÓN'] }),
          T('Nº de resolución de anulación', { v: '' }),
          T('Fecha', { t: 'date' }),
          T('Sustento', { t: 'area', wide: 1 })
        ], 'Opcional')
      ],
      actions: ['Excel', 'Anular valor', 'Derivar a coactiva']
    },

    notificacion_valores: {
      mod: 'Valores y coactiva', title: 'Notificación de valores',
      endpoint: 'POST /api/v1/valores/{nro}/notificacion',
      desc: 'Registro del acto de notificación. La fecha de notificación determina el inicio del plazo de reclamación y, vencido este, la firmeza del valor.',
      filters: [T('Nro. de valor', { v: 'OP-2026-004182' }), T('Notificador', { t: 'sel', v: 'Todos', opts: ['Todos', 'J. RUIZ PALACIOS', 'A. VÍLCHEZ ROJAS'] }), T('Resultado', { t: 'sel', v: 'Todos', opts: ['Todos', 'RECIBIDO POR EL TITULAR', 'RECIBIDO POR TERCERO', 'CEDULÓN FIJADO', 'RECHAZADO', 'DOMICILIO CERRADO', 'NO UBICADO'] })],
      sections: [
        S('Acto de notificación', [
          T('Nro. de valor', { t: 'ro', v: 'OP-2026-004182' }),
          T('Contribuyente', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA', wide: 1 }),
          T('Domicilio fiscal', { t: 'ro', v: 'CALLE LAMA 482 — ZONA 2 INDUSTRIAL', wide: 1 }),
          T('Tipo de notificación', { t: 'sel', v: 'PERSONAL EN DOMICILIO FISCAL', opts: ['PERSONAL EN DOMICILIO FISCAL', 'CEDULÓN', 'PUBLICACIÓN', 'BUZÓN ELECTRÓNICO'] }),
          T('Fecha de notificación', { t: 'date', v: '2026-07-18' }),
          T('Hora', { v: '11:40' }),
          T('Notificador', { t: 'sel', v: 'J. RUIZ PALACIOS', opts: ['J. RUIZ PALACIOS', 'A. VÍLCHEZ ROJAS'] }),
          T('Resultado', { t: 'sel', v: 'RECIBIDO POR EL TITULAR', opts: ['RECIBIDO POR EL TITULAR', 'RECIBIDO POR TERCERO', 'CEDULÓN FIJADO', 'RECHAZADO', 'DOMICILIO CERRADO', 'NO UBICADO'] }),
          T('Persona que recibe', { v: 'CASTILLO PASCUALA, MARÍA E.' }),
          T('Documento de quien recibe', { v: '44218937' }),
          T('Vínculo', { t: 'sel', v: 'TITULAR', opts: ['TITULAR', 'FAMILIAR', 'EMPLEADO', 'PORTERO', 'OTRO'] }),
          T('Fecha de firmeza', { t: 'ro', v: '15/08/2026' }),
          T('Observaciones', { t: 'area', wide: 1 })
        ])
      ],
      actions: ['Imprimir cargo', 'Registrar notificación']
    },

    coactiva_expedientes: {
      mod: 'Valores y coactiva', title: 'Expedientes coactivos',
      endpoint: 'GET /api/v1/coactiva/expedientes',
      desc: 'Cobranza coactiva de valores firmes: resolución de ejecución, medidas cautelares, costas y gastos, y causales de suspensión.',
      filters: [T('Nro. de expediente', { v: '' }), T('Cod. Contribuyente', { v: '' }), T('Ejecutor', { t: 'sel', v: 'R. MENDOZA CRUZ', opts: ['R. MENDOZA CRUZ', 'C. ANCAJIMA FLORES'] }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'INICIADO', 'CON MEDIDA CAUTELAR', 'SUSPENDIDO', 'CONCLUIDO'] })],
      table: {
        title: 'Expedientes activos', count: '4 de 318',
        cols: ['Expediente', 'Contribuyente', 'Valores', 'Deuda S/', 'Costas S/', 'Medida cautelar', 'Estado'], num: [2, 3, 4],
        rows: [
          ['EC-2026-00412', 'DÍAZ MADRID, JULIO CÉSAR', '3', '9,412.15', '941.20', 'Embargo en forma de retención', ['Con medida', 'bad']],
          ['EC-2026-00418', 'INVERSIONES DEL NORTE SAC', '1', '18,412.00', '1,841.20', 'Embargo en forma de inscripción', ['Con medida', 'bad']],
          ['EC-2026-00421', 'CASTILLO PASCUALA, MARÍA E.', '1', '940.64', '96.00', 'Ninguna', ['Iniciado', 'warn']],
          ['EC-2025-00988', 'REYES CHUNGA, PEDRO', '2', '0.00', '0.00', 'Levantada', ['Concluido', 'ok']]
        ]
      },
      tabs: [
        { label: 'REC', sections: [
          S('Resolución de ejecución coactiva', [
            T('Nro. de expediente', { t: 'ro', v: 'EC-2026-00421' }),
            T('Nro. de REC', { v: 'REC-0421-2026-MPS-EC' }),
            T('Fecha de inicio', { t: 'date', v: '2026-08-04' }),
            T('Ejecutor coactivo', { t: 'ro', v: 'R. MENDOZA CRUZ' }),
            T('Auxiliar coactivo', { t: 'sel', v: 'S. PALACIOS NIMA', opts: ['S. PALACIOS NIMA', 'K. CHERO VARGAS'] }),
            T('Cod. Contribuyente', { t: 'ro', v: '00000003541' }),
            T('Valores acumulados', { t: 'ro', v: '1' }),
            T('Plazo para pago voluntario', { t: 'ro', v: '7 días hábiles' }),
            T('Fecha de notificación de la REC', { t: 'date', v: '2026-08-06' })
          ])
        ] },
        { label: 'Medida cautelar', sections: [
          S('Medida trabada', [
            T('Tipo de medida', { t: 'sel', v: 'EMBARGO EN FORMA DE RETENCIÓN', opts: ['NINGUNA', 'EMBARGO EN FORMA DE RETENCIÓN', 'EMBARGO EN FORMA DE INSCRIPCIÓN', 'EMBARGO EN FORMA DE DEPÓSITO', 'EMBARGO EN FORMA DE INTERVENCIÓN'] }),
            T('Nº de resolución coactiva', { v: 'RC-02-0421-2026' }),
            T('Entidad / tercero retenedor', { v: 'BANCO DE LA NACIÓN' }),
            T('Bien o cuenta afectada', { v: 'CTA. AHORROS 00-412-118442', wide: 1 }),
            T('Monto de la medida (S/)', { v: '1,036.64' }),
            T('Fecha de la medida', { t: 'date', v: '2026-08-11' }),
            T('Resultado', { t: 'sel', v: 'EN TRÁMITE', opts: ['EN TRÁMITE', 'RETENIDO', 'SIN FONDOS', 'LEVANTADA'] })
          ])
        ] },
        { label: 'Costas y gastos', sections: [
          S('Liquidación de costas', [
            T('Deuda materia de cobranza (S/)', { t: 'ro', v: '940.64' }),
            T('Costas procesales (10 %)', { t: 'ro', v: '94.06' }),
            T('Gastos de notificación (S/)', { v: '12.00' }),
            T('Gastos de medida cautelar (S/)', { v: '38.00' }),
            T('Gastos de tasación (S/)', { v: '0.00' }),
            T('Gastos de remate (S/)', { v: '0.00' }),
            T('Total costas y gastos (S/)', { t: 'ro', v: '144.06' })
          ])
        ] },
        { label: 'Suspensión', sections: [
          S('Suspensión y conclusión', [
            T('Causal', { t: 'sel', v: 'NINGUNA', opts: ['NINGUNA', 'RECLAMACIÓN EN TRÁMITE', 'PRESCRIPCIÓN DECLARADA', 'PAGO TOTAL', 'CONVENIO DE FRACCIONAMIENTO', 'MANDATO JUDICIAL', 'DEUDA DECLARADA NULA'] }),
            T('Documento sustentatorio', { v: '' }),
            T('Fecha de suspensión', { t: 'date' }),
            T('Fecha de conclusión', { t: 'date' }),
            T('Observaciones', { t: 'area', wide: 1 })
          ], 'Opcional')
        ] }
      ],
      totals: [
        { label: 'Deuda en coactiva', value: 'S/ 940.64' },
        { label: 'Costas y gastos', value: 'S/ 144.06' },
        { label: 'Retenido', value: 'S/ 0.00' },
        { label: 'Total exigible', value: 'S/ 1,084.70', strong: 1 }
      ],
      actions: ['Iniciar cobranza', 'Trabar medida', 'Suspender', 'Imprimir REC']
    },

    prescripcion: {
      mod: 'Valores y coactiva', title: 'Prescripción de la deuda',
      endpoint: 'POST /api/v1/coactiva/prescripcion',
      desc: 'Solicitud de prescripción de la acción de cobro, con el cómputo del plazo y los actos que lo interrumpen o suspenden.',
      sections: [
        S('Solicitud', [
          T('Nº de expediente', { t: 'ro', v: '2026-1204' }),
          T('Cod. Contribuyente', { v: '00000006550' }),
          T('Nombre', { t: 'ro', v: 'DÍAZ MADRID, JULIO CÉSAR' }),
          T('Tributo', { t: 'sel', v: 'IMPUESTO PREDIAL', opts: ['IMPUESTO PREDIAL', 'ARBITRIOS', 'PATRIMONIO VEHICULAR', 'MULTA'] }),
          T('Ejercicios solicitados', { v: '2014 — 2018' }),
          T('Fecha de presentación', { t: 'date', v: '2026-08-04' })
        ]),
        S('Cómputo del plazo', [
          T('Plazo aplicable', { t: 'sel', v: '4 AÑOS — DECLARACIÓN PRESENTADA', opts: ['4 AÑOS — DECLARACIÓN PRESENTADA', '6 AÑOS — NO PRESENTÓ DECLARACIÓN', '10 AÑOS — AGENTE DE RETENCIÓN'] }),
          T('Inicio del cómputo', { t: 'ro', v: '01/01/2015' }),
          T('Acto de interrupción', { t: 'sel', v: 'NOTIFICACIÓN DE ORDEN DE PAGO', opts: ['NINGUNO', 'NOTIFICACIÓN DE ORDEN DE PAGO', 'PAGO PARCIAL', 'RECONOCIMIENTO DE DEUDA', 'NOTIFICACIÓN DE REC', 'SOLICITUD DE FRACCIONAMIENTO'] }),
          T('Fecha del último acto', { t: 'date', v: '2019-05-12' }),
          T('Nuevo inicio del cómputo', { t: 'ro', v: '13/05/2019' }),
          T('Fecha de prescripción', { t: 'ro', v: '13/05/2023' }),
          T('Resultado', { t: 'sel', v: 'PROCEDE', opts: ['PROCEDE', 'PROCEDE EN PARTE', 'NO PROCEDE'] }),
          T('Nº de resolución', { v: 'RGAT-0244-2026-MPS' }),
          T('Monto a extinguir (S/)', { t: 'ro', v: '4,412.80' })
        ])
      ],
      actions: ['Calcular', 'Notificar', 'Resolver']
    },

    /* ── AUTORIZACIONES Y LICENCIAS ───────────────────────── */

    anuncios: {
      mod: 'Autorizaciones', title: 'Anuncio y propaganda',
      endpoint: 'GET /api/v1/autorizaciones/anuncios',
      desc: 'Autorización para instalar elementos publicitarios. La tasa resulta del área del anuncio, el número de lados y su clase.',
      filters: [T('Nro. Autorización', { v: '' }), T('Contribuyente', { v: '' }), T('R.U.C.', { v: '' }), T('Nº Expediente', { v: '' }), T('Dirección', { v: '' }), T('D.N.I.', { v: '' })],
      table: {
        title: 'Autorizaciones encontradas', count: '3 de 884',
        cols: ['Est.', 'Nro. Autorización', 'Nro. Expediente', 'Contribuyente', 'D.N.I.', 'R.U.C.', 'Dirección', 'Tasa S/'], num: [7],
        rows: [
          [['A', 'ok'], '2010-000001', '—', 'SUC. RUFINA MEDINA MEDINA', '03593174', '—', 'URB. SANTA ROSA — EL ALTO 116', '0.00'],
          [['A', 'ok'], '2026-000184', '2026-0884', 'RESTAURANT SABOR Y SAZÓN', '44218937', '—', 'AV. JOSÉ DE LAMA 1180', '288.00'],
          [['P', 'warn'], '2026-000191', '2026-0918', 'NOBLECILLA ARISMENDIZ SAC', '—', '20525118447', 'CARRETERA SULLANA-PAITA KM 2', '2,880.00']
        ]
      },
      tabs: [
        { label: 'Datos Generales', sections: [
          S('Anuncio', [
            T('Nro. Autorización', { t: 'ro', v: '2010 — 000001' }),
            T('Estado', { t: 'sel', v: 'A — ACTIVA', opts: ['A — ACTIVA', 'I — INACTIVA', 'P — PENDIENTE', 'X — ANULADA'] }),
            T('Fec. Inicio', { t: 'date', v: '2010-10-19' }),
            T('Fec. Venc.', { t: 'date', v: '2011-10-19' })
          ]),
          S('Datos del titular', [
            T('Nro. Licencia', { v: '' }),
            T('Razón Social', { v: '' }),
            T('Contribuyente', { v: '00000025673' }),
            T('Nombre', { t: 'ro', v: 'SUC. RUFINA MEDINA MEDINA' }),
            T('R.U.C.', { v: '' }), T('D.N.I.', { v: '03593174' }),
            T('Cod. Catastral', { v: '200601001010080A0101001' }),
            T('Dirección', { t: 'ro', v: 'URB. SANTA ROSA — EL ALTO 116', wide: 1 })
          ]),
          S('Características', [
            T('Clase Anuncio', { t: 'sel', v: 'LETRERO', opts: ['LETRERO', 'PANEL', 'TOLDO', 'BANDEROLA', 'PANTALLA DIGITAL', 'GLOBO AEROSTÁTICO'] }),
            T('Ubicacion', { t: 'sel', v: 'LOCALES COMERCIALES', opts: ['LOCALES COMERCIALES', 'VÍA PÚBLICA', 'AZOTEA', 'FACHADA', 'TERRENO PRIVADO'] }),
            T('Tipo Anuncio', { t: 'sel', v: 'AVISO SIMPLE', opts: ['AVISO SIMPLE', 'AVISO LUMINOSO', 'AVISO ILUMINADO', 'AVISO ELECTRÓNICO'] }),
            T('Forma', { t: 'sel', v: 'MONOLITO', opts: ['MONOLITO', 'ADOSADO', 'BIPOSTE', 'MONOPOSTE', 'TIPO BANDERA'] }),
            T('Denominación', { v: 'VAMOS PERU!!!', wide: 1 }),
            T('Base', { v: '8.0000' }), T('Altura', { v: '2.0000' }),
            T('Nro lados', { v: '2' }), T('Area', { t: 'ro', v: '32.0000' }),
            T('Tasa', { t: 'ro', v: '0.0000' }),
            T('Observación', { v: '', wide: 1 })
          ]),
          S('Trámite interno', [
            T('Nro de Expediente', { v: '' }),
            T('Fecha Exp.', { t: 'date', v: '1900-01-01' }),
            T('Nro de Resolución', { v: '' }),
            T('Fecha de Res.', { t: 'date', v: '1900-01-01' }),
            T('Nro. Recibo', { v: '' }),
            T('Fecha Rec.', { t: 'date', v: '1900-01-01' }),
            T('Importe Rec.', { t: 'ro', v: '0.000' })
          ], 'Opcional')
        ] },
        { label: 'Cancelación', sections: [
          S('Cese de la autorización', [
            T('Fecha de cancelación', { t: 'date' }),
            T('Motivo', { t: 'sel', v: '—', opts: ['—', 'SOLICITUD DEL TITULAR', 'VENCIMIENTO', 'RETIRO POR INFRACCIÓN', 'CESE DEL NEGOCIO'] }),
            T('Nº de documento', { v: '' }),
            T('Anuncio retirado', { t: 'chk', on: false, ph: 'Verificado en campo' })
          ], 'Opcional')
        ] },
        { label: 'Observaciones', sections: [
          S('Notas', [T('Observaciones', { t: 'area', wide: 1 })], 'Opcional')
        ] }
      ],
      actions: ['Nuevo', 'Activar', 'Excel', 'Imprimir', 'Guardar']
    },

    licencia_funcionamiento: {
      mod: 'Licencias', title: 'Licencia de funcionamiento',
      endpoint: 'GET /api/v1/licencias/funcionamiento',
      desc: 'Registro y seguimiento de licencias comerciales, con giros CIIU, zonificación, aforo, inspección técnica de seguridad y arbitrios del establecimiento.',
      filters: [T('Nro. Licencia', { v: '2010-000000' }), T('Nº Expediente', { v: '' }), T('Nombre del Contribuyente', { v: '' }), T('Denominación Comercial', { v: '' }), T('Dirección', { v: '' })],
      table: {
        title: 'Licencias encontradas', count: '4 de 6,551',
        cols: ['Est.', 'Nro. Licencia', 'Contribuyente', 'Nº Expediente', 'Denominación Comercial', 'Dirección'],
        rows: [
          [['P', 'warn'], '2010-000000', 'CASTILLO PASCUALA, MARÍA E.', '2010-0281', 'RESTAURANT SABOR Y SAZÓN', 'ZONA 2 INDUSTRIAL'],
          [['A', 'ok'], '2010-006549', 'QUIROGA RAMOS, ELEODORO', '2010-0280', 'BODEGA EL SOL', 'CENTRO DE SULLANA — DE LAMA'],
          [['A', 'ok'], '2010-006550', 'DÍAZ MADRID, JULIO CÉSAR', '2010-0650', 'FERRETERÍA DÍAZ', 'C.P. BARRIO BUENOS AIRES'],
          [['C', 'bad'], '2010-006551', 'NOBLECILLA ARISMENDIZ SAC', '2010-0621', 'DEPÓSITO NOBLECILLA', 'C.P. BARRIO BUENOS AIRES']
        ]
      },
      tabs: [
        { label: 'Datos Generales', sections: [
          S('Licencia', [
            T('Código interno', { t: 'ro', v: '54350' }),
            T('Proceso', { t: 'sel', v: 'REGISTRO SIMPLE DE NUEVA LICENCIA', opts: ['REGISTRO SIMPLE DE NUEVA LICENCIA', 'RENOVACIÓN', 'AMPLIACIÓN DE GIRO', 'CAMBIO DE TITULAR', 'DUPLICADO', 'CESE'] }),
            T('Nro. Licencia', { t: 'ro', v: '2010-000000' }),
            T('Estado', { t: 'sel', v: 'P — PENDIENTE', opts: ['A — ACTIVA', 'P — PENDIENTE', 'C — CESADA', 'S — SUSPENDIDA', 'X — ANULADA'] }),
            T('Tipo de licencia', { t: 'sel', v: 'DEFINITIVA', opts: ['DEFINITIVA', 'TEMPORAL', 'CESIONARIA'] }),
            T('Fecha de emisión', { t: 'date', v: '2026-09-16' }),
            T('Fecha de vencimiento', { t: 'date' }),
            T('Horario autorizado', { t: 'sel', v: 'DE 06:00 A 23:00 HORAS', opts: ['DE 06:00 A 23:00 HORAS', 'DE 08:00 A 20:00 HORAS', 'DE 24 HORAS', 'HORARIO EXTENDIDO'] })
          ]),
          S('Contribuyente y denominación', [
            T('Cod. Contribuyente', { t: 'ro', v: '00000003541' }),
            T('Nombre / razón social', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA', wide: 1 }),
            T('D.N.I.', { v: '44218937' }), T('R.U.C.', { v: '' }),
            T('Denominación Comercial', { v: 'RESTAURANT SABOR Y SAZÓN', wide: 1 }),
            T('Actividad principal', { v: 'SERVICIO DE RESTAURANTE', wide: 1 })
          ]),
          S('Giros CIIU autorizados', [
            T('CIIU 1', { t: 'ro', v: 'D-1549-19 — RESTAURANTE-POLLERÍA' }),
            T('CIIU 2', { t: 'ro', v: 'H-5520-02 — SERVICIO DE RESTAURANTES A DOMICILIO' }),
            T('CIIU 3', { t: 'ro', v: 'H-5520-63 — CHIFA AL PASO' }),
            T('Agregar giro', { ph: 'Buscar por código o descripción', wide: 1 })
          ]),
          S('Expediente, resolución y recibo', [
            T('Nº de expediente', { v: '2010-0281' }),
            T('Fecha de expediente', { t: 'date', v: '2026-09-16' }),
            T('Nº de resolución', { v: '' }),
            T('Fecha de resolución', { t: 'date' }),
            T('Nº de recibo', { v: '' }),
            T('Importe pagado (S/)', { t: 'ro', v: '412.00' }),
            T('Fecha de pago', { t: 'date' })
          ], 'Opcional')
        ] },
        { label: 'Predio', sections: [
          S('Establecimiento', [
            T('Código predial', { v: '02-014-D-14-01' }),
            T('Dirección del establecimiento', { t: 'ro', v: 'CALLE LAMA 482 — ZONA 2 INDUSTRIAL', wide: 1 }),
            T('Área del establecimiento (m²)', { v: '96.00' }),
            T('Zonificación', { t: 'sel', v: 'CV — COMERCIO VECINAL', opts: ['RDM — RESIDENCIAL DENSIDAD MEDIA', 'CV — COMERCIO VECINAL', 'CZ — COMERCIO ZONAL', 'I1 — INDUSTRIA LIVIANA', 'OU — OTROS USOS'] }),
            T('Compatibilidad de uso', { t: 'sel', v: 'COMPATIBLE', opts: ['COMPATIBLE', 'COMPATIBLE CON RESTRICCIONES', 'NO COMPATIBLE'] }),
            T('Aforo autorizado', { v: '48' }),
            T('Condición del local', { t: 'sel', v: 'ALQUILADO', opts: ['PROPIO', 'ALQUILADO', 'CEDIDO EN USO'] })
          ]),
          S('Inspección técnica de seguridad (ITSE)', [
            T('Nivel de riesgo', { t: 'sel', v: 'RIESGO MEDIO', opts: ['RIESGO BAJO', 'RIESGO MEDIO', 'RIESGO ALTO', 'RIESGO MUY ALTO'] }),
            T('Momento de la ITSE', { t: 'sel', v: 'POSTERIOR', opts: ['PREVIA', 'POSTERIOR'] }),
            T('Nº de certificado ITSE', { v: '' }),
            T('Fecha de inspección', { t: 'date' }),
            T('Resultado', { t: 'sel', v: 'PENDIENTE', opts: ['PENDIENTE', 'CONFORME', 'OBSERVADO', 'NO CONFORME'] })
          ])
        ] },
        { label: 'Documentos', sections: [
          S('Requisitos presentados', [
            T('Solicitud - declaración jurada', { t: 'chk', on: true, ph: 'Presentada' }),
            T('Vigencia de poder', { t: 'chk', on: false, ph: 'Solo persona jurídica' }),
            T('Declaración jurada de ITSE', { t: 'chk', on: true, ph: 'Presentada' }),
            T('Autorización sectorial', { t: 'chk', on: false, ph: 'Según giro' }),
            T('Copia del contrato de alquiler', { t: 'chk', on: true, ph: 'Presentada' }),
            T('Recibo de pago del derecho', { t: 'chk', on: false, ph: 'Pendiente' })
          ])
        ] },
        { label: 'Arbitrios', sections: [
          S('Arbitrios del establecimiento', [
            T('Uso para arbitrios', { t: 'sel', v: 'COMERCIO', opts: ['COMERCIO', 'SERVICIOS', 'INDUSTRIA'] }),
            T('Zona', { t: 'sel', v: 'Zona 2', opts: ['Zona 1', 'Zona 2', 'Zona 3'] }),
            T('Limpieza pública anual (S/)', { t: 'ro', v: '412.80' }),
            T('Parques y jardines anual (S/)', { t: 'ro', v: '96.00' }),
            T('Serenazgo anual (S/)', { t: 'ro', v: '188.40' }),
            T('Total arbitrios anual (S/)', { t: 'ro', v: '697.20' })
          ])
        ] },
        { label: 'Procesos', sections: [
          S('Trazabilidad del trámite', [
            T('Estado del trámite', { t: 'ro', v: 'EN EVALUACIÓN — SUBGERENCIA DE COMERCIALIZACIÓN' }),
            T('Plazo TUPA', { t: 'ro', v: '15 días hábiles' }),
            T('Días transcurridos', { t: 'ro', v: '4' }),
            T('Observaciones', { t: 'area', wide: 1, v: 'Falta acreditar el pago del derecho de trámite.' })
          ])
        ] }
      ],
      actions: ['Nuevo', 'Activar', 'Duplicar', 'Imprimir licencia', 'Guardar']
    },

    fue_edificacion: {
      mod: 'Licencias', title: 'Formulario único de edificación (FUE)',
      endpoint: 'GET /api/v1/licencias/edificacion',
      desc: 'Licencia de obra bajo la Ley 29090. La modalidad de aprobación determina si basta la verificación administrativa o se requiere comisión técnica.',
      filters: [T('NRO EXPEDIENTE', { v: '' }), T('NRO LICENCIA', { v: '' }), T('NOMBRE CONTRIBUYENTE', { v: '%%%%' }), T('LUGAR — Mz.', { v: '' }), T('LUGAR — Lt.', { v: '' }), T('TIPO TRAMITE', { t: 'sel', v: 'Todos', opts: ['Todos', 'ANTEPROYECTO EN CONSULTA', 'LICENCIA DE OBRA', 'AMPLIACIÓN DE LICENCIA', 'REVALIDACIÓN DE LICENCIA', 'REGULARIZACIÓN DE LICENCIA'] })],
      table: {
        title: 'Registros encontrados', count: '1 — Registros Encontrados',
        cols: ['Nro Expedient', 'Contribuyente', 'Nombre Contribuyente', 'Tipo Tramite', 'Nro Licencia', 'Modalidad'],
        rows: [
          ['00007', '00000152614', 'OLIVER FABIAN VALDEZ RIOS Y MILENA ALE', 'LICENCIA DE OBRA', '000001', 'APROBACIÓN AUTOMÁTICA']
        ]
      },
      tabs: [
        { label: 'Datos Licencia', sections: [
          S('Expediente', [
            T('NRO EXPEDIENTE', { t: 'ro', v: '00007' }),
            T('NRO EXPEDIENTE ANTERIOR', { v: '' }),
            T('NRO LICENCIA ANTERIOR', { v: '2010' })
          ]),
          S('Licencia de edificación', [
            T('Tipo Trámite', { t: 'sel', v: 'LICENCIA DE OBRA', opts: ['ANTEPROYECTO EN CONSULTA', 'LICENCIA DE OBRA', 'AMPLIACIÓN DE LICENCIA', 'REVALIDACIÓN DE LICENCIA', 'REGULARIZACIÓN DE LICENCIA'] }),
            T('OBRA', { t: 'sel', v: 'EDIFICACIÓN NUEVA', opts: ['EDIFICACIÓN NUEVA', 'AMPLIACIÓN', 'REMODELACIÓN', 'DEMOLICIÓN', 'CERCO', 'PUESTA EN VALOR'] }),
            T('FECHA DECLARACIÓN', { t: 'date', v: '2010-09-14' }),
            T('FECHA CADUCIDAD', { t: 'date', v: '2015-09-14' }),
            T('FECHA INICIO DE OBRA', { t: 'date', v: '2010-09-14' }),
            T('TIPO TRAMITE', { t: 'ro', v: 'LICENCIA DE EDIFICACION NUEVA.', wide: 1 }),
            T('MODALIDAD APROBACION', { t: 'sel', v: 'A — APROBACION AUTOMATICA', opts: ['A — APROBACION AUTOMATICA', 'B — APROBACIÓN CON EVALUACIÓN PREVIA', 'C — COMISIÓN TÉCNICA', 'D — COMISIÓN TÉCNICA'] }),
            T('Revisión', { t: 'sel', v: 'REVISORES URBANOS', opts: ['REVISORES URBANOS', 'COMISION TECNICA'] }),
            T('Generar N° Licencia', { t: 'chk', on: false, ph: 'Nº 000001' })
          ]),
          S('Anexos', [
            T('"A" DATOS CONDOMINOS - PERSONAS NATURALES', { t: 'chk', on: false, ph: 'Adjunta anexo A' }),
            T('"B" DATOS CONDOMINOS - PERSONAS JURIDICAS', { t: 'chk', on: false, ph: 'Adjunta anexo B' }),
            T('"C" PRE-DECLARATORIA DE FABRICA', { t: 'chk', on: false, ph: 'Adjunta anexo C' }),
            T('"D" AUTOLIQUIDACION', { t: 'chk', on: false, ph: 'Adjunta anexo D' }),
            T('SOLICITANTE', { t: 'sel', v: 'PROPIETARIO', opts: ['PROPIETARIO', 'NO PROPIETARIO'] })
          ])
        ] },
        { label: 'Datos Solicitante', sections: [
          S('Solicitante', [
            T('Cod. Contribuyente', { v: '00000152614' }),
            T('Nombre / razón social', { t: 'ro', v: 'OLIVER FABIAN VALDEZ RIOS Y MILENA ALE', wide: 1 }),
            T('D.N.I.', { v: '44118207' }), T('R.U.C.', { v: '' }),
            T('Domicilio', { v: 'URB. SANTA ROSA MZ. D LT. 14', wide: 1 }),
            T('Teléfono', { v: '073-502147' }),
            T('Correo electrónico', { v: 'ovaldez@correo.pe' })
          ])
        ] },
        { label: 'Representante Legal', sections: [
          S('Representante', [
            T('D.N.I.', { v: '' }), T('Nombre', { v: '' }),
            T('Partida registral del poder', { v: '' }),
            T('Vigencia de poder', { t: 'date' })
          ], 'Opcional')
        ] },
        { label: 'Datos Terreno', sections: [
          S('Terreno', [
            T('Cod. Catastral', { v: '200601010150010101001' }),
            T('Dirección', { v: 'URB. SANTA ROSA MZ. D LT. 14', wide: 1 }),
            T('Mz.', { v: 'D' }), T('Lt.', { v: '14' }),
            T('Área del terreno (m²)', { v: '210.00' }),
            T('Zonificación', { t: 'sel', v: 'RDM — RESIDENCIAL DENSIDAD MEDIA', opts: ['RDM — RESIDENCIAL DENSIDAD MEDIA', 'CV — COMERCIO VECINAL', 'CZ — COMERCIO ZONAL', 'I1 — INDUSTRIA LIVIANA'] }),
            T('Partida registral', { v: 'P11024478' }),
            T('Frente (m)', { v: '10.50' }), T('Fondo (m)', { v: '20.00' })
          ])
        ] },
        { label: 'Datos Proyecto', sections: [
          S('Proyecto', [
            T('Uso de la edificación', { t: 'sel', v: 'VIVIENDA UNIFAMILIAR', opts: ['VIVIENDA UNIFAMILIAR', 'VIVIENDA MULTIFAMILIAR', 'COMERCIO', 'INDUSTRIA', 'SERVICIOS'] }),
            T('Nº de pisos', { v: '3' }),
            T('Área techada total (m²)', { v: '186.00' }),
            T('Área libre (m²)', { v: '84.00' }),
            T('Nº de estacionamientos', { v: '1' }),
            T('Valor de obra (S/)', { v: '148,200.00' }),
            T('Tasa de licencia', { t: 'ro', v: '1.0 %' }),
            T('Derecho de licencia (S/)', { t: 'ro', v: '1,482.00' }),
            T('Plazo de ejecución (meses)', { v: '36' })
          ])
        ] },
        { label: 'Proyectistas', sections: [
          S('Profesionales responsables', [
            T('Proyectista de arquitectura', { v: 'ARQ. C. ZAPATA RUIZ' }),
            T('Colegiatura (CAP)', { v: '18442' }),
            T('Proyectista de estructuras', { v: 'ING. M. SANDOVAL CRUZ' }),
            T('Colegiatura (CIP)', { v: '92118' }),
            T('Proyectista de instalaciones', { v: 'ING. R. FLORES NIMA' }),
            T('Responsable de obra', { v: 'ING. M. SANDOVAL CRUZ' })
          ])
        ] },
        { label: 'Documentos Adjuntos', sections: [
          S('Requisitos', [
            T('FUE firmado por el solicitante', { t: 'chk', on: true, ph: 'Presentado' }),
            T('Copia literal de dominio', { t: 'chk', on: true, ph: 'Presentada' }),
            T('Planos de arquitectura', { t: 'chk', on: true, ph: 'Presentados' }),
            T('Planos de estructuras', { t: 'chk', on: true, ph: 'Presentados' }),
            T('Planos de instalaciones', { t: 'chk', on: false, ph: 'Pendiente' }),
            T('Certificado de parámetros urbanísticos', { t: 'chk', on: true, ph: 'Presentado' }),
            T('Recibo de pago del derecho', { t: 'chk', on: false, ph: 'Pendiente' })
          ])
        ] }
      ],
      actions: ['Nuevo', 'Inactivar', 'Excel', 'Imprimir', 'Guardar']
    },

    ciiu: {
      mod: 'Licencias', title: 'Catálogo CIIU de giros',
      endpoint: 'GET /api/v1/licencias/ciiu',
      desc: 'Clasificación industrial internacional uniforme. Determina la compatibilidad del giro con la zonificación y el nivel de riesgo de la ITSE.',
      filters: [T('Código CIIU', { v: '' }), T('Descripción', { v: '' }), T('Sección', { t: 'sel', v: 'Todas', opts: ['Todas', 'D — INDUSTRIAS MANUFACTURERAS', 'G — COMERCIO', 'H — HOTELES Y RESTAURANTES', 'I — TRANSPORTE', 'K — ACTIVIDADES INMOBILIARIAS'] })],
      table: {
        title: 'Giros registrados', count: '6 de 1,842',
        cols: ['Código', 'Descripción', 'Sección', 'Riesgo ITSE', 'Zonificación compatible', 'Requiere sectorial'],
        rows: [
          ['D-1549-19', 'RESTAURANTE-POLLERÍA', 'D', ['Medio', 'warn'], 'CV, CZ', 'No'],
          ['G-5211-01', 'VENTA AL POR MENOR EN ALMACENES', 'G', ['Bajo', 'ok'], 'CV, CZ, RDM', 'No'],
          ['G-5234-01', 'VENTA DE MATERIALES DE CONSTRUCCIÓN', 'G', ['Medio', 'warn'], 'CZ, I1', 'No'],
          ['H-5520-02', 'SERVICIO DE RESTAURANTES A DOMICILIO', 'H', ['Bajo', 'ok'], 'CV, CZ', 'No'],
          ['H-5520-63', 'CHIFA AL PASO', 'H', ['Medio', 'warn'], 'CV, CZ', 'No'],
          ['N-8511-01', 'ACTIVIDADES DE HOSPITALES Y CLÍNICAS', 'N', ['Alto', 'bad'], 'OU, CZ', 'Sí — MINSA']
        ]
      },
      actions: ['Nuevo', 'Guardar']
    },

    certificados: {
      mod: 'Licencias', title: 'Certificados de numeración y zonificación',
      endpoint: 'POST /api/v1/licencias/certificados',
      desc: 'Emisión de los certificados que acreditan el número municipal asignado y los parámetros urbanísticos del predio.',
      filters: [T('Nº de certificado', { v: '' }), T('Tipo', { t: 'sel', v: 'Todos', opts: ['Todos', 'NUMERACIÓN', 'ZONIFICACIÓN Y VÍAS', 'PARÁMETROS URBANÍSTICOS', 'JURISDICCIÓN'] }), T('Predio', { v: '' })],
      table: {
        title: 'Certificados emitidos', count: '3 de 1,184',
        cols: ['Nº certificado', 'Tipo', 'Predio', 'Solicitante', 'Fecha', 'Derecho S/', 'Estado'], num: [5],
        rows: [
          ['CN-2026-00418', 'NUMERACIÓN', '02-014-D-14-01', 'MEDINA MEDINA, RUFINA (SUC.)', '04/08/2026', '42.00', ['Emitido', 'ok']],
          ['CZ-2026-00212', 'ZONIFICACIÓN Y VÍAS', '03-088-A-01-00', 'INVERSIONES DEL NORTE SAC', '28/07/2026', '86.00', ['Emitido', 'ok']],
          ['CP-2026-00188', 'PARÁMETROS URBANÍSTICOS', '02-014-D-14-01', 'VALDEZ RIOS, OLIVER F.', '12/08/2026', '112.00', ['En trámite', 'warn']]
        ]
      },
      sections: [
        S('Datos del certificado', [
          T('Tipo de certificado', { t: 'sel', v: 'PARÁMETROS URBANÍSTICOS', opts: ['NUMERACIÓN', 'ZONIFICACIÓN Y VÍAS', 'PARÁMETROS URBANÍSTICOS', 'JURISDICCIÓN'] }),
          T('Código predial', { v: '02-014-D-14-01' }),
          T('Solicitante', { v: 'VALDEZ RIOS, OLIVER FABIÁN' }),
          T('Nº de expediente', { v: '2026-0944' }),
          T('Zonificación', { t: 'ro', v: 'RDM — RESIDENCIAL DENSIDAD MEDIA' }),
          T('Altura máxima permitida', { t: 'ro', v: '3 pisos' }),
          T('Área libre mínima', { t: 'ro', v: '30 %' }),
          T('Retiro municipal', { t: 'ro', v: '2.00 m' }),
          T('Coeficiente de edificación', { t: 'ro', v: '2.1' }),
          T('Derecho de trámite (S/)', { t: 'ro', v: '112.00' }),
          T('Vigencia', { t: 'ro', v: '36 meses' })
        ])
      ],
      actions: ['Emitir', 'Imprimir certificado']
    },

    /* ── SEGURIDAD ────────────────────────────────────────── */

    usuarios: {
      mod: 'Seguridad', title: 'Usuarios del sistema',
      endpoint: 'GET /api/v1/seguridad/usuarios',
      desc: 'Alta de usuarios con su unidad orgánica, la caja asignada y el grupo de acceso que define qué opciones del menú puede ejecutar.',
      filters: [T('Usuario', { v: '' }), T('Unidad orgánica', { t: 'sel', v: 'Todas', opts: ['Todas', 'UNIDAD DE RENTAS', 'TESORERÍA', 'FISCALIZACIÓN', 'EJECUTORÍA COACTIVA', 'COMERCIALIZACIÓN'] }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'ACTIVA', 'BLOQUEADA', 'INACTIVA'] })],
      table: {
        title: 'Usuarios registrados', count: '5 de 68',
        cols: ['Usuario', 'Nombre', 'Unidad orgánica', 'Grupo', 'Caja', 'Último acceso', 'Estado'],
        rows: [
          ['jcardenas', 'CÁRDENAS VEGA, JOSÉ', 'TESORERÍA', 'CAJERO', 'C-3', '13/08/2026 08:00', ['ACTIVA', 'ok']],
          ['mrios', 'RÍOS PALACIOS, MARIELA', 'UNIDAD DE RENTAS', 'ANALISTA', '—', '13/08/2026 07:52', ['ACTIVA', 'ok']],
          ['rmendoza', 'MENDOZA CRUZ, RICARDO', 'EJECUTORÍA COACTIVA', 'EJECUTOR', '—', '12/08/2026 17:20', ['ACTIVA', 'ok']],
          ['lpena', 'PEÑA SANDOVAL, LUIS', 'FISCALIZACIÓN', 'FISCALIZADOR', '—', '02/07/2026 12:11', ['BLOQUEADA', 'bad']],
          ['ehurtado', 'HURTADO CHERO, ELENA', 'COMERCIALIZACIÓN', 'ADMINISTRADORES', '—', '13/08/2026 09:04', ['ACTIVA', 'ok']]
        ]
      },
      sections: [
        S('Cuenta', [
          T('Usuario', { v: 'jcardenas' }),
          T('Nombre completo', { v: 'CÁRDENAS VEGA, JOSÉ' }),
          T('D.N.I.', { v: '02718844' }),
          T('Cargo', { v: 'CAJERO DE VENTANILLA' }),
          T('Unidad orgánica', { t: 'sel', v: 'TESORERÍA', opts: ['UNIDAD DE RENTAS', 'TESORERÍA', 'FISCALIZACIÓN', 'EJECUTORÍA COACTIVA', 'COMERCIALIZACIÓN'] }),
          T('Grupo', { t: 'sel', v: 'CAJERO', opts: ['ADMINISTRADORES', 'EJECUCION PO', 'PLAN', 'PLANEAMIENTO', 'CAJERO', 'ORIENTADOR', 'ANALISTA', 'FISCALIZADOR', 'EJECUTOR', 'CONSULTA'] }),
          T('Caja asignada', { t: 'sel', v: 'C-3', opts: ['—', 'C-1', 'C-2', 'C-3', 'C-4'] }),
          T('Estado', { t: 'sel', v: 'ACTIVA', opts: ['ACTIVA', 'BLOQUEADA', 'INACTIVA'] }),
          T('Vencimiento de contraseña', { t: 'date', v: '2026-11-30' }),
          T('Obliga cambio en el próximo acceso', { t: 'chk', on: false, ph: 'Forzar cambio de contraseña' })
        ])
      ],
      actions: ['Nuevo', 'Restablecer contraseña', 'Eliminar', 'Guardar']
    },

    grupos: {
      mod: 'Seguridad', title: 'Grupos de usuarios',
      endpoint: 'GET /api/v1/seguridad/grupos',
      desc: 'Agrupación jerárquica de cuentas. El grupo concentra los accesos y todo usuario hereda los permisos del grupo al que pertenece.',
      filters: [T('Grupo', { v: '' }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'ACTIVA', 'INACTIVA'] })],
      table: {
        title: 'Grupos y usuarios — SIGAM', count: '4 grupos · 68 usuarios',
        cols: ['Grupo', 'Descripción', 'Usuarios', 'Accesos asignados', 'Estado'], num: [2, 3],
        rows: [
          ['ADMINISTRADORES', 'Acceso total al sistema', '6', '184', ['ACTIVA', 'ok']],
          ['EJECUCION PO', 'Ejecución presupuestal', '4', '42', ['ACTIVA', 'ok']],
          ['PLAN', 'Planificación', '3', '38', ['ACTIVA', 'ok']],
          ['PLANEAMIENTO', 'Presupuesto y planeamiento', '2', '21', ['ACTIVA', 'ok']]
        ],
        note: 'Los usuarios del grupo ADMINISTRADORES son aayca, ehurtado, fruiz, iquispep y vrosales.'
      },
      sections: [
        S('Datos del grupo', [
          T('Nombre del grupo', { v: 'PLANEAMIENTO' }),
          T('Descripción', { v: 'Presupuesto y planeamiento', wide: 1 }),
          T('Grupo padre', { t: 'sel', v: 'SIGAM', opts: ['SIGAM', 'ADMINISTRADORES', '—'] }),
          T('Estado', { t: 'sel', v: 'ACTIVA', opts: ['ACTIVA', 'INACTIVA'] })
        ])
      ],
      actions: ['Nuevo', 'Eliminar', 'Guardar']
    },

    permisos: {
      mod: 'Seguridad', title: 'Permisos y niveles de accesibilidad',
      endpoint: 'PUT /api/v1/seguridad/grupos/{id}/permisos',
      desc: 'Matriz de acceso por opción del menú. Cada acceso se otorga con siete niveles: total, ejecuta, consulta, ingresa, modifica, anula e imprime.',
      filters: [
        T('Buscar por', { t: 'sel', v: 'Grupo', opts: ['Grupo', 'Usuario'] }),
        T('Grupo / Usuario', { v: 'PLANEAMIENTO' }),
        T('Acceso', { t: 'sel', v: 'Todos', opts: ['Todos', 'MENU SIGAM', 'Archivo - Cambiar el Año', 'Presupuesto', 'Patrimonio - Catálogo de Bienes'] })
      ],
      table: {
        title: 'Permisos entre usuarios y accesos', count: '6 de 21 accesos',
        cols: ['Usuario', 'Acceso', 'Total', 'Ejecuta', 'Consulta', 'Ingresa', 'Modifica', 'Anula', 'Imprime', 'Estado'],
        rows: [
          ['PLANEAM…', 'Archivo - Cambiar el Año', ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['ACTIVA', 'ok']],
          ['PLANEAM…', 'Archivo - Cambiar Usuario', ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['ACTIVA', 'ok']],
          ['PLANEAM…', 'MENU SIGAM', ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['ACTIVA', 'ok']],
          ['PLANEAM…', 'Caja tributaria', ['No', 'bad'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['No', 'bad'], ['No', 'bad'], ['Sí', 'ok'], ['ACTIVA', 'ok']],
          ['PLANEAM…', 'Cierre de caja', ['No', 'bad'], ['Sí', 'ok'], ['Sí', 'ok'], ['Sí', 'ok'], ['No', 'bad'], ['No', 'bad'], ['Sí', 'ok'], ['ACTIVA', 'ok']],
          ['PLANEAM…', 'Contribuyentes', ['No', 'bad'], ['No', 'bad'], ['Sí', 'ok'], ['No', 'bad'], ['No', 'bad'], ['No', 'bad'], ['No', 'bad'], ['ACTIVA', 'ok']]
        ],
        actions: ['Agregar', 'Agregar Todo'],
        note: 'Los cambios se aplican en el siguiente inicio de sesión de los usuarios del grupo.'
      },
      actions: ['Nuevo', 'Eliminar', 'Imprimir', 'Guardar']
    },

    cambiar_anio: {
      mod: 'Seguridad', title: 'Cambiar el año de trabajo',
      endpoint: 'PUT /api/v1/seguridad/sesion/ejercicio',
      desc: 'Fija el ejercicio sobre el que operan todas las opciones del sistema. Los registros se graban contra el año seleccionado.',
      sections: [
        S('Ejercicio de trabajo', [
          T('Año actual de la sesión', { t: 'ro', v: '2026' }),
          T('Cambiar al año', { t: 'sel', v: '2026', opts: yrs }),
          T('Ejercicio contable abierto', { t: 'ro', v: '2026' }),
          T('Último cierre ejecutado', { t: 'ro', v: '31/12/2025' }),
          T('Advertencia', { t: 'ro', v: 'Cambiar de año afecta a todas las pantallas abiertas', wide: 1 })
        ])
      ],
      actions: ['Cancelar', 'Aceptar']
    },

    cambiar_clave: {
      mod: 'Seguridad', title: 'Cambiar contraseña',
      endpoint: 'PUT /api/v1/seguridad/usuarios/{id}/clave',
      desc: 'Cambio de la clave del usuario en sesión. La contraseña caduca cada 90 días y no puede repetir las tres últimas.',
      sections: [
        S('Credenciales', [
          T('Usuario', { t: 'ro', v: 'jcardenas' }),
          T('Contraseña actual', { ph: '••••••••' }),
          T('Nueva contraseña', { ph: 'Mínimo 8 caracteres' }),
          T('Confirmar nueva contraseña', { ph: 'Repita la contraseña' }),
          T('Vencimiento actual', { t: 'ro', v: '30/11/2026' }),
          T('Requisitos', { t: 'ro', v: 'Ocho caracteres, una mayúscula, un número', wide: 1 })
        ])
      ],
      actions: ['Cancelar', 'Aceptar']
    },

    auditoria: {
      mod: 'Seguridad', title: 'Auditoría del sistema',
      endpoint: 'GET /api/v1/seguridad/auditoria',
      desc: 'Bitácora de operaciones sensibles: anulaciones, extornos, bajas de deuda, cambios de valor y accesos fallidos.',
      filters: [T('Usuario', { t: 'sel', v: 'Todos', opts: ['Todos', 'jcardenas', 'mrios', 'rmendoza', 'lpena'] }), T('Acción', { t: 'sel', v: 'Todas', opts: ['Todas', 'ALTA', 'MODIFICACIÓN', 'ELIMINACIÓN', 'ANULACIÓN', 'ACCESO'] }), T('Desde', { t: 'date', v: '2026-08-01' }), T('Hasta', { t: 'date', v: '2026-08-13' })],
      table: {
        title: 'Movimientos registrados', count: '5 de 18,442',
        cols: ['Fecha y hora', 'Usuario', 'Opción', 'Acción', 'Registro afectado', 'Terminal / IP'],
        rows: [
          ['12/08/2026 09:41', 'jcardenas', 'Anulación de recibo', ['Anulación', 'bad'], 'Recibo 0003-0041184', 'PC-CAJA3 · 10.0.2.43'],
          ['12/08/2026 09:14', 'jcardenas', 'Caja tributaria', ['Alta', 'ok'], 'Recibo 0003-0041182', 'PC-CAJA3 · 10.0.2.43'],
          ['12/08/2026 08:22', 'mrios', 'Baja de deuda', ['Eliminación', 'bad'], 'RGAT-0244-2026-MPS', 'PC-RENT2 · 10.0.1.18'],
          ['11/08/2026 17:20', 'rmendoza', 'Expedientes coactivos', ['Modificación', 'warn'], 'EC-2026-00412', 'PC-COAC1 · 10.0.2.88'],
          ['11/08/2026 08:02', 'lpena', 'Acceso al sistema', ['Acceso fallido', 'bad'], '3 intentos', 'TAB-FISC2 · 10.0.4.12']
        ]
      },
      actions: ['Excel', 'Imprimir bitácora']
    },

    parametros: {
      mod: 'Seguridad', title: 'Parámetros del sistema',
      endpoint: 'GET /api/v1/seguridad/parametros',
      desc: 'Valores que gobiernan el cálculo tributario del ejercicio. Cambiarlos afecta a todas las liquidaciones posteriores.',
      sections: [
        S('Entidad y ejercicio', [
          T('Entidad', { t: 'ro', v: 'MUNICIPALIDAD PROVINCIAL DE SULLANA' }),
          T('R.U.C. de la entidad', { t: 'ro', v: '20146114677' }),
          T('Ejercicio vigente', { t: 'sel', v: '2026', opts: yrs }),
          T('UIT del ejercicio (S/)', { v: '5,350.00' }),
          T('Fecha de cierre del ejercicio anterior', { t: 'date', v: '2025-12-31' })
        ]),
        S('Tasas e intereses', [
          T('TIM mensual (%)', { v: '0.90' }),
          T('Interés de fraccionamiento mensual (%)', { v: '0.80' }),
          T('Índice de precios al por mayor (IPM)', { v: '1.0206' }),
          T('Derecho de emisión predial (S/)', { v: '4.50' }),
          T('Costas coactivas (% de la deuda)', { v: '10.00' }),
          T('Descuento por pronto pago (%)', { v: '10.00' }),
          T('Monto mínimo de emisión de valores (S/)', { v: '50.00' })
        ]),
        S('Vencimientos del ejercicio', [
          T('Cuota 1', { t: 'date', v: '2026-02-28' }),
          T('Cuota 2', { t: 'date', v: '2026-05-31' }),
          T('Cuota 3', { t: 'date', v: '2026-08-31' }),
          T('Cuota 4', { t: 'date', v: '2026-11-30' }),
          T('Vencimiento de la declaración jurada anual', { t: 'date', v: '2026-02-28' })
        ])
      ],
      actions: ['Deshacer', 'Guardar parámetros']
    },

    respaldo: {
      mod: 'Seguridad', title: 'Copias de seguridad',
      endpoint: 'POST /api/v1/seguridad/respaldos',
      desc: 'Respaldo de la base de datos. El manual exige una copia diaria al cierre de caja y una copia mensual fuera del servidor.',
      filters: [T('Desde', { t: 'date', v: '2026-08-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Tipo', { t: 'sel', v: 'Todos', opts: ['Todos', 'DIARIA', 'MENSUAL', 'MANUAL'] })],
      table: {
        title: 'Respaldos ejecutados', count: '4 de 218',
        cols: ['Fecha y hora', 'Tipo', 'Tamaño', 'Destino', 'Ejecutado por', 'Estado'],
        rows: [
          ['12/08/2026 19:00', 'DIARIA', '4.82 GB', '\\\\SRV-BK01\\sgtm', 'Automático', ['Correcta', 'ok']],
          ['11/08/2026 19:00', 'DIARIA', '4.81 GB', '\\\\SRV-BK01\\sgtm', 'Automático', ['Correcta', 'ok']],
          ['31/07/2026 22:00', 'MENSUAL', '4.78 GB', 'Unidad externa', 'ehurtado', ['Correcta', 'ok']],
          ['10/08/2026 19:00', 'DIARIA', '0.00 GB', '\\\\SRV-BK01\\sgtm', 'Automático', ['Fallida', 'bad']]
        ],
        note: 'El respaldo del 10/08 falló por falta de espacio en el servidor de destino.'
      },
      sections: [
        S('Nuevo respaldo', [
          T('Tipo', { t: 'sel', v: 'MANUAL', opts: ['DIARIA', 'MENSUAL', 'MANUAL'] }),
          T('Destino', { v: '\\\\SRV-BK01\\sgtm' }),
          T('Comprimir', { t: 'chk', on: true, ph: 'Comprime el archivo resultante' }),
          T('Verificar al terminar', { t: 'chk', on: true, ph: 'Comprueba la integridad de la copia' })
        ])
      ],
      actions: ['Restaurar', 'Ejecutar respaldo']
    },

    /* ── INICIO Y PORTAL ──────────────────────────────────── */

    inicio: {
      mod: 'Inicio', title: 'Panel de recaudación',
      endpoint: 'GET /api/v1/indicadores/recaudacion?ejercicio=2026',
      kind: 'dash',
      desc: 'Avance de la recaudación del ejercicio 2026 al 13 de agosto, con la cartera pendiente por tributo y las tareas abiertas de cada unidad.',
      kpis: [
        { label: 'Recaudado 2026', value: 'S/ 18.42 M', note: '77.6 % de lo emitido' },
        { label: 'Predial del mes', value: 'S/ 1.94 M', note: '+12.8 % vs. julio' },
        { label: 'Cartera morosa', value: 'S/ 26.71 M', note: '38,412 contribuyentes' },
        { label: 'Emitidos hoy', value: '412', note: 'recibos · 9 cajas activas' }
      ],
      panels: [
        { title: 'Recaudación por tributo', note: 'Ejercicio 2026', rows: [
          { label: 'Impuesto predial', sub: '24,118 contribuyentes', value: 'S/ 8.42 M', pct: 89 },
          { label: 'Arbitrios municipales', sub: 'Limpieza, parques, serenazgo', value: 'S/ 5.11 M', pct: 87 },
          { label: 'Patrimonio vehicular', sub: '3,204 vehículos afectos', value: 'S/ 1.88 M', pct: 65 },
          { label: 'Alcabala', sub: '612 transferencias', value: 'S/ 1.42 M', pct: 100 },
          { label: 'Multas y papeletas', sub: 'Tránsito e infracciones', value: 'S/ 1.59 M', pct: 39 }
        ] },
        { title: 'Pendientes por unidad', note: 'Requieren acción', rows: [
          { label: 'Valores por notificar', sub: 'Órdenes de pago y RD', value: '1,284', pct: 74 },
          { label: 'Expedientes coactivos activos', sub: 'Ejecutor: R. Mendoza', value: '318', pct: 45 },
          { label: 'Fiscalizaciones en campo', sub: 'Sectores 02 y 04', value: '96', pct: 28 },
          { label: 'Convenios en riesgo', sub: '2 cuotas vencidas', value: '141', pct: 52 },
          { label: 'Licencias por resolver', sub: 'Funcionamiento y edificación', value: '73', pct: 21 }
        ] }
      ]
    },

    portal: {
      mod: 'Portal ciudadano', title: 'Consulta y pago en línea',
      endpoint: 'GET /api/v1/portal/deuda?doc=44218937',
      kind: 'portal',
      desc: 'Flujo público de autoconsulta: el contribuyente identifica su deuda, elige qué pagar y descarga su constancia sin acudir a la municipalidad.',
      steps: ['Identificarte', 'Revisar tu deuda', 'Elegir qué pagar', 'Pagar', 'Descargar constancia'],
      table: {
        title: 'Tu deuda al 13 de agosto de 2026', count: 'CASTILLO PASCUALA, MARÍA ELENA',
        cols: ['', 'Concepto', 'Periodo', 'Vencimiento', 'Insoluto S/', 'Interés S/', 'Total S/'], num: [4, 5, 6],
        rows: [
          ['✓', 'Impuesto predial — cuota 2', '2026', '31/05/2026', '146.86', '6.96', '153.82'],
          ['✓', 'Arbitrios municipales', '2026', 'Mensual', '486.00', '25.64', '511.64'],
          ['', 'Impuesto predial — cuota 3', '2025', '31/08/2025', '144.20', '51.78', '195.98'],
          ['', 'Patrimonio vehicular T2G-418', '2024', '28/02/2024', '614.00', '326.64', '940.64']
        ],
        note: 'Las deudas en cobranza coactiva deben regularizarse en la Ejecutoría Coactiva y no pueden pagarse por este canal.'
      },
      totals: [
        { label: 'Seleccionado', value: 'S/ 665.46' },
        { label: 'Descuento pronto pago', value: '− S/ 25.40' },
        { label: 'Total a pagar', value: 'S/ 640.06', strong: 1 }
      ],
      sections: [
        S('Pago en línea', [
          T('Medio de pago', { t: 'sel', v: 'Tarjeta de débito o crédito', opts: ['Tarjeta de débito o crédito', 'Yape / Plin', 'Banca por internet', 'Agente autorizado'] }),
          T('Correo para el comprobante', { v: 'mcastillo@correo.pe' }),
          T('Celular', { v: '969 442 118' }),
          T('Acepto los términos del pago electrónico', { t: 'chk', on: true, ph: 'Requerido para continuar' })
        ])
      ],
      actions: ['Descargar estado de cuenta', 'Pagar S/ 640.06']
    }

  });

  /* ── NAVEGACIÓN ─────────────────────────────────────────── */
  window.SGTM_NAV = [
    { label: 'Inicio', items: [['inicio', 'Panel de recaudación'], ['portal', 'Portal ciudadano']] },
    { label: 'Catastro', items: [
      ['ficha_urbana', 'Ficha urbana individual'], ['ficha_economica', 'Ficha económica'],
      ['ficha_bienes', 'Bienes comunes'], ['ficha_rural', 'Ficha rural'],
      ['consulta_fichas', 'Consulta de fichas'], ['actualizacion_catastro', 'Actualización del catastro'],
      ['ficha_contribuyente_reporte', 'Reporte de ficha del contribuyente'],
      ['calles', 'Vías y calles'],
      ['sectores', 'Sectores y manzanas'], ['aranceles', 'Aranceles'],
      ['valores_unitarios', 'Valores unitarios'], ['depreciacion', 'Depreciación']
    ] },
    { label: 'Rentas · Registro', items: [
      ['contribuyentes', 'Contribuyentes'], ['predios_rentas', 'Predios'],
      ['predial_individual', 'Predial — individual'], ['predial_masivo', 'Predial — masivo'],
      ['declaracion_jurada', 'Declaración jurada'], ['arbitrios', 'Arbitrios'],
      ['transferencia_predio', 'Transferencia de predio'], ['alcabala', 'Alcabala'],
      ['vehiculos', 'Vehículos'], ['vehicular_calculo', 'Cálculo vehicular'],
      ['transferencia_vehiculo', 'Transferencia de vehículo'], ['espectaculos', 'Espectáculos públicos'],
      ['beneficios', 'Beneficios'], ['alta_deuda', 'Alta de deuda'], ['baja_deuda', 'Baja de deuda']
    ] },
    { label: 'Fiscalización', items: [
      ['fisc_programa', 'Programación'], ['fisc_predial', 'Fiscalización predial'],
      ['fisc_vehicular', 'Fiscalización vehicular'], ['fisc_resultados', 'Resultados'],
      ['fisc_omisos', 'Omisos y subvaluadores'],
      ['fisc_estado_cuenta', 'Estado de cuenta de fiscalización'],
      ['fisc_historico', 'Histórico de fiscalización predial'],
      ['resolucion_determinacion_fisc', 'Resolución de determinación']
    ] },
    { label: 'Tránsito', items: [
      ['papeletas', 'Papeletas'], ['transito_busqueda', 'Búsqueda de infracciones'],
      ['codigos_transito', 'Códigos de tránsito'], ['transito_descargos', 'Descargos'],
      ['internamiento', 'Internamiento vehicular'],
      ['transito_documentos', 'Resoluciones y documentos'],
      ['transito_valores', 'Generación de valores'],
      ['transito_cambio_numero', 'Cambio de nº de papeleta'],
      ['transito_reportes', 'Reportes de tránsito'],
      ['transito_record_conductor', 'Record de conductor'],
      ['transito_record_vehicular', 'Record vehicular'],
      ['transito_constancia_libre', 'Constancia libre de infracciones'],
      ['transito_padron', 'Padrón de papeletas'],
      ['transito_estado_cuenta', 'Estado de cuenta de infracciones'],
      ['transito_papeleta_reporte', 'Reporte de papeleta'],
      ['transito_rg_ordinaria', 'Res. de gerencia ordinaria'],
      ['transito_rg_sancionadora', 'Res. de gerencia sancionadora'],
      ['transito_padron_coactiva', 'Padrón enviadas a coactiva'],
      ['transito_padron_constancias', 'Padrón de constancias'],
      ['transito_resumen_recaudacion', 'Resumen de recaudación'],
      ['transito_resumen_papeletas', 'Resumen de papeletas'],
      ['transito_resumen_codigo', 'Resumen por código'],
      ['transito_resumen_placa', 'Resumen por iniciales de placa']
    ] },
    { label: 'Infracciones administrativas', items: [
      ['adm_notificacion', 'Notificación administrativa'],
      ['infracciones_adm', 'Infracción administrativa'],
      ['codigos_cuis', 'Cuadro CUIS'], ['adm_codigos_reporte', 'Reporte de códigos'],
      ['adm_valores', 'Generación de valores'],
      ['adm_estado_cuenta', 'Estado de cuenta de papeleta'],
      ['adm_resolucion_gerencia', 'Resolución de gerencia'],
      ['adm_notificacion_resolucion', 'Notificación de resolución'],
      ['adm_reportes', 'Reportes administrativos'],
      ['adm_padron_notificaciones', 'Padrón de notificaciones'],
      ['adm_notificaciones_vencidas', 'Notificaciones vencidas'],
      ['adm_notificaciones_contribuyente', 'Notificaciones por contribuyente'],
      ['adm_resumen_recaudacion', 'Resumen de recaudación']
    ] },
    { label: 'Tesorería', items: [
      ['caja_tributaria', 'Caja tributaria'], ['caja_tasas', 'Caja de tasas'],
      ['fraccionamiento', 'Fraccionamiento'], ['consulta_convenios', 'Convenios'],
      ['duplicado_recibo', 'Duplicado de recibo'], ['anulacion_recibo', 'Anulación de recibo'],
      ['anulacion_convenio', 'Anulación de convenio'],
      ['cierre_caja', 'Cierre de caja'], ['avance_recaudacion', 'Avance de recaudación'],
      ['recaudacion_area', 'Recaudación por área']
    ] },
    { label: 'Consultas', items: [
      ['cuenta_corriente', 'Cuenta corriente'], ['consulta_deuda', 'Deuda'],
      ['consulta_unificada', 'Unificada predial-arbitrios'],
      ['consulta_resumen_predial', 'Resumen predial-arbitrios'],
      ['consulta_altas_bajas', 'Altas y bajas'],
      ['consulta_deudas_beneficio', 'Deudas con beneficio'],
      ['consulta_pagos', 'Pagos'], ['consulta_predios', 'Predios'],
      ['consulta_vehiculos', 'Vehículos'], ['consulta_valores', 'Valores'],
      ['constancia', 'Constancia de no adeudo']
    ] },
    { label: 'Valores', items: [
      ['valores_individual', 'Valor individual'], ['valores_masivo', 'Valores masivos'],
      ['valores_busqueda', 'Mantenimiento de valores'], ['notificacion_valores', 'Notificación'],
      ['prescripcion', 'Prescripción'], ['pase_coactiva', 'Pase de valores a coactiva']
    ] },
    { label: 'Coactiva', items: [
      ['coactiva_expedientes', 'Expedientes coactivos'],
      ['importacion_valores', 'Importación de valores'],
      ['proceso_coactivo', 'Proceso coactivo'],
      ['rec_impresion', 'Impresión de REC'],
      ['expediente_historial', 'Historial del expediente'],
      ['cambiar_direccion_ref', 'Cambiar dirección referencial'],
      ['costas_procesales', 'Liquidación de costas'],
      ['fraccionamiento_coactivo', 'Fraccionamiento coactivo'],
      ['actos_coactivos', 'Actos coactivos'],
      ['notificaciones_coactivas', 'Notificaciones coactivas'],
      ['coactiva_consulta_deudas', 'Consulta de deudas'],
      ['coactiva_deudas_beneficio', 'Deudas en beneficio']
    ] },
    { label: 'Autorizaciones y licencias', items: [
      ['anuncios', 'Anuncio y propaganda'], ['anuncios_reportes', 'Reportes de anuncios'],
      ['licencia_funcionamiento', 'Licencia de funcionamiento'],
      ['licencia_padron', 'Padrón de licencias'],
      ['licencia_resumen_anual', 'Resumen de licencias por año'],
      ['licencia_resolucion_cancelacion', 'Res. de cancelación'],
      ['licencia_resolucion_duplicado', 'Res. de duplicado'],
      ['fue_edificacion', 'FUE — edificación'],
      ['edificacion_reporte', 'Reporte de licencias de edificación'],
      ['ciiu', 'Catálogo CIIU'], ['certificados', 'Certificados']
    ] },
    { label: 'Seguridad', items: [
      ['modulos', 'Módulos'], ['usuarios', 'Usuarios'], ['grupos', 'Grupos'],
      ['accesos', 'Accesos y políticas'], ['miembros', 'Miembros'], ['permisos', 'Permisos'],
      ['cambiar_anio', 'Cambiar el año'], ['cambiar_clave', 'Cambiar contraseña'],
      ['auditoria', 'Auditoría'], ['parametros', 'Parámetros'], ['respaldo', 'Copias de seguridad']
    ] }
  ];
})();
