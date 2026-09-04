/* SGTM — catálogo de pantallas (complemento 1): actualización catastral,
   fiscalización, tránsito e infracciones administrativas.
   Campos tomados del Manual de Usuario SGTM (figuras 012, 043, 075-114). */
(function () {
  var T = function (label, o) { return Object.assign({ label: label, t: 'text', v: '', ph: '', opts: null, wide: 0, on: false }, o || {}); };
  var S = function (label, fields, hint) { return { label: label, fields: fields, hint: hint || '' }; };
  var W = window.SGTM_SCREENS = window.SGTM_SCREENS || {};
  var yrs = ['2026', '2025', '2024', '2023', '2022', '2021', '2020'];
  var estDeuda = ['(TODOS)', 'PENDIENTE', 'A CUENTA', 'CANCELADA', 'FRACCIONADA', 'ANULADA'];
  var tipoPap = ['TRIBUTARIA', 'P. TRÁNSITO', 'P. ADMINISTRATIVA'];

  Object.assign(W, {

    /* ── CATASTRO ─────────────────────────────────────────── */

    actualizacion_catastro: {
      mod: 'Catastro', title: 'Actualización del catastro',
      endpoint: 'PUT /api/v1/catastro/fichas/{codigo}/actualizacion',
      desc: 'Actualiza construcciones y otras instalaciones de una ficha ya registrada. El sistema conserva cada versión declarada y verificada por piso, con su MEP, ECS, ECC y estado de conservación.',
      filters: [T('Cod. Ref. Catastral', { v: '200601010150010101001' }), T('Nº de ficha', { v: '' }), T('Sector', { t: 'sel', v: 'Todos', opts: ['Todos', '01', '02', '03', '04', '05'] }), T('Tipo de actualización', { t: 'sel', v: 'INDIVIDUAL', opts: ['INDIVIDUAL', 'MASIVA POR SECTOR', 'POR NOTIFICACIÓN', 'POR FISCALIZACIÓN'] })],
      tabs: [
        { label: 'Construcción', sections: [
          S('Características de construcción', [
            T('Nº Piso', { v: '01' }), T('Mes', { v: '01' }), T('Año', { v: '1986' }),
            T('MEP', { t: 'sel', v: '02 — LADRILLO', opts: ['01 — CONCRETO', '02 — LADRILLO', '03 — ADOBE / TAPIA', '04 — QUINCHA', '05 — MADERA', '99 — NO ESPECIFICADO'] }),
            T('ECS', { t: 'sel', v: '03 — REGULAR', opts: ['01 — MUY BUENO', '02 — BUENO', '03 — REGULAR', '04 — MALO', '05 — MUY MALO'] }),
            T('ECC', { t: 'sel', v: '03 — TERMINADO', opts: ['01 — EN CONSTRUCCIÓN', '02 — INCONCLUSO', '03 — TERMINADO', '04 — EN RUINAS'] }),
            T('UCA', { t: 'sel', v: '99 — NO ESPECIFICADO', opts: ['01 — CASA HABITACIÓN', '02 — TIENDA / DEPÓSITO', '03 — EDIFICIO', '99 — NO ESPECIFICADO'] })
          ]),
          S('Acabados (categorías)', [
            T('Muros', { v: 'C' }), T('Techos', { v: 'F' }), T('Pisos', { v: 'I' }),
            T('Puertas', { v: 'H' }), T('Revest.', { v: 'I' }), T('Baños', { v: 'F' }),
            T('Instalaciones eléctricas', { v: 'H' })
          ]),
          S('Área construida', [
            T('Declarada (m²)', { v: '0.000' }), T('Verificada (m²)', { v: '40.000' }),
            T('Legal — terreno (m²)', { t: 'ro', v: '0.00' }), T('Legal — construcción (m²)', { t: 'ro', v: '0.00' }),
            T('Físico — terreno (m²)', { t: 'ro', v: '0.00' }), T('Físico — construcción (m²)', { t: 'ro', v: '0.00' })
          ])
        ] },
        { label: 'Otras instalaciones', sections: [
          S('Instalación', [
            T('Código', { t: 'ro', v: '00033 — PORTÓN DE FIERRO (2.50 ML) F2"' }),
            T('Mes', { v: '01' }), T('Año', { v: '2006' }),
            T('MEP', { t: 'sel', v: '02 — LADRILLO', opts: ['01 — CONCRETO', '02 — LADRILLO', '03 — FIERRO', '04 — MADERA', '99 — NO ESPECIFICADO'] }),
            T('ECS', { t: 'sel', v: '02 — BUENO', opts: ['01 — MUY BUENO', '02 — BUENO', '03 — REGULAR', '04 — MALO'] }),
            T('EDC', { t: 'sel', v: '01 — TERRENO SIN CONSTRUIR', opts: ['01 — TERRENO SIN CONSTRUIR', '02 — CONSTRUIDO', '03 — EN CONSTRUCCIÓN'] })
          ]),
          S('Dimensiones verificadas', [
            T('Largo', { v: '1.00' }), T('Ancho', { v: '1.00' }), T('Alto', { v: '1.00' }),
            T('Metrado', { v: '1.00' }),
            T('Unidad de medida', { t: 'sel', v: '02 — METROS CUADRADOS', opts: ['01 — METROS LINEALES', '02 — METROS CUADRADOS', '03 — UNIDAD', '04 — GLOBAL'] }),
            T('UCA', { t: 'sel', v: '99 — NO ESPECIFICADO', opts: ['01 — CERCO', '02 — PORTÓN', '03 — TANQUE', '99 — NO ESPECIFICADO'] })
          ])
        ] }
      ],
      table: {
        title: 'Versiones registradas por piso', count: '3 registros',
        cols: ['Num. Piso', 'Mes', 'Año', 'MPE', 'ECS', 'ECC', 'Muro', 'Tech', 'Piso', 'Puer', 'Rev', 'Bañ', 'InstEle', 'Área declarada', 'Área verificada', 'UCA'], num: [13, 14],
        rows: [
          ['01', '01', '1986', '02', '03', '03', 'C', 'F', 'I', 'I', 'F', 'H', 'H', '0.000', '40.000', '99'],
          ['01', '01', '1978', '02', '03', '03', 'C', 'F', 'H', 'F', 'H', 'E', 'G', '0.000', '75.540', '99'],
          ['01', '01', '1986', '02', '02', '03', 'C', 'F', 'G', 'F', 'F', 'H', 'H', '0.000', '77.000', '99']
        ]
      },
      actions: ['Nuevo', 'Guardar', 'Imprimir', 'Quitar']
    },

    ficha_contribuyente_reporte: {
      mod: 'Catastro', title: 'Reporte de ficha del contribuyente', kind: 'report',
      endpoint: 'GET /api/v1/catastro/contribuyentes/{codigo}/ficha.pdf',
      desc: 'Ficha impresa del contribuyente: identificación, domicilio fiscal, documentos, contactos y unidades afectas.',
      report: {
        code: 'FC-00000025673', date: '13 de agosto de 2026',
        title: 'Ficha del contribuyente',
        subtitle: 'Registro único de contribuyentes — Gerencia de Rentas',
        meta: [
          { k: 'Código', v: '00000025673' },
          { k: 'Contribuyente', v: 'SUC. RUFINA MEDINA MEDINA' },
          { k: 'Tipo de persona', v: 'SUCESIÓN INDIVISA' },
          { k: 'Documento', v: 'DNI 03593174' },
          { k: 'Domicilio fiscal', v: 'CA. SANTA ROSA 116 — URB. SANTA ROSA, SULLANA' },
          { k: 'Estado', v: 'A — ACTIVO' }
        ],
        cols: ['Unidad', 'Identificación', 'Uso / clase', 'Condición', 'Deuda S/'], num: [4],
        rows: [
          ['Predio', '02-014-D-14-01', 'Casa habitación', 'Propietario único', '1,842.60'],
          ['Predio', '04-021-B-07-00', 'Terreno sin construir', 'Copropietario 50 %', '0.00'],
          ['Vehículo', 'T2G-418', 'Automóvil', 'Afecto 2019 — 2021', '0.00'],
          ['Licencia', 'LF-2024-00812', 'Bodega', 'Vigente', '0.00']
        ],
        footer: 'Documento emitido por el Sistema de Gestión Tributaria Municipal. La información corresponde al registro a la fecha de emisión y no constituye constancia de no adeudo.'
      }
    },

    /* ── FISCALIZACIÓN ────────────────────────────────────── */

    fisc_estado_cuenta: {
      mod: 'Fiscalización', title: 'Estado de cuenta de fiscalización',
      endpoint: 'GET /api/v1/fiscalizacion/estado-cuenta?contribuyente={codigo}',
      desc: 'Consulta las deudas originadas en un proceso fiscalizador: diferencias de impuesto predial, arbitrios y patrimonio vehicular con sus multas tributarias.',
      filters: [T('Tipo de papeleta', { t: 'sel', v: 'TRIBUTARIA', opts: tipoPap }), T('Papeleta', { v: '' }), T('Placa', { v: '' }), T('Contribuyente', { v: '00000093199' })],
      sections: [
        S('Búsqueda', [
          T('Contribuyente', { t: 'ro', v: '00000093199 — ALBURQUEQUE INFANTE GENARO' }),
          T('Domicilio fiscal', { t: 'ro', v: 'SULLANA - CA. — DIR. REFER.: LA HUACA - PAITA', wide: 1 }),
          T('Incluir inquilinos', { t: 'chk', on: false }),
          T('Excluir deudas prescritas', { t: 'chk', on: false }),
          T('Fecha de consulta', { t: 'date', v: '2026-08-13' })
        ]),
        S('Filtros de deuda', [
          T('Año', { v: '' }), T('Cuota', { v: '' }),
          T('Tributo', { t: 'sel', v: '00003 — VEHICULAR', opts: ['00001 — PREDIAL', '00003 — VEHICULAR', '00007 — LIMPIEZA PÚBLICA', '00008 — PARQUES Y JARDINES', '00026 — SERENAZGO'] }),
          T('Fase', { v: '' }), T('Conc.', { v: '' }), T('CodUnid', { v: '' }),
          T('CodConv', { v: '' }), T('Recau', { v: '' }), T('Coac', { v: '' })
        ]),
        S('Impresión', [
          T('Formato', { t: 'sel', v: 'DETALLADO', opts: ['CONSOLIDADO', 'DETALLADO', 'VOUCHER'] })
        ])
      ],
      table: {
        title: 'Deudas de fiscalización', count: '4 registros · total S/ 581.65',
        note: 'Tributo 500.00 · Reajuste 12.50 · Interés 58.35 · Gastos 10.80',
        cols: ['Deuda', 'Cod. Contri.', 'Año', 'Unidad', 'Convenio', 'Cuota', 'Cod. Tri.', 'Nom. Trib.', 'Fase', 'Concepto', 'Estad.', 'Papeleta', 'UnidIden'], num: [],
        rows: [
          ['47', '00000093', '2010', 'SC-2346', '—', '001', '00003', 'VEHICULAR-FIS', '002', '081', ['P', 'warn'], '—', '001'],
          ['48', '00000093', '2010', 'SC-2346', '—', '002', '00003', 'VEHICULAR-FIS', '002', '081', ['P', 'warn'], '—', '001'],
          ['49', '00000093', '2010', 'SC-2346', '—', '003', '00003', 'VEHICULAR-FIS', '002', '081', ['P', 'warn'], '—', '001'],
          ['50', '00000093', '2010', 'SC-2346', '—', '004', '00003', 'VEHICULAR-FIS', '002', '081', ['P', 'warn'], '—', '001']
        ]
      },
      actions: ['Buscar', 'Filtrar', 'Limpiar', 'Imprimir']
    },

    fisc_historico: {
      mod: 'Fiscalización', title: 'Histórico de fiscalización predial',
      endpoint: 'GET /api/v1/fiscalizacion/predial/historico',
      desc: 'Versiones de un proceso fiscalizador: qué característica cambió, quién la modificó y en qué momento. Cada liquidación conserva su estado y su versión.',
      filters: [T('Nº Liquidación', { v: '' }), T('Cód. Cont.', { v: '00000277292' }), T('Nº Notificación', { v: '' }), T('Contribuyente', { v: '' })],
      table: {
        title: 'Fiscalizaciones encontradas', count: '6 registros',
        cols: ['Est.', 'Cód. Cont.', 'Contribuyente', 'Nº Liquidación', 'Nº Notificación', 'Versión'], num: [5],
        rows: [
          [['A', 'ok'], '00000038288', 'EUREKA S.R.L.', '—', '—', '2'],
          [['L', 'warn'], '00000043655', 'BUSTAMANTE REPRESENTACIONES S...', '—', '—', '1'],
          [['L', 'warn'], '00000277292', 'CORPORACIÓN BUSTAMANTE S.A.C.', '—', '—', '3'],
          [['L', 'warn'], '00000041313', 'RUGEL MEDINA-CESAR', '—', '—', '2'],
          [['L', 'warn'], '00000013846', 'TALLEDO TORRES-GUIDO GERARDO', '—', '—', '1'],
          [['L', 'warn'], '00000009738', 'AGROCHIRA S.A.', '—', '—', '1']
        ]
      },
      tabs: [
        { label: 'Datos Generales', sections: [
          S('Propietario', [
            T('Código Cont.', { t: 'ro', v: '00000277292' }),
            T('Nombre', { t: 'ro', v: 'CORPORACIÓN BUSTAMANTE S.A.C.', wide: 1 })
          ]),
          S('Fiscalización', [
            T('Fecha de fiscalización', { t: 'date', v: '2026-03-08' }),
            T('Bloqueado', { t: 'chk', on: false }),
            T('Estado', { t: 'sel', v: 'LIQUIDADA', opts: ['ABIERTA', 'EN PROCESO', 'LIQUIDADA', 'NOTIFICADA', 'ANULADA'] }),
            T('Motivo determinante', { t: 'area', wide: 1 }),
            T('Periodo fiscalizado — desde', { t: 'sel', v: '2024', opts: yrs }),
            T('Periodo fiscalizado — hasta', { t: 'sel', v: '2026', opts: yrs }),
            T('Tipo de fiscalización', { t: 'sel', v: 'CIERTA', opts: ['CIERTA', 'PRESUNTA', 'DE OFICIO', 'GABINETE'] }),
            T('Último usuario', { t: 'ro', v: 'MRIOS — 08/03/2026 11:42' })
          ])
        ] },
        { label: 'Versiones', sections: [
          S('Historial de versiones', [
            T('Nº de versión', { t: 'ro', v: '3' }),
            T('Estado de la versión', { t: 'ro', v: 'L — LIQUIDADA' }),
            T('Fecha de la versión', { t: 'ro', v: '08/03/2026' }),
            T('Versión anterior', { t: 'ro', v: '2 — A — 08/03/2026' })
          ], 'Solo lectura')
        ] },
        { label: 'Estado de predios', sections: [
          S('Predio urbano', [
            T('Cod. Catastral', { t: 'ro', v: '200601010150010101001' }),
            T('Dirección del predio', { t: 'ro', v: 'AV. JOSÉ DE LAMA 1180', wide: 1 })
          ]),
          S('Predio rural', [
            T('Cod. Ref.', { t: 'ro', v: '—' }),
            T('Ubicación', { t: 'ro', v: '—', wide: 1 })
          ])
        ] },
        { label: 'Documentos', sections: [
          S('Documentos de la fiscalización', [
            T('Tipo de documento', { t: 'sel', v: 'ACTA DE INSPECCIÓN', opts: ['ACTA DE INSPECCIÓN', 'REQUERIMIENTO', 'RESULTADO DE REQUERIMIENTO', 'LIQUIDACIÓN', 'NOTIFICACIÓN'] }),
            T('Nº de documento', { v: '' }), T('Fecha', { t: 'date', v: '2026-03-08' }),
            T('Archivo', { t: 'ro', v: 'ACTA-277292-V3.pdf', wide: 1 })
          ])
        ] },
        { label: 'Infracciones', sections: [
          S('Infracciones detectadas', [
            T('Código de infracción', { v: '' }),
            T('Artículo del Código Tributario', { t: 'sel', v: 'ART. 176 NUM. 1', opts: ['ART. 176 NUM. 1', 'ART. 177 NUM. 5', 'ART. 178 NUM. 1', 'ART. 173 NUM. 1'] }),
            T('Multa (S/)', { v: '0.00' }), T('Gradualidad (%)', { v: '0' })
          ])
        ] },
        { label: 'Observaciones', sections: [
          S('Observaciones de la versión', [T('Observación', { t: 'area', wide: 1 })])
        ] }
      ],
      actions: ['Buscar', 'Actualizar', 'Imprimir']
    },

    /* ── TRÁNSITO ─────────────────────────────────────────── */

    transito_busqueda: {
      mod: 'Tránsito', title: 'Búsqueda de infracciones',
      endpoint: 'GET /api/v1/transito/papeletas/busqueda',
      desc: 'Búsqueda avanzada de papeletas por número, placa, infractor, propietario, rango de fechas y estado de deuda. Muestra el estado de coactiva, el último pago y el usuario que registró la papeleta.',
      filters: [T('Papeleta', { v: '' }), T('Nº Placa', { v: 'NB-21169' }), T('Estado de deuda', { t: 'sel', v: '(TODOS)', opts: estDeuda }), T('Ingresado por', { v: 'JC' })],
      sections: [
        S('Búsqueda avanzada', [
          T('Conductor — código', { v: '' }), T('Conductor — nombre', { v: '' }),
          T('Propietario — código', { v: '' }), T('Propietario — nombre', { v: '' }),
          T('Registradas desde', { t: 'date', v: '2026-07-21' }), T('Registradas hasta', { t: 'date', v: '2026-08-13' })
        ])
      ],
      table: {
        title: 'Papeletas encontradas', count: '6 registros',
        note: 'Total pendiente — importe S/ 175.00 · a pagar S/ 52.50 · con beneficio S/ 52.50',
        cols: ['A.Coa', 'Coact', 'Fec. Reg.', 'Deuda', 'Serie', 'Número', 'Placa', 'Fecha', 'Infracción', 'Conductor', 'Importe', 'A pagar'], num: [10, 11],
        rows: [
          ['—', '—', '01/07/2026', ['CANCELADA', 'ok'], 'D', '007782', 'NB-21169', '01/07/2026', 'OM F-16', 'SERNAQUE VILLEGAS H...', '144.00', '144.00'],
          ['—', '—', '01/01/1900', ['PENDIENTE', 'warn'], 'C', '002635', 'NB-21169', '12/04/2025', 'DS F1', 'SERNAQUE VILLEGAS H...', '142.00', '42.60'],
          ['—', '—', '01/01/1900', ['A CUENTA', 'warn'], 'C', '010962', 'NB-21169', '31/01/2024', 'DS F1', 'SÁNCHEZ NAVARRO MIG...', '280.00', '84.00'],
          ['✓', '■', '01/01/1900', ['PENDIENTE', 'warn'], 'C', '006230', 'NB-21169', '25/03/2022', 'OM F4', 'SERNAQUE VILLEGAS H...', '34.00', '34.00'],
          ['—', '—', '01/01/1900', ['PENDIENTE', 'warn'], 'C', '003159', 'NB-21169', '09/09/2021', 'OM F4', 'CARRASCO MIGUEL ÁNG...', '33.00', '9.90'],
          ['—', '—', '01/01/1900', ['PENDIENTE', 'warn'], 'C', '001686', 'NB-21169', '03/08/2021', 'OM F4', 'CARRASCO MONTES AN...', '16.50', '16.50']
        ]
      },
      actions: ['Buscar', 'Limpiar', 'Ver propietario y pagos', 'Excel']
    },

    transito_reportes: {
      mod: 'Tránsito', title: 'Reportes de infracción de tránsito',
      endpoint: 'POST /api/v1/transito/reportes',
      desc: 'Emisor de los reportes del módulo de tránsito. El tipo de reporte habilita los criterios que corresponden y el destino puede ser pantalla, impresora o Excel.',
      sections: [
        S('Tipo de reporte', [
          T('Reporte', { t: 'sel', v: 'RECORD DE CONDUCTOR', wide: 1, opts: [
            'RECORD DE CONDUCTOR', 'RECORD VEHICULAR', 'CONSTANCIA LIBRE DE INFRACCIONES',
            'PADRÓN DE PAPELETAS DE INFRACCIÓN', 'ESTADO DE CUENTA DE INFRACCIONES',
            'PAPELETA DE INFRACCIÓN', 'RESOLUCIÓN DE GERENCIA ORDINARIA',
            'PAPELETAS ENVIADAS A COACTIVAS', 'RESOLUCIÓN DE GERENCIA SANCIONADA',
            'NOTIFICACIÓN', 'RELACIÓN CONSTANCIAS LIBRE DE INFRAC.', 'RESUMEN RECAUDACIÓN',
            'RESUMEN PAPEL. PENDIENTES Y PAGADAS', 'RESUMEN POR CÓDIGO INFRACCIÓN', 'RESUMEN POR PLACA (2 LETRAS)'
          ] })
        ]),
        S('Criterios', [
          T('Nº papeleta — serie', { v: '' }), T('Nº papeleta — año', { v: '' }), T('Nº papeleta — número', { v: '' }),
          T('¿Hasta?', { v: '' }),
          T('Estado', { t: 'sel', v: '(TODOS)', opts: estDeuda }),
          T('Conductor', { v: '' }), T('Placa', { v: '' }),
          T('Infracción — código', { v: '' }),
          T('Acción', { t: 'sel', v: 'GENERAR', opts: ['GENERAR', 'REIMPRIMIR', 'ANULAR'] }),
          T('Nº constancia', { v: '' }), T('Nº recibo', { v: '' }), T('Importe S/', { v: '' }),
          T('Usuario que ingresó', { v: '' }),
          T('Fecha desde', { t: 'date', v: '2026-07-01' }), T('Fecha hasta', { t: 'date', v: '2026-08-13' }),
          T('Fecha de ingreso desde', { t: 'date', v: '' }), T('Fecha de ingreso hasta', { t: 'date', v: '' }),
          T('Ordenación', { t: 'sel', v: 'FECHA DE INFRACCIÓN', opts: ['FECHA DE INFRACCIÓN', 'Nº DE PAPELETA', 'PLACA', 'CONDUCTOR', 'IMPORTE'] }),
          T('Agrupado por', { t: 'sel', v: 'MES', opts: ['MES', 'AÑO', 'CÓDIGO DE INFRACCIÓN', 'ESTADO', 'PLACA'] })
        ])
      ],
      actions: ['Exportar', 'Imprimir', 'Pantalla', 'Cancelar']
    },

    transito_record_conductor: {
      mod: 'Tránsito', title: 'Record de conductor', kind: 'report',
      endpoint: 'GET /api/v1/transito/reportes/record-conductor',
      desc: 'Historial de infracciones cometidas por un conductor y el estado de deuda de cada papeleta impuesta.',
      report: {
        code: 'RC-2026-00418', date: '13 de agosto de 2026',
        title: 'Record de conductor',
        subtitle: 'Historial de infracciones de tránsito',
        meta: [
          { k: 'Conductor', v: 'SERNAQUE VILLEGAS, DORIS' },
          { k: 'Licencia', v: 'Q-44218937 — clase A-I' },
          { k: 'Documento', v: 'DNI 44218937' },
          { k: 'Domicilio', v: 'CALLE TÚPAC AMARU 611 — SULLANA' },
          { k: 'Papeletas registradas', v: '6' },
          { k: 'Deuda pendiente', v: 'S/ 175.00' }
        ],
        cols: ['Papeleta', 'Fecha', 'Placa', 'Infracción', 'Importe S/', 'Estado'], num: [4],
        rows: [
          ['D2026007782', '01/07/2026', 'NB-21169', 'OM F-16', '144.00', 'Cancelada'],
          ['C2025002635', '12/04/2025', 'NB-21169', 'DS F1', '142.00', 'Pendiente'],
          ['C2022006230', '25/03/2022', 'NB-21169', 'OM F4', '34.00', 'Coactiva'],
          ['C2021003159', '09/09/2021', 'NB-21169', 'OM F4', '33.00', 'Pendiente']
        ],
        footer: 'El presente record se emite a solicitud del interesado y refleja las papeletas registradas en el sistema a la fecha de emisión.'
      }
    },

    transito_record_vehicular: {
      mod: 'Tránsito', title: 'Record vehicular', kind: 'report',
      endpoint: 'GET /api/v1/transito/reportes/record-vehicular',
      desc: 'Historial de papeletas de infracción de tránsito de un solo vehículo, con el estado de pago de cada una.',
      report: {
        code: 'RV-2026-00219', date: '13 de agosto de 2026',
        title: 'Record vehicular',
        subtitle: 'Papeletas de infracción por vehículo',
        meta: [
          { k: 'Placa', v: 'NB-21169' },
          { k: 'Clase', v: 'AUTOMÓVIL' },
          { k: 'Marca y modelo', v: 'TOYOTA COROLLA' },
          { k: 'Propietario', v: 'SERNAQUE VILLEGAS, DORIS' },
          { k: 'Papeletas', v: '6' },
          { k: 'Pendiente', v: 'S/ 175.00' }
        ],
        cols: ['Papeleta', 'Fecha', 'Conductor', 'Infracción', 'Importe S/', 'Estado'], num: [4],
        rows: [
          ['D2026007782', '01/07/2026', 'SERNAQUE VILLEGAS, D.', 'OM F-16', '144.00', 'Cancelada'],
          ['C2024010962', '31/01/2024', 'SÁNCHEZ NAVARRO, M.', 'DS F1', '280.00', 'A cuenta'],
          ['C2022006230', '25/03/2022', 'SERNAQUE VILLEGAS, D.', 'OM F4', '34.00', 'Coactiva'],
          ['C2021001686', '03/08/2021', 'CARRASCO MONTES, A.', 'OM F4', '16.50', 'Pendiente']
        ],
        footer: 'Documento informativo. No acredita la ausencia de infracciones; para ello corresponde la constancia libre de infracciones.'
      }
    },

    transito_constancia_libre: {
      mod: 'Tránsito', title: 'Constancia libre de infracciones', kind: 'report',
      endpoint: 'POST /api/v1/transito/constancias-libres',
      desc: 'Documento con el que la municipalidad acredita que un vehículo no registra papeletas de tránsito pendientes de pago.',
      report: {
        code: 'CLI-2026-00742', date: '13 de agosto de 2026',
        title: 'Constancia libre de infracciones',
        subtitle: 'Emitida por la Subgerencia de Fiscalización y Control de Tránsito',
        meta: [
          { k: 'Nº de constancia', v: '000742-2026' },
          { k: 'Placa', v: 'B7T-221' },
          { k: 'Propietario', v: 'REYES CHUNGA, PEDRO' },
          { k: 'Documento', v: 'DNI 02718844' },
          { k: 'Recibo de pago', v: '000000049406 — S/ 36.00' },
          { k: 'Vigencia', v: '30 días calendario' }
        ],
        cols: ['Concepto', 'Periodo verificado', 'Papeletas', 'Situación'], num: [2],
        rows: [
          ['Papeletas de tránsito', '2019 — 2026', '0', 'Sin registros pendientes'],
          ['Papeletas en cobranza coactiva', '2019 — 2026', '0', 'Sin registros'],
          ['Internamiento vehicular', '2019 — 2026', '0', 'Sin registros']
        ],
        footer: 'Se deja constancia de que el vehículo identificado no registra papeletas de infracción de tránsito pendientes de pago a la fecha de emisión.'
      }
    },

    transito_padron: {
      mod: 'Tránsito', title: 'Padrón de papeletas de tránsito',
      endpoint: 'GET /api/v1/transito/reportes/padron',
      desc: 'Listado de las papeletas registradas en un intervalo de fechas, filtrable por estado de deuda, infracción y placa.',
      filters: [T('Desde', { t: 'date', v: '2026-06-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Estado', { t: 'sel', v: 'PENDIENTES', opts: estDeuda }), T('Ordenado por', { t: 'sel', v: 'FECHA', opts: ['FECHA', 'Nº PAPELETA', 'PLACA', 'IMPORTE'] })],
      table: {
        title: 'Papeletas del periodo', count: '5 de 1,184 · S/ 8,442.50 pendiente',
        cols: ['Papeleta', 'Fec. infracción', 'Placa', 'Conductor', 'Infracción', 'Importe S/', 'A pagar S/', 'Estado'], num: [5, 6],
        rows: [
          ['C2026004182', '02/08/2026', 'T2G-418', 'CASTILLO PASCUALA, M.', 'M-20', '412.00', '123.60', ['Pendiente', 'warn']],
          ['C2026004183', '04/08/2026', 'V1H-882', 'DÍAZ MADRID, J.', 'G-58', '206.00', '61.80', ['Pendiente', 'warn']],
          ['C2026004184', '11/08/2026', 'NB-21169', 'SERNAQUE VILLEGAS, D.', 'DS F1', '142.00', '42.60', ['Pendiente', 'warn']],
          ['C2026004185', '11/08/2026', 'B7T-221', 'REYES CHUNGA, P.', 'OM F4', '34.00', '34.00', ['Coactiva', 'bad']],
          ['C2026004186', '12/08/2026', 'T4M-119', 'INVERSIONES DEL NORTE', 'M-02', '824.00', '247.20', ['Pendiente', 'warn']]
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    transito_estado_cuenta: {
      mod: 'Tránsito', title: 'Estado de cuenta de infracciones',
      endpoint: 'GET /api/v1/transito/estado-cuenta',
      desc: 'Papeletas pendientes de pago de un conductor o de un vehículo, con importe, beneficio aplicable y situación de coactiva.',
      filters: [T('Conductor', { v: '' }), T('Placa', { v: 'NB-21169' }), T('Estado', { t: 'sel', v: 'PENDIENTE', opts: estDeuda }), T('Fecha de cálculo', { t: 'date', v: '2026-08-13' })],
      table: {
        title: 'Deuda por papeletas', count: '5 papeletas · S/ 175.00',
        note: 'Importe S/ 175.00 · a pagar S/ 52.50 · con beneficio S/ 52.50',
        cols: ['Papeleta', 'Fecha', 'Infracción', 'Importe S/', 'Descuento %', 'A pagar S/', 'Coactiva', 'Estado'], num: [3, 4, 5],
        rows: [
          ['C2025002635', '12/04/2025', 'DS F1', '142.00', '70', '42.60', 'No', ['Pendiente', 'warn']],
          ['C2024010962', '31/01/2024', 'DS F1', '280.00', '70', '84.00', 'No', ['A cuenta', 'warn']],
          ['C2022006230', '25/03/2022', 'OM F4', '34.00', '0', '34.00', 'Sí', ['Coactiva', 'bad']],
          ['C2021003159', '09/09/2021', 'OM F4', '33.00', '70', '9.90', 'No', ['Pendiente', 'warn']],
          ['C2021001686', '03/08/2021', 'OM F4', '16.50', '0', '16.50', 'No', ['Pendiente', 'warn']]
        ]
      },
      actions: ['Imprimir', 'Voucher de pago']
    },

    transito_papeleta_reporte: {
      mod: 'Tránsito', title: 'Reporte papeleta de infracción', kind: 'report',
      endpoint: 'GET /api/v1/transito/papeletas/{numero}/hoja-informativa',
      desc: 'Hoja informativa que resume la información relevante de una papeleta de infracción de tránsito.',
      report: {
        code: 'C2025002635', date: '13 de agosto de 2026',
        title: 'Papeleta de infracción de tránsito',
        subtitle: 'Hoja informativa de la infracción impuesta',
        meta: [
          { k: 'Nº de papeleta', v: 'C2025002635' },
          { k: 'Fecha y hora', v: '12/04/2025 — 18:40' },
          { k: 'Placa', v: 'NB-21169' },
          { k: 'Conductor', v: 'SERNAQUE VILLEGAS, DORIS' },
          { k: 'Licencia', v: 'Q-44218937' },
          { k: 'Lugar', v: 'AV. JOSÉ DE LAMA CDRA. 11' }
        ],
        cols: ['Concepto', 'Detalle', 'Importe S/'], num: [2],
        rows: [
          ['Código de infracción', 'DS F1 — Conducir sin portar licencia', '—'],
          ['Base imponible (UIT 2025)', 'S/ 5,350.00', '5,350.00'],
          ['% de la UIT por la infracción', '8 %', '428.00'],
          ['% realmente a cobrar', '33.18 %', '142.00'],
          ['Importe a pagar con beneficio', 'Descuento 70 % — pago dentro de 5 días', '42.60']
        ],
        footer: 'El infractor puede presentar descargo dentro de los cinco días hábiles de notificada la papeleta, conforme al Reglamento Nacional de Tránsito.'
      }
    },

    transito_rg_ordinaria: {
      mod: 'Tránsito', title: 'Resolución de gerencia ordinaria', kind: 'report',
      endpoint: 'POST /api/v1/transito/resoluciones/ordinaria',
      desc: 'Resolución que emite la municipalidad para la cobranza de la papeleta. De no cancelarse, el documento pasa al área de cobranza coactiva.',
      report: {
        code: 'RG-2026-001842', date: '13 de agosto de 2026',
        title: 'Resolución de gerencia',
        subtitle: 'Cobranza ordinaria de papeleta de infracción de tránsito',
        meta: [
          { k: 'Nº de resolución', v: '001842-2026-GR/MPS' },
          { k: 'Papeleta', v: 'C2025002635' },
          { k: 'Obligado', v: 'SERNAQUE VILLEGAS, DORIS' },
          { k: 'Documento', v: 'DNI 44218937' },
          { k: 'Domicilio', v: 'CALLE TÚPAC AMARU 611 — SULLANA' },
          { k: 'Plazo de pago', v: '7 días hábiles' }
        ],
        cols: ['Concepto', 'Periodo', 'Importe S/'], num: [2],
        rows: [
          ['Multa por infracción DS F1', '2025', '142.00'],
          ['Interés moratorio', 'al 13/08/2026', '18.40'],
          ['Gastos administrativos', '—', '10.80'],
          ['Total a pagar', '—', '171.20']
        ],
        footer: 'Vencido el plazo señalado sin acreditarse el pago, el expediente será remitido a la Oficina de Ejecutoría Coactiva para el inicio del procedimiento de cobranza coactiva.'
      }
    },

    transito_padron_coactiva: {
      mod: 'Tránsito', title: 'Padrón de papeletas enviadas a coactiva',
      endpoint: 'GET /api/v1/transito/reportes/padron-coactiva',
      desc: 'Control de las papeletas derivadas al área de cobranza coactiva por intervalo de fechas.',
      filters: [T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Ejecutor', { t: 'sel', v: 'Todos', opts: ['Todos', 'CHECA FERNÁNDEZ-HILTON ARTURO', 'GARCÍA NAVARRO-MARTHA ELENA'] }), T('Estado del expediente', { t: 'sel', v: 'Todos', opts: ['Todos', 'REC 01 EMITIDO', 'NOTIFICADO', 'MEDIDA CAUTELAR', 'CONCLUIDO'] })],
      table: {
        title: 'Papeletas en coactiva', count: '4 papeletas · S/ 1,284.00',
        cols: ['Expediente', 'Papeleta', 'Fec. pase', 'Placa', 'Obligado', 'Deuda S/', 'Estado'], num: [5],
        rows: [
          ['2026-0001201', 'C2022006230', '18/03/2026', 'NB-21169', 'SERNAQUE VILLEGAS, D.', '34.00', ['REC 01 emitido', 'warn']],
          ['2026-0001248', 'C2021009118', '02/04/2026', 'B7T-221', 'REYES CHUNGA, P.', '412.00', ['Notificado', 'warn']],
          ['2026-0001302', 'C2020004410', '11/05/2026', 'T4M-119', 'INVERSIONES DEL NORTE', '824.00', ['Medida cautelar', 'bad']],
          ['2026-0001344', 'C2020001188', '30/06/2026', 'V1H-882', 'DÍAZ MADRID, J.', '14.00', ['Concluido', 'ok']]
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    transito_rg_sancionadora: {
      mod: 'Tránsito', title: 'Resolución de gerencia sancionadora', kind: 'report',
      endpoint: 'POST /api/v1/transito/resoluciones/sancionadora',
      desc: 'Segunda resolución, emitida luego de la ordinaria. Tiene carácter sancionador y se deriva a la Dirección General de Transportes.',
      report: {
        code: 'RGS-2026-000418', date: '13 de agosto de 2026',
        title: 'Resolución de gerencia sancionadora',
        subtitle: 'Deriva la sanción a la Dirección Regional de Transportes y Comunicaciones',
        meta: [
          { k: 'Nº de resolución', v: '000418-2026-GR/MPS' },
          { k: 'Resolución ordinaria', v: '001842-2026-GR/MPS' },
          { k: 'Papeleta', v: 'C2025002635' },
          { k: 'Obligado', v: 'SERNAQUE VILLEGAS, DORIS' },
          { k: 'Licencia', v: 'Q-44218937 — clase A-I' },
          { k: 'Sanción accesoria', v: 'Suspensión de licencia' }
        ],
        cols: ['Concepto', 'Detalle', 'Importe S/'], num: [2],
        rows: [
          ['Multa firme', 'DS F1 — papeleta C2025002635', '142.00'],
          ['Interés y gastos', 'al 13/08/2026', '29.20'],
          ['Total exigible', '—', '171.20'],
          ['Sanción no pecuniaria', 'Suspensión de licencia por 30 días', '—']
        ],
        footer: 'Se remite copia a la Dirección Regional de Transportes y Comunicaciones para el registro de la sanción en el Registro Nacional de Sanciones.'
      }
    },

    transito_padron_constancias: {
      mod: 'Tránsito', title: 'Padrón de constancias libres de infracciones',
      endpoint: 'GET /api/v1/transito/reportes/padron-constancias',
      desc: 'Padrón general de constancias libres de infracciones emitidas por la unidad competente.',
      filters: [T('Desde', { t: 'date', v: '2026-07-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Nº de constancia', { v: '' }), T('Usuario que emitió', { t: 'sel', v: 'Todos', opts: ['Todos', 'VRETO', 'MRIOS', 'SISTEMAS'] })],
      table: {
        title: 'Constancias emitidas', count: '4 constancias · S/ 144.00 recaudado',
        cols: ['Nº constancia', 'Fecha', 'Placa', 'Solicitante', 'Recibo', 'Importe S/', 'Usuario'], num: [5],
        rows: [
          ['000742-2026', '13/08/2026', 'B7T-221', 'REYES CHUNGA, PEDRO', '000000049406', '36.00', 'VRETO'],
          ['000741-2026', '11/08/2026', 'T2G-418', 'CASTILLO PASCUALA, M.', '000000049388', '36.00', 'VRETO'],
          ['000740-2026', '06/08/2026', 'V1H-882', 'DÍAZ MADRID, J.', '000000049341', '36.00', 'MRIOS'],
          ['000739-2026', '02/08/2026', 'T4M-119', 'INVERSIONES DEL NORTE', '000000049302', '36.00', 'MRIOS']
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    transito_resumen_recaudacion: {
      mod: 'Tránsito', title: 'Resumen de recaudación de tránsito',
      endpoint: 'GET /api/v1/transito/reportes/resumen-recaudacion',
      desc: 'Recaudación por papeletas organizada por tipo de cobranza, año y mes.',
      filters: [T('Año', { t: 'sel', v: '2026', opts: yrs }), T('Tipo de cobranza', { t: 'sel', v: 'Todas', opts: ['Todas', 'ORDINARIA', 'COACTIVA', 'CONVENIO'] }), T('Agrupado por', { t: 'sel', v: 'MES', opts: ['MES', 'AÑO', 'TIPO DE COBRANZA'] }), T('Caja', { t: 'sel', v: 'Todas', opts: ['Todas', 'CAJA 01', 'CAJA 02', 'CAJA 03'] })],
      table: {
        title: 'Recaudación por mes', count: 'Enero — agosto 2026 · S/ 184,412.60',
        cols: ['Mes', 'Ordinaria S/', 'Coactiva S/', 'Convenios S/', 'Papeletas pagadas', 'Total S/'], num: [1, 2, 3, 4, 5],
        rows: [
          ['Enero', '18,412.00', '4,120.00', '2,180.00', '184', '24,712.00'],
          ['Febrero', '16,204.50', '3,880.00', '1,940.00', '162', '22,024.50'],
          ['Marzo', '21,180.00', '5,412.00', '2,410.00', '204', '29,002.00'],
          ['Abril', '19,442.10', '4,018.00', '2,110.00', '191', '25,570.10'],
          ['Mayo', '22,104.00', '6,180.00', '2,880.00', '218', '31,164.00'],
          ['Junio', '17,880.00', '3,410.00', '1,780.00', '172', '23,070.00'],
          ['Julio', '20,412.00', '4,918.00', '2,240.00', '198', '27,570.00'],
          ['Agosto (al 13)', '1,180.00', '120.00', '0.00', '14', '1,300.00']
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    transito_resumen_papeletas: {
      mod: 'Tránsito', title: 'Resumen de papeletas pendientes y pagadas',
      endpoint: 'GET /api/v1/transito/reportes/resumen-papeletas',
      desc: 'Cantidades e importes de papeletas pendientes y pagadas, diferenciando cobranza ordinaria de cobranza coactiva.',
      filters: [T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Agrupado por', { t: 'sel', v: 'AÑO', opts: ['AÑO', 'MES', 'ESTADO', 'CÓDIGO DE INFRACCIÓN'] }), T('Cobranza', { t: 'sel', v: 'Todas', opts: ['Todas', 'ORDINARIA', 'COACTIVA'] })],
      table: {
        title: 'Papeletas por año y estado', count: '2021 — 2026',
        cols: ['Año', 'Pendientes', 'Importe pendiente S/', 'Pagadas', 'Importe pagado S/', 'En coactiva', 'Importe coactiva S/'], num: [1, 2, 3, 4, 5, 6],
        rows: [
          ['2026', '412', '84,180.00', '1,184', '184,412.60', '48', '18,420.00'],
          ['2025', '388', '76,410.00', '1,042', '162,180.40', '92', '32,118.00'],
          ['2024', '294', '58,220.00', '918', '141,204.80', '118', '41,880.00'],
          ['2023', '218', '42,180.00', '842', '128,410.00', '142', '48,120.00'],
          ['2022', '184', '34,110.00', '788', '112,880.00', '164', '52,410.00'],
          ['2021', '142', '26,480.00', '712', '98,412.00', '188', '58,204.00']
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    transito_resumen_codigo: {
      mod: 'Tránsito', title: 'Resumen de papeletas por código de infracción',
      endpoint: 'GET /api/v1/transito/reportes/resumen-por-codigo',
      desc: 'Cantidades e importes de papeletas pendientes y pagadas de una infracción determinada.',
      filters: [T('Código de infracción', { v: '' }), T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Estado', { t: 'sel', v: '(TODOS)', opts: estDeuda })],
      table: {
        title: 'Papeletas por infracción', count: '6 códigos con movimiento',
        cols: ['Código', 'Descripción', 'Pendientes', 'Pendiente S/', 'Pagadas', 'Pagado S/'], num: [2, 3, 4, 5],
        rows: [
          ['M-20', 'Conducir en estado de ebriedad', '18', '14,842.00', '42', '34,180.00'],
          ['G-58', 'Estacionar en zona rígida', '184', '18,412.00', '612', '61,200.00'],
          ['DS F1', 'No portar licencia de conducir', '92', '13,064.00', '288', '40,896.00'],
          ['OM F4', 'Circular sin SOAT vigente', '76', '6,460.00', '204', '17,340.00'],
          ['OM F-16', 'Transporte informal de pasajeros', '28', '4,032.00', '88', '12,672.00'],
          ['M-02', 'Exceso de velocidad', '14', '11,536.00', '38', '31,312.00']
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    transito_resumen_placa: {
      mod: 'Tránsito', title: 'Resumen de papeletas por iniciales de placa',
      endpoint: 'GET /api/v1/transito/reportes/resumen-por-placa',
      desc: 'Resumen de papeletas filtrado por las dos letras iniciales del número de placa del vehículo.',
      filters: [T('Iniciales (2 letras)', { v: 'NB' }), T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Estado', { t: 'sel', v: '(TODOS)', opts: estDeuda })],
      table: {
        title: 'Papeletas por iniciales', count: '6 grupos',
        cols: ['Iniciales', 'Papeletas', 'Pendientes', 'Pendiente S/', 'Pagadas', 'Pagado S/'], num: [1, 2, 3, 4, 5],
        rows: [
          ['NB', '412', '118', '18,412.00', '294', '41,180.00'],
          ['T2', '288', '82', '12,204.00', '206', '31,420.00'],
          ['V1', '204', '64', '9,880.00', '140', '22,110.00'],
          ['B7', '184', '48', '7,412.00', '136', '19,840.00'],
          ['T4', '142', '38', '6,180.00', '104', '16,204.00'],
          ['M8', '118', '28', '4,410.00', '90', '13,880.00']
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    transito_valores: {
      mod: 'Tránsito', title: 'Generación de valores de tránsito',
      endpoint: 'POST /api/v1/transito/valores/generacion-masiva',
      desc: 'Genera masivamente los valores por papeletas de tránsito pendientes de pago. El criterio define el tipo de recaudo, la oficina y el vencimiento; las papeletas se agregan por selección o manualmente.',
      filters: [T('Código / descripción del criterio', { v: '', wide: 1 })],
      table: {
        title: 'Criterios registrados', count: '4 criterios',
        cols: ['Cod. Criterio', 'Descripción', 'Tipo Rec.', 'Fec. Ini.', 'Fec. Fin.', 'Est.'], num: [],
        rows: [
          ['00000000090', 'INSERCIÓN MIGRACIÓN PAPELETAS', 'RS', '01/01/2021', '01/12/2023', ['A', 'ok']],
          ['00000000091', 'INSERCIÓN MIGRACIÓN PAPELETAS 2007-2008', 'RS', '01/01/2021', '01/12/2023', ['A', 'ok']],
          ['00000007747', 'PAP TRAN-CRITERIO DE PRUEBA', 'RS', '01/10/2024', '31/10/2025', ['A', 'ok']],
          ['00000007748', 'PAPELETAS AGOSTO 2026', 'RS', '01/08/2026', '31/10/2026', ['A', 'ok']]
        ]
      },
      sections: [
        S('Criterio', [
          T('Código de criterio', { t: 'ro', v: '00000007748' }),
          T('Descripción', { v: 'PAPELETAS AGOSTO 2026', wide: 1 }),
          T('Fec. inicio', { t: 'date', v: '2026-08-01' }), T('Fec. fin', { t: 'date', v: '2026-10-31' }),
          T('Tipo de recaudo', { t: 'sel', v: '003 — RS PAPELETAS DE TRÁNSITO', opts: ['003 — RS PAPELETAS DE TRÁNSITO', '035 — RM PAPELETAS ADMINISTRATIVAS', '081 — RM LICENCIA FUNCIONAMIENTO'] }),
          T('Vencimiento', { t: 'date', v: '2026-10-06' }),
          T('Oficina', { t: 'sel', v: '113300 — SUBGERENCIA DE FISCALIZACIÓN TRIBUTARIA', opts: ['113300 — SUBGERENCIA DE FISCALIZACIÓN TRIBUTARIA', '113100 — SUBGERENCIA DE RECAUDACIÓN', '999999 — OFICINA NO ESPECIFICADA'], wide: 1 })
        ]),
        S('Recaudo / papeletas', [
          T('Papeleta', { v: '' }), T('Placa', { v: '' })
        ], 'Buscar papeletas y agregar al criterio')
      ],
      actions: ['Nuevo', 'Modificar', 'Guardar', 'Procesar', 'Anular', 'Imprimir']
    },

    transito_cambio_numero: {
      mod: 'Tránsito', title: 'Cambio de número de papeleta de tránsito',
      endpoint: 'PATCH /api/v1/transito/papeletas/{numero}/codigo',
      desc: 'Corrige el número de papeleta o el número de placa registrados, cuando hubo error del operador al momento del registro.',
      sections: [
        S('Actualización de cod. papeleta', [
          T('Placa Nº', { v: '' }),
          T('Cod. papeleta', { v: '' }),
          T('Placa nueva', { v: '' }),
          T('Cod. papeleta nueva', { v: '' })
        ])
      ],
      actions: ['Consultar', 'Modificar', 'Salir']
    },

    transito_documentos: {
      mod: 'Tránsito', title: 'Emisión de resoluciones y otros documentos',
      endpoint: 'GET /api/v1/transito/papeletas/{numero}/actos',
      desc: 'Registra los documentos emitidos por papeleta y conserva la secuencia del trámite, incluido el archivo digital de cada acto administrativo.',
      filters: [T('Contribuyente', { v: '' }), T('Papeleta Nº', { v: 'C2007005161' }), T('Placa Nº', { v: '' }), T('Expediente', { v: '112' })],
      table: {
        title: 'Papeletas con expediente', count: '4 registros',
        cols: ['Placa', 'Papeleta', 'Expediente', 'Código', 'Obligado'], num: [],
        rows: [
          ['NB-68190', 'C2009002448', '—', '00000092245', 'ADANAQUE CHINCHAY JOSÉ JORGE'],
          ['BIM-310', 'C2008020114', '—', '00000056625', 'BACA NEIRA RICARDO MARTÍN'],
          ['NB-26629', 'C2008017310', '—', '00000088898', 'VEGA PALACIOS MIGUEL RODOLFO'],
          ['NB-1712', 'C2007005161', '112', '00000071013', 'JUÁREZ SEMINARIO DANIEL']
        ]
      },
      sections: [
        S('Datos principales', [
          T('Placa', { t: 'ro', v: 'NB-1712' }),
          T('Papeleta Nº', { t: 'ro', v: 'C2007005161' }),
          T('Fec. papeleta', { t: 'date', v: '2026-07-25' }),
          T('Exped.', { v: '112' }), T('Fec. exp.', { t: 'date', v: '2026-05-05' }),
          T('Infracción', { t: 'ro', v: 'F5 — NO PRESENTAR LA TARJETA DE IDENTIFICACIÓN VEHICULAR, LICENCIA DE CONDUCIR U OTRO DOCUMENTO DE IDENTIDAD', wide: 1 }),
          T('Obligado', { t: 'ro', v: '00000071013 — JUÁREZ SEMINARIO DANIEL', wide: 1 }),
          T('Domicilio', { t: 'ro', v: 'CALLE BERNAL 439 BELLAVISTA', wide: 1 }),
          T('D.N.I.', { t: 'ro', v: '03901006' }), T('R.U.C.', { t: 'ro', v: '' })
        ]),
        S('Descargo e informe', [
          T('Fec. solicitud', { t: 'date', v: '' }),
          T('Argumento', { t: 'area', v: 'PRUEBA', wide: 1 }),
          T('Informe Nº', { v: '156' }),
          T('Fec. informe', { t: 'date', v: '' }),
          T('Glosa', { t: 'area', v: 'PRUEBA 1', wide: 1 })
        ]),
        S('Actos administrativos', [
          T('Documento', { t: 'sel', v: 'RESOLUCIÓN', opts: ['RESOLUCIÓN', 'NOTIFICACIÓN', 'INFORME', 'CARTA', 'MEMORANDO'] }),
          T('Nº Doc.', { v: '1' }), T('Fec. Doc.', { t: 'date', v: '2026-05-05' }),
          T('Nombre de archivo', { t: 'ro', v: 'RESOLUCION G...pdf' }),
          T('Glosa del acto', { v: '.', wide: 1 })
        ])
      ],
      actions: ['Nuevo', 'Modificar', 'Guardar', 'Deshacer', 'Imprimir']
    },

    /* ── INFRACCIONES ADMINISTRATIVAS ─────────────────────── */

    adm_notificacion: {
      mod: 'Infracciones administrativas', title: 'Notificación administrativa',
      endpoint: 'POST /api/v1/infracciones/administrativas/notificaciones',
      desc: 'Registro previo de la notificación emitida en la vivienda o el negocio inspeccionado. Es el paso anterior a la generación de la multa administrativa.',
      filters: [T('Serie', { v: '001' }), T('Año', { t: 'sel', v: '2026', opts: yrs }), T('Número', { v: '' }), T('Estado', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'NOTIFICADA', 'VENCIDA', 'SUBSANADA', 'CON PAPELETA', 'ANULADA'] })],
      table: {
        title: 'Notificaciones registradas', count: '4 de 812',
        cols: ['Serie-Nº', 'Fecha', 'Infractor', 'Dirección del predio', 'CIIU', 'Infracción', 'Vence', 'Estado'], num: [],
        rows: [
          ['001-004182', '02/08/2026', 'NOBLECILLA ARISMENDIZ SAC', 'AV. JOSÉ DE LAMA 1180', '5610', 'A-014', '12/08/2026', ['Vencida', 'bad']],
          ['001-004183', '04/08/2026', 'CASTILLO PASCUALA, MARÍA E.', 'CALLE LAMA 482', '4711', 'A-021', '14/08/2026', ['Notificada', 'warn']],
          ['001-004184', '07/08/2026', 'DÍAZ MADRID, JULIO CÉSAR', 'C.P. BARRIO BUENOS AIRES', '—', 'A-008', '17/08/2026', ['Subsanada', 'ok']],
          ['001-004185', '11/08/2026', 'INVERSIONES DEL NORTE SAC', 'AV. CHAMPAGNAT 220', '4520', 'A-032', '21/08/2026', ['Con papeleta', 'bad']]
        ]
      },
      sections: [
        S('Datos de la notificación', [
          T('Serie', { v: '001' }), T('Año', { t: 'sel', v: '2026', opts: yrs }), T('Número', { v: '004183' }),
          T('Fecha de notificación', { t: 'date', v: '2026-08-04' }),
          T('Hora', { v: '11:20' }),
          T('Plazo (días hábiles)', { v: '10' }),
          T('Vence', { t: 'ro', v: '14/08/2026' })
        ]),
        S('Infractor y predio', [
          T('Infractor — código', { v: '00000003541' }),
          T('Infractor — nombre', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA', wide: 1 }),
          T('D.N.I. / R.U.C.', { t: 'ro', v: '44218937' }),
          T('Dirección del predio', { v: 'CALLE LAMA 482', wide: 1 }),
          T('CIIU', { v: '4711 — VENTA AL POR MENOR EN COMERCIOS NO ESPECIALIZADOS' }),
          T('Licencia de funcionamiento', { v: 'LF-2024-00812' })
        ]),
        S('Infracción y fiscalizador', [
          T('Código de infracción', { v: 'A-021' }),
          T('Descripción', { t: 'ro', v: 'ABRIR ESTABLECIMIENTO SIN AUTORIZACIÓN MUNICIPAL', wide: 1 }),
          T('Fiscalizador', { t: 'sel', v: 'RETO SANTOS, VÍCTOR', opts: ['RETO SANTOS, VÍCTOR', 'RÍOS MENDOZA, MARÍA', 'QUISPE PEÑA, JORGE'] }),
          T('Recibido por', { t: 'sel', v: 'CONTRIBUYENTE', opts: ['CONTRIBUYENTE', 'FAMILIAR', 'DEPENDIENTE', 'NEGATIVA A RECIBIR', 'CEDULÓN'] }),
          T('Nombre del receptor', { v: '' }), T('D.N.I. del receptor', { v: '' }),
          T('Observaciones', { t: 'area', wide: 1 })
        ])
      ],
      actions: ['Nuevo', 'Modificar', 'Guardar', 'Anular', 'Imprimir']
    },

    adm_reportes: {
      mod: 'Infracciones administrativas', title: 'Reportes de infracción administrativa',
      endpoint: 'POST /api/v1/infracciones/administrativas/reportes',
      desc: 'Emisor de los reportes del módulo de papeletas administrativas. El tipo de reporte habilita los criterios y el destino puede ser pantalla, impresora o Excel.',
      sections: [
        S('Tipo de reporte', [
          T('Reporte', { t: 'sel', v: 'PADRÓN DE NOTIFICACIONES', wide: 1, opts: [
            'RELACIÓN DE NOTIFICACIONES POR MES', 'PADRÓN DE NOTIFICACIONES', 'NOTIFICACIONES VENCIDAS',
            'NOTIFICACIONES POR CONTRIBUYENTE', 'PADRÓN DE PAPELETAS', 'PAPELETAS POR INFRACCIÓN',
            'ESTADO DE CUENTA PAPELETA', 'RESOLUCIONES DE GERENCIA', 'NOTIFICACIÓN DE RESOLUCIÓN',
            'RESUMEN RECAUDACIÓN'
          ] })
        ]),
        S('Criterios', [
          T('Serie', { v: '' }), T('Año', { t: 'sel', v: '2026', opts: yrs }), T('Número', { v: '' }),
          T('Estado', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'NOTIFICADA', 'VENCIDA', 'SUBSANADA', 'CON PAPELETA', 'ANULADA'] }),
          T('Deuda', { t: 'sel', v: '(TODOS)', opts: estDeuda }),
          T('CIIU', { v: '' }), T('Infracción', { v: '' }), T('Vence', { v: '' }),
          T('Infractor', { v: '', wide: 1 }),
          T('Fiscalizador', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'RETO SANTOS, VÍCTOR', 'RÍOS MENDOZA, MARÍA', 'QUISPE PEÑA, JORGE'], wide: 1 }),
          T('Dirección del predio', { v: '', wide: 1 }),
          T('Rango desde', { t: 'date', v: '2026-07-01' }), T('Rango hasta', { t: 'date', v: '2026-08-13' }),
          T('Registradas desde', { t: 'date', v: '' }), T('Registradas hasta', { t: 'date', v: '' })
        ])
      ],
      actions: ['Exportar', 'Imprimir', 'Pantalla', 'Cancelar']
    },

    adm_padron_notificaciones: {
      mod: 'Infracciones administrativas', title: 'Padrón de notificaciones',
      endpoint: 'GET /api/v1/infracciones/administrativas/reportes/padron-notificaciones',
      desc: 'Relación de las notificaciones emitidas por el sistema y el estado de la deuda cuando ya existe papeleta.',
      filters: [T('Desde', { t: 'date', v: '2026-07-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Agrupado por', { t: 'sel', v: 'MES', opts: ['MES', 'FISCALIZADOR', 'INFRACCIÓN', 'CIIU'] }), T('Estado', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'NOTIFICADA', 'VENCIDA', 'SUBSANADA', 'CON PAPELETA'] })],
      table: {
        title: 'Notificaciones del periodo', count: '5 de 812',
        cols: ['Serie-Nº', 'Fecha', 'Infractor', 'Infracción', 'Fiscalizador', 'Vence', 'Papeleta', 'Deuda S/'], num: [7],
        rows: [
          ['001-004182', '02/08/2026', 'NOBLECILLA ARISMENDIZ SAC', 'A-014', 'RETO SANTOS, V.', '12/08/2026', 'P-002418', '2,675.00'],
          ['001-004183', '04/08/2026', 'CASTILLO PASCUALA, M. E.', 'A-021', 'RÍOS MENDOZA, M.', '14/08/2026', '—', '0.00'],
          ['001-004184', '07/08/2026', 'DÍAZ MADRID, J. C.', 'A-008', 'QUISPE PEÑA, J.', '17/08/2026', '—', '0.00'],
          ['001-004185', '11/08/2026', 'INVERSIONES DEL NORTE SAC', 'A-032', 'RETO SANTOS, V.', '21/08/2026', 'P-002419', '5,350.00'],
          ['001-004186', '12/08/2026', 'SUC. RUFINA MEDINA MEDINA', 'A-005', 'RÍOS MENDOZA, M.', '22/08/2026', '—', '0.00']
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    adm_notificaciones_vencidas: {
      mod: 'Infracciones administrativas', title: 'Notificaciones vencidas',
      endpoint: 'GET /api/v1/infracciones/administrativas/reportes/vencidas',
      desc: 'Notificaciones cuyo plazo de subsanación venció sin acreditarse el cumplimiento; habilitan la generación de la papeleta administrativa.',
      filters: [T('Vencidas al', { t: 'date', v: '2026-08-13' }), T('Fiscalizador', { t: 'sel', v: '(TODOS)', opts: ['(TODOS)', 'RETO SANTOS, VÍCTOR', 'RÍOS MENDOZA, MARÍA', 'QUISPE PEÑA, JORGE'] }), T('Infracción', { v: '' }), T('Con papeleta', { t: 'sel', v: 'NO', opts: ['TODAS', 'SÍ', 'NO'] })],
      table: {
        title: 'Notificaciones vencidas sin papeleta', count: '4 notificaciones',
        cols: ['Serie-Nº', 'Fecha', 'Infractor', 'Dirección', 'Infracción', 'Venció', 'Días vencidos'], num: [6],
        rows: [
          ['001-004182', '02/08/2026', 'NOBLECILLA ARISMENDIZ SAC', 'AV. JOSÉ DE LAMA 1180', 'A-014', '12/08/2026', '1'],
          ['001-004102', '18/07/2026', 'COMERCIAL SULLANA EIRL', 'CALLE BOLÍVAR 318', 'A-021', '30/07/2026', '14'],
          ['001-004044', '02/07/2026', 'RESTAURANT EL PARAÍSO', 'AV. CHAMPAGNAT 118', 'A-014', '14/07/2026', '30'],
          ['001-003988', '12/06/2026', 'BODEGA SANTA ROSA', 'URB. SANTA ROSA MZ. B LT. 4', 'A-005', '26/06/2026', '48']
        ]
      },
      actions: ['Generar papeleta', 'Imprimir', 'Excel']
    },

    adm_notificaciones_contribuyente: {
      mod: 'Infracciones administrativas', title: 'Notificaciones por contribuyente',
      endpoint: 'GET /api/v1/infracciones/administrativas/reportes/por-contribuyente',
      desc: 'Papeletas administrativas agrupadas por año y mes de cometida la infracción, con el estado de la multa y los datos de su pago.',
      filters: [T('Cod. Contribuyente', { v: '00000006551' }), T('Año', { t: 'sel', v: 'Todos', opts: ['Todos'].concat(yrs) }), T('Estado de deuda', { t: 'sel', v: '(TODOS)', opts: estDeuda }), T('Agrupado por', { t: 'sel', v: 'AÑO Y MES', opts: ['AÑO Y MES', 'INFRACCIÓN', 'ESTADO'] })],
      table: {
        title: 'Papeletas del contribuyente', count: '5 registros · S/ 8,025.00',
        cols: ['Año', 'Mes', 'Papeleta', 'Infracción', 'Multa S/', 'Recibo', 'Fec. pago', 'Estado'], num: [4],
        rows: [
          ['2026', 'Agosto', 'P-002418', 'A-014', '2,675.00', '—', '—', ['Pendiente', 'warn']],
          ['2026', 'Mayo', 'P-002204', 'A-021', '1,070.00', '000000048112', '20/05/2026', ['Cancelada', 'ok']],
          ['2025', 'Noviembre', 'P-001988', 'A-014', '2,140.00', '—', '—', ['Coactiva', 'bad']],
          ['2025', 'Julio', 'P-001842', 'A-005', '1,070.00', '000000044180', '02/08/2025', ['Cancelada', 'ok']],
          ['2024', 'Marzo', 'P-001412', 'A-032', '1,070.00', '—', '—', ['Prescrita', 'warn']]
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    adm_estado_cuenta: {
      mod: 'Infracciones administrativas', title: 'Estado de cuenta de papeleta administrativa',
      endpoint: 'GET /api/v1/infracciones/administrativas/estado-cuenta',
      desc: 'Deuda de una papeleta administrativa con su insoluto, reajuste, interés y gastos, y el importe con beneficio vigente.',
      filters: [T('Papeleta', { v: 'P-002418' }), T('Cod. Contribuyente', { v: '' }), T('Fecha de cálculo', { t: 'date', v: '2026-08-13' }), T('Incluir gastos', { t: 'sel', v: 'SÍ', opts: ['SÍ', 'NO'] })],
      table: {
        title: 'Detalle de la deuda', count: 'Total S/ 2,824.40',
        note: 'Insoluto 2,675.00 · Reajuste 0.00 · Interés 138.60 · Gastos 10.80',
        cols: ['Concepto', 'Cuota', 'Vencimiento', 'Insoluto S/', 'Interés S/', 'Gastos S/', 'Total S/'], num: [3, 4, 5, 6],
        rows: [
          ['MULTA ADMINISTRATIVA A-014', '001', '12/08/2026', '2,675.00', '138.60', '10.80', '2,824.40'],
          ['Beneficio por pronto pago (50 %)', '—', '31/08/2026', '1,337.50', '0.00', '10.80', '1,348.30']
        ]
      },
      actions: ['Imprimir', 'Voucher de pago']
    },

    adm_resolucion_gerencia: {
      mod: 'Infracciones administrativas', title: 'Resolución de gerencia', kind: 'report',
      endpoint: 'POST /api/v1/infracciones/administrativas/resoluciones',
      desc: 'Resolución que resuelve el procedimiento sancionador y determina la multa administrativa exigible.',
      report: {
        code: 'RG-2026-000912', date: '13 de agosto de 2026',
        title: 'Resolución de gerencia',
        subtitle: 'Procedimiento administrativo sancionador — multa administrativa',
        meta: [
          { k: 'Nº de resolución', v: '000912-2026-GM/MPS' },
          { k: 'Papeleta', v: 'P-002418' },
          { k: 'Notificación previa', v: '001-004182 del 02/08/2026' },
          { k: 'Infractor', v: 'NOBLECILLA ARISMENDIZ SAC' },
          { k: 'R.U.C.', v: '20525118447' },
          { k: 'Establecimiento', v: 'AV. JOSÉ DE LAMA 1180' }
        ],
        cols: ['Concepto', 'Base legal', 'Importe S/'], num: [2],
        rows: [
          ['Multa A-014 — funcionar sin licencia', 'CUIS — Ordenanza Municipal 018-2024', '2,675.00'],
          ['Interés moratorio', 'Art. 33 Código Tributario', '138.60'],
          ['Gastos administrativos', 'TUPA vigente', '10.80'],
          ['Total exigible', '—', '2,824.40']
        ],
        footer: 'Contra la presente resolución procede recurso de reconsideración o apelación dentro de los quince días hábiles siguientes a su notificación.'
      }
    },

    adm_notificacion_resolucion: {
      mod: 'Infracciones administrativas', title: 'Notificación de resolución de gerencia', kind: 'report',
      endpoint: 'POST /api/v1/infracciones/administrativas/resoluciones/{id}/notificacion',
      desc: 'Cédula de notificación de la resolución de gerencia, con el acuse de recibo y los datos del notificador y testigos.',
      report: {
        code: 'NOT-2026-001842', date: '13 de agosto de 2026',
        title: 'Notificación de resolución de gerencia',
        subtitle: 'Cédula de notificación — Ley 27444',
        meta: [
          { k: 'Nº de notificación', v: '001842-2026' },
          { k: 'Resolución', v: '000912-2026-GM/MPS' },
          { k: 'Administrado', v: 'NOBLECILLA ARISMENDIZ SAC' },
          { k: 'Domicilio', v: 'AV. JOSÉ DE LAMA 1180 — SULLANA' },
          { k: 'Nº de visita', v: '1' },
          { k: 'Tipo de notificación', v: 'NOTIFICACIÓN CON ÉXITO' }
        ],
        cols: ['Dato del acto de notificación', 'Detalle'], num: [],
        rows: [
          ['Fecha y hora', '13/08/2026 — 10:15'],
          ['Recibido por', 'REPRESENTANTE — RUIZ INGA, FERNANDO'],
          ['Documento del receptor', 'DNI 10027723'],
          ['Características de la vivienda', 'LOCAL COMERCIAL DE UN PISO, FACHADA DE LADRILLO'],
          ['Notificador', 'RETO SANTOS, VÍCTOR'],
          ['Testigo 01', '—']
        ],
        footer: 'La notificación surte efecto el día hábil siguiente de su recepción, conforme al artículo 25 del Texto Único Ordenado de la Ley del Procedimiento Administrativo General.'
      }
    },

    adm_resumen_recaudacion: {
      mod: 'Infracciones administrativas', title: 'Resumen de recaudación de papeletas',
      endpoint: 'GET /api/v1/infracciones/administrativas/reportes/resumen-recaudacion',
      desc: 'Recaudación por multas administrativas por año y mes, diferenciando cobranza ordinaria, coactiva y por convenio.',
      filters: [T('Año', { t: 'sel', v: '2026', opts: yrs }), T('Agrupado por', { t: 'sel', v: 'MES', opts: ['MES', 'INFRACCIÓN', 'TIPO DE COBRANZA'] }), T('Tipo de cobranza', { t: 'sel', v: 'Todas', opts: ['Todas', 'ORDINARIA', 'COACTIVA', 'CONVENIO'] }), T('Caja', { t: 'sel', v: 'Todas', opts: ['Todas', 'CAJA 01', 'CAJA 02', 'CAJA 03'] })],
      table: {
        title: 'Recaudación por mes', count: 'Enero — agosto 2026 · S/ 96,412.00',
        cols: ['Mes', 'Papeletas pagadas', 'Ordinaria S/', 'Coactiva S/', 'Convenios S/', 'Total S/'], num: [1, 2, 3, 4, 5],
        rows: [
          ['Enero', '42', '8,412.00', '2,140.00', '1,070.00', '11,622.00'],
          ['Febrero', '38', '7,490.00', '1,070.00', '2,140.00', '10,700.00'],
          ['Marzo', '51', '11,235.00', '3,210.00', '1,070.00', '15,515.00'],
          ['Abril', '44', '9,630.00', '2,140.00', '1,070.00', '12,840.00'],
          ['Mayo', '48', '10,700.00', '1,070.00', '2,140.00', '13,910.00'],
          ['Junio', '39', '8,025.00', '2,140.00', '1,070.00', '11,235.00'],
          ['Julio', '52', '12,840.00', '3,210.00', '2,140.00', '18,190.00'],
          ['Agosto (al 13)', '9', '2,400.00', '0.00', '0.00', '2,400.00']
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    adm_codigos_reporte: {
      mod: 'Infracciones administrativas', title: 'Reporte de códigos de infracción administrativa',
      endpoint: 'GET /api/v1/infracciones/administrativas/codigos/reporte',
      desc: 'Relación impresa del cuadro único de infracciones y sanciones vigente, con la base de cálculo y la sanción no pecuniaria de cada código.',
      filters: [T('Código', { v: '' }), T('Descripción contiene', { v: '' }), T('Estado', { t: 'sel', v: 'VIGENTES', opts: ['VIGENTES', 'DEROGADOS', 'TODOS'] }), T('Ordenado por', { t: 'sel', v: 'CÓDIGO', opts: ['CÓDIGO', 'DESCRIPCIÓN', 'IMPORTE'] })],
      table: {
        title: 'Códigos tipificados', count: '6 de 184 códigos',
        cols: ['Código', 'Infracción', 'Base', '% UIT', 'Multa S/', 'Sanción no pecuniaria', 'Estado'], num: [3, 4],
        rows: [
          ['A-005', 'Ocupar la vía pública sin autorización', 'UIT', '10', '535.00', 'Retiro de bienes', ['Vigente', 'ok']],
          ['A-008', 'Arrojar residuos sólidos en la vía pública', 'UIT', '20', '1,070.00', '—', ['Vigente', 'ok']],
          ['A-014', 'Funcionar sin licencia de funcionamiento', 'UIT', '50', '2,675.00', 'Clausura temporal', ['Vigente', 'ok']],
          ['A-021', 'Abrir establecimiento sin autorización municipal', 'UIT', '20', '1,070.00', 'Clausura', ['Vigente', 'ok']],
          ['A-032', 'Construir sin licencia de edificación', 'Valor de obra', '10', '5,350.00', 'Paralización de obra', ['Vigente', 'ok']],
          ['A-041', 'Instalar anuncio sin autorización', 'UIT', '15', '802.50', 'Retiro del anuncio', ['Vigente', 'ok']]
        ]
      },
      actions: ['Imprimir', 'Excel']
    },

    adm_valores: {
      mod: 'Infracciones administrativas', title: 'Generación de valores administrativa',
      endpoint: 'POST /api/v1/infracciones/administrativas/valores/generacion-masiva',
      desc: 'Selecciona un conjunto de papeletas administrativas con deuda según un criterio y genera masivamente un valor por papeleta para su impresión y notificación posterior.',
      filters: [T('Código / descripción del criterio', { v: '', wide: 1 })],
      table: {
        title: 'Criterios registrados', count: '2 criterios',
        cols: ['Cod. Criterio', 'Descripción', 'Tipo Rec.', 'Fec. Ini.', 'Fec. Fin.', 'Est.'], num: [],
        rows: [
          ['00000000400', 'RM PAPELETAS ADMINISTRATIVAS 013 AÑO 2025', 'RMPAD', '01/01/2025', '31/12/2025', ['A', 'ok']],
          ['00000000418', 'RM PAPELETAS ADMINISTRATIVAS 001 AÑO 2026', 'RMPAD', '01/01/2026', '31/07/2026', ['A', 'ok']]
        ]
      },
      sections: [
        S('Criterio', [
          T('Código de criterio', { t: 'ro', v: '00000000418' }),
          T('Descripción', { v: 'RM PAPELETAS ADMINISTRATIVAS 001 AÑO 2026', wide: 1 }),
          T('Fec. inicio', { t: 'date', v: '2026-01-01' }), T('Fec. fin', { t: 'date', v: '2026-07-31' }),
          T('Tipo de recaudo', { t: 'sel', v: '035 — RM PAPELETAS ADMINISTRATIVAS', opts: ['035 — RM PAPELETAS ADMINISTRATIVAS', '003 — RS PAPELETAS DE TRÁNSITO'] }),
          T('Vencimiento', { t: 'date', v: '2026-09-15' }),
          T('Oficina', { t: 'sel', v: '999999 — OFICINA NO ESPECIFICADA', opts: ['113300 — SUBGERENCIA DE FISCALIZACIÓN TRIBUTARIA', '113100 — SUBGERENCIA DE RECAUDACIÓN', '999999 — OFICINA NO ESPECIFICADA'], wide: 1 })
        ]),
        S('Recaudo / papeletas', [
          T('Papeleta', { v: '' }), T('Placa / establecimiento', { v: '' })
        ], 'Buscar papeletas y agregar al criterio')
      ],
      actions: ['Nuevo', 'Modificar', 'Guardar', 'Procesar', 'Anular', 'Imprimir']
    }

  });
})();
