/* SGTM — catálogo de pantallas: Fiscalización, Tránsito, Tesorería y Consultas.
   Campos tomados del Manual de Usuario SGTM (figuras 100-180). */
(function () {
  var T = function (label, o) { return Object.assign({ label: label, t: 'text', v: '', ph: '', opts: null, wide: 0, on: false }, o || {}); };
  var S = function (label, fields, hint) { return { label: label, fields: fields, hint: hint || '' }; };
  var W = window.SGTM_SCREENS = window.SGTM_SCREENS || {};
  var yrs = ['2026', '2025', '2024', '2023', '2022', '2021', '2020'];

  Object.assign(W, {

    /* ── FISCALIZACIÓN ────────────────────────────────────── */

    fisc_programa: {
      mod: 'Fiscalización', title: 'Programación de fiscalización',
      endpoint: 'POST /api/v1/fiscalizacion/programas',
      desc: 'Selección de la muestra a inspeccionar por sector y criterio de riesgo, con el fiscalizador asignado y el plazo del programa.',
      filters: [T('Nº de programa', { v: 'PF-2026-014' }), T('Ejercicio', { t: 'sel', v: '2026', opts: yrs }), T('Tipo', { t: 'sel', v: 'Todos', opts: ['Todos', 'PREDIAL MASIVO', 'PREDIAL SELECTIVO', 'VEHICULAR', 'LICENCIAS', 'OMISOS', 'SUBVALUACIÓN'] }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'EN PREPARACIÓN', 'APROBADO', 'EN EJECUCIÓN', 'CERRADO'] })],
      sections: [
        S('Datos del programa', [
          T('Nº de programa', { t: 'ro', v: 'PF-2026-014' }),
          T('Ejercicio', { t: 'sel', v: '2026', opts: yrs }),
          T('Tipo de programa', { t: 'sel', v: 'PREDIAL SELECTIVO', opts: ['PREDIAL MASIVO', 'PREDIAL SELECTIVO', 'VEHICULAR', 'LICENCIAS', 'OMISOS', 'SUBVALUACIÓN'] }),
          T('Sector', { t: 'sel', v: '02', opts: ['01', '02', '03', '04', '05'] }),
          T('Criterio de riesgo', { t: 'sel', v: 'SUBVALUACIÓN PROBABLE', opts: ['SUBVALUACIÓN PROBABLE', 'OMISO A LA DECLARACIÓN', 'AMPLIACIÓN NO DECLARADA', 'USO DISTINTO AL DECLARADO', 'DEUDA ALTA'] }),
          T('Fiscalizador asignado', { t: 'sel', v: 'R. MENDOZA CRUZ', opts: ['R. MENDOZA CRUZ', 'L. PEÑA SANDOVAL', 'A. VÍLCHEZ ROJAS'] }),
          T('Fecha de inicio', { t: 'date', v: '2026-08-17' }),
          T('Fecha de término', { t: 'date', v: '2026-09-30' }),
          T('Tamaño de muestra', { v: '96' }),
          T('Estado', { t: 'sel', v: 'EN EJECUCIÓN', opts: ['EN PREPARACIÓN', 'APROBADO', 'EN EJECUCIÓN', 'CERRADO'] })
        ])
      ],
      table: {
        title: 'Predios seleccionados', count: '96 predios · 4 visibles',
        cols: ['Predio', 'Contribuyente', 'Uso declarado', 'Área decl. m²', 'Riesgo', 'Estado'], num: [3],
        rows: [
          ['02-014-D-14-01', 'MEDINA MEDINA, RUFINA (SUC.)', 'Casa habitación', '164.50', 'Alto', ['Programado', 'warn']],
          ['02-014-D-18-00', 'SILVA CÓRDOVA, ANA', 'Comercio', '82.00', 'Alto', ['Inspeccionado', 'ok']],
          ['02-016-A-02-00', 'REYES CHUNGA, PEDRO', 'Casa habitación', '120.00', 'Medio', ['Programado', 'warn']],
          ['02-016-A-09-00', 'INVERSIONES DEL NORTE SAC', 'Industria', '640.00', 'Alto', ['Con acta', 'ok']]
        ]
      },
      actions: ['Generar muestra', 'Asignar fiscalizador', 'Aprobar programa']
    },

    fisc_predial: {
      mod: 'Fiscalización', title: 'Fiscalización predial — acta de inspección',
      endpoint: 'POST /api/v1/fiscalizacion/predial/actas',
      desc: 'Formulario de campo optimizado para tablet. Contrasta lo verificado con lo declarado y determina si corresponde emitir resolución de determinación.',
      sections: [
        S('Datos de la visita', [
          T('Nº de acta', { t: 'ro', v: 'ACT-2026-00418' }),
          T('Programa', { t: 'ro', v: 'PF-2026-014' }),
          T('Código predial', { t: 'ro', v: '02-014-D-14-01' }),
          T('Contribuyente', { t: 'ro', v: 'MEDINA MEDINA, RUFINA (SUC.)', wide: 1 }),
          T('Fecha de inspección', { t: 'date', v: '2026-08-12' }),
          T('Hora', { v: '10:25' }),
          T('Fiscalizador', { t: 'ro', v: 'R. MENDOZA CRUZ' }),
          T('Persona que atiende', { v: 'MEDINA CHÁVEZ, ROSA' }),
          T('Vínculo con el predio', { t: 'sel', v: 'FAMILIAR', opts: ['PROPIETARIO', 'FAMILIAR', 'INQUILINO', 'ENCARGADO', 'NADIE ATENDIÓ'] }),
          T('Resultado de la visita', { t: 'sel', v: 'INSPECCIÓN REALIZADA', opts: ['INSPECCIÓN REALIZADA', 'PREDIO CERRADO', 'SE NEGÓ A LA INSPECCIÓN', 'DIRECCIÓN NO UBICADA'] })
        ]),
        S('Verificación de campo', [
          T('Uso verificado', { t: 'sel', v: 'COMERCIO', opts: ['CASA HABITACIÓN', 'COMERCIO', 'INDUSTRIA', 'SERVICIOS', 'TERRENO SIN CONSTRUIR'] }),
          T('Uso declarado', { t: 'ro', v: 'CASA HABITACIÓN' }),
          T('Área de terreno verificada (m²)', { v: '210.00' }),
          T('Área construida verificada (m²)', { v: '198.00' }),
          T('Área construida declarada (m²)', { t: 'ro', v: '164.50' }),
          T('Diferencia (m²)', { t: 'ro', v: '+33.50' }),
          T('Nº de pisos verificados', { v: '2' }),
          T('MEP verificado', { t: 'sel', v: '02 — LADRILLO', opts: ['01 — CONCRETO', '02 — LADRILLO', '03 — ADOBE', '04 — QUINCHA', '05 — MADERA'] }),
          T('ECS verificado', { t: 'sel', v: '02 — BUENO', opts: ['01 — MUY BUENO', '02 — BUENO', '03 — REGULAR', '04 — MALO'] }),
          T('Servicios básicos', { t: 'sel', v: 'AGUA, DESAGÜE Y LUZ', opts: ['AGUA, DESAGÜE Y LUZ', 'AGUA Y LUZ', 'SOLO LUZ', 'NINGUNO'] })
        ]),
        S('Hallazgos y evidencia', [
          T('Hallazgo principal', { t: 'sel', v: 'AMPLIACIÓN NO DECLARADA', opts: ['SIN OBSERVACIONES', 'AMPLIACIÓN NO DECLARADA', 'USO DISTINTO AL DECLARADO', 'OMISO A LA DECLARACIÓN', 'PREDIO SUBVALUADO', 'PREDIO INEXISTENTE'] }),
          T('Genera determinación', { t: 'chk', on: true, ph: 'Deriva a resolución de determinación' }),
          T('Fotografías', { t: 'ro', v: '4 archivos adjuntos' }),
          T('Croquis / georreferencia', { t: 'ro', v: '-4.902315, -80.685442' }),
          T('Observaciones del fiscalizador', { t: 'area', wide: 1, v: 'Segundo piso construido en 2011 destinado a bodega; no figura en la declaración jurada.' }),
          T('Firma del administrado', { t: 'ro', v: 'Capturada — 10:52' }),
          T('Se negó a firmar', { t: 'chk', on: false, ph: 'Dejar constancia en el acta' })
        ])
      ],
      actions: ['Guardar borrador', 'Cerrar acta', 'Generar determinación']
    },

    fisc_vehicular: {
      mod: 'Fiscalización', title: 'Fiscalización vehicular',
      endpoint: 'POST /api/v1/fiscalizacion/vehicular',
      desc: 'Cruce del padrón vehicular con la información registral y de SUNAT para detectar vehículos afectos no declarados o con valor subvaluado.',
      filters: [T('Placa', { v: '' }), T('Ejercicio', { t: 'sel', v: '2026', opts: yrs }), T('Origen del cruce', { t: 'sel', v: 'Todos', opts: ['Todos', 'SUNARP', 'SUNAT', 'MTC', 'DECLARACIÓN'] }), T('Hallazgo', { t: 'sel', v: 'Todos', opts: ['Todos', 'NO DECLARADO', 'SUBVALUADO', 'BAJA INDEBIDA', 'CONFORME'] })],
      table: {
        title: 'Vehículos observados', count: '4 de 618',
        cols: ['Placa', 'Contribuyente', 'Origen', 'Valor declarado S/', 'Valor referencial S/', 'Hallazgo', 'Deuda omitida S/'], num: [3, 4, 6],
        rows: [
          ['V1H-882', 'CASTILLO PASCUALA, MARÍA E.', 'SUNARP', '0.00', '112,800.00', ['No declarado', 'bad'], '3,384.00'],
          ['B7T-221', 'REYES CHUNGA, PEDRO', 'SUNAT', '38,000.00', '62,400.00', ['Subvaluado', 'warn'], '732.00'],
          ['T4M-119', 'INVERSIONES DEL NORTE SAC', 'MTC', '84,000.00', '84,000.00', ['Conforme', 'ok'], '0.00'],
          ['C2P-704', 'DÍAZ MADRID, JULIO CÉSAR', 'SUNARP', '0.00', '48,200.00', ['Baja indebida', 'bad'], '1,446.00']
        ],
        note: 'El valor referencial proviene de la tabla del MEF vigente para el año de fabricación del vehículo.'
      },
      actions: ['Importar cruce', 'Notificar requerimiento', 'Generar determinación']
    },

    fisc_resultados: {
      mod: 'Fiscalización', title: 'Resultados y determinaciones',
      endpoint: 'GET /api/v1/fiscalizacion/resultados',
      desc: 'Diferencias detectadas, deuda omitida por ejercicio y estado del valor emitido a partir de cada acta.',
      filters: [T('Programa', { t: 'sel', v: 'PF-2026-014', opts: ['PF-2026-014', 'PF-2026-011', 'PF-2025-032'] }), T('Hallazgo', { t: 'sel', v: 'Todos', opts: ['Todos', 'AMPLIACIÓN NO DECLARADA', 'OMISO', 'USO DISTINTO', 'SUBVALUACIÓN'] }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'PENDIENTE', 'DETERMINADO', 'NOTIFICADO', 'RECLAMADO'] })],
      table: {
        title: 'Actas con diferencia determinada', count: '4 de 96',
        cols: ['Acta', 'Predio', 'Hallazgo', 'Dif. m²', 'Ejercicios', 'Deuda omitida S/', 'Estado'], num: [3, 5],
        rows: [
          ['ACT-2026-00418', '02-014-D-14-01', 'Ampliación no declarada', '+33.50', '2022 — 2026', '1,842.60', ['Determinado', 'warn']],
          ['ACT-2026-00419', '02-014-D-18-00', 'Uso distinto al declarado', '0.00', '2024 — 2026', '944.10', ['Notificado', 'ok']],
          ['ACT-2026-00421', '02-016-A-09-00', 'Omiso a la declaración', '+640.00', '2021 — 2026', '18,412.00', ['Reclamado', 'bad']],
          ['ACT-2026-00424', '02-016-A-02-00', 'Sin observaciones', '0.00', '—', '0.00', ['Conforme', 'ok']]
        ],
        note: 'La deuda omitida incluye insoluto, reajuste e interés moratorio calculado a la fecha de emisión de la resolución de determinación.'
      },
      totals: [
        { label: 'Actas cerradas', value: '96' },
        { label: 'Con diferencia', value: '61' },
        { label: 'Deuda determinada', value: 'S/ 214,882.40' },
        { label: 'Efectividad', value: '63.5 %', strong: 1 }
      ],
      actions: ['Exportar Excel', 'Emitir resoluciones de determinación']
    },

    fisc_omisos: {
      mod: 'Fiscalización', title: 'Omisos y subvaluadores',
      endpoint: 'GET /api/v1/fiscalizacion/omisos',
      desc: 'Contribuyentes con predio en catastro pero sin declaración en rentas, y declaraciones cuyo autovalúo está por debajo del valor catastral verificado.',
      filters: [T('Ejercicio', { t: 'sel', v: '2026', opts: yrs }), T('Sector', { t: 'sel', v: 'Todos', opts: ['Todos', '01', '02', '03', '04', '05'] }), T('Condición', { t: 'sel', v: 'Todas', opts: ['Todas', 'OMISO', 'SUBVALUADOR'] })],
      table: {
        title: 'Contribuyentes detectados', count: '4 de 3,418',
        cols: ['Cod. Ref. Catastral', 'Titular', 'Condición', 'Valor catastral S/', 'Valor declarado S/', 'Diferencia S/', 'Impuesto omitido S/'], num: [3, 4, 5, 6],
        rows: [
          ['200601010160020101001', 'REYES CHUNGA, PEDRO', ['Omiso', 'bad'], '96,400.00', '0.00', '96,400.00', '478.40'],
          ['200601010150010101001', 'MEDINA MEDINA, RUFINA (SUC.)', ['Subvaluador', 'warn'], '178,200.00', '132,196.75', '46,003.25', '276.02'],
          ['200601020210070100000', 'CASTILLO PASCUALA, MARÍA E.', ['Subvaluador', 'warn'], '44,800.00', '38,420.00', '6,380.00', '38.28'],
          ['200601030880010101001', 'INVERSIONES DEL NORTE SAC', ['Omiso', 'bad'], '842,000.00', '0.00', '842,000.00', '7,984.40']
        ]
      },
      actions: ['Exportar', 'Programar fiscalización', 'Notificar esquela']
    },

    /* ── TRÁNSITO E INFRACCIONES ──────────────────────────── */

    papeletas: {
      mod: 'Tránsito', title: 'Papeletas de infracción de tránsito',
      endpoint: 'GET /api/v1/transito/papeletas',
      desc: 'Papeletas levantadas por el inspector municipal, con el código del Reglamento Nacional de Tránsito, la sanción en porcentaje de UIT y la medida preventiva aplicada.',
      filters: [T('Nro. Papeleta', { v: '' }), T('Placa', { v: 'T2G-418' }), T('Documento del infractor', { v: '' }), T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Estado', { t: 'sel', v: 'Todas', opts: ['Todas', 'PENDIENTE', 'PAGADA', 'CON DESCARGO', 'FIRME', 'COACTIVA', 'ANULADA'] })],
      table: {
        title: 'Papeletas encontradas', count: '4 de 12,844',
        cols: ['Nro. Papeleta', 'Fecha', 'Placa', 'Infractor', 'Código', 'Gravedad', 'Multa S/', 'Estado'], num: [6],
        rows: [
          ['MPS-2026-041182', '02/08/2026', 'T2G-418', 'CASTILLO PASCUALA, MARÍA E.', 'M-02', ['Muy grave', 'bad'], '535.00', ['Pendiente', 'warn']],
          ['MPS-2026-040877', '21/07/2026', 'V1H-882', 'DÍAZ MADRID, JULIO CÉSAR', 'G-58', ['Grave', 'warn'], '428.00', ['Con descargo', 'warn']],
          ['MPS-2026-040412', '09/06/2026', 'B7T-221', 'REYES CHUNGA, PEDRO', 'L-11', ['Leve', 'ok'], '214.00', ['Pagada', 'ok']],
          ['MPS-2025-038119', '14/11/2025', 'T2G-418', 'CASTILLO PASCUALA, MARÍA E.', 'G-40', ['Grave', 'warn'], '428.00', ['Coactiva', 'bad']]
        ]
      },
      tabs: [
        { label: 'Datos de la papeleta', sections: [
          S('Intervención', [
            T('Nro. Papeleta', { t: 'ro', v: 'MPS-2026-041182' }),
            T('Fecha', { t: 'date', v: '2026-08-02' }), T('Hora', { v: '18:40' }),
            T('Lugar de la intervención', { v: 'AV. JOSÉ DE LAMA CUADRA 12', wide: 1 }),
            T('Inspector municipal', { t: 'sel', v: 'A. VÍLCHEZ ROJAS', opts: ['A. VÍLCHEZ ROJAS', 'L. PEÑA SANDOVAL', 'J. RUIZ PALACIOS'] }),
            T('Nº de credencial', { t: 'ro', v: 'IM-0412' }),
            T('Supervisor', { t: 'sel', v: 'C. ANCAJIMA FLORES', opts: ['C. ANCAJIMA FLORES', 'R. MENDOZA CRUZ'] })
          ]),
          S('Infractor y vehículo', [
            T('Documento', { v: '44218937' }),
            T('Nombre del infractor', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA' }),
            T('Nro. de licencia', { v: 'Q44218937' }),
            T('Clase / categoría', { t: 'sel', v: 'A-I', opts: ['A-I', 'A-IIa', 'A-IIb', 'A-IIIa'] }),
            T('Placa', { v: 'T2G-418' }),
            T('Clase de vehículo', { t: 'sel', v: 'AUTOMÓVIL', opts: ['AUTOMÓVIL', 'CAMIONETA', 'MOTOCICLETA', 'ÓMNIBUS', 'CAMIÓN', 'MOTOTAXI'] }),
            T('Propietario del vehículo', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA' }),
            T('Empresa de transporte', { v: '' })
          ])
        ] },
        { label: 'Infracción y sanción', sections: [
          S('Sanción', [
            T('Código de infracción', { t: 'sel', v: 'M-02', opts: ['M-02', 'M-08', 'G-40', 'G-58', 'L-11'] }),
            T('Descripción', { t: 'ro', v: 'CONDUCIR CON PRESENCIA DE ALCOHOL EN LA SANGRE', wide: 1 }),
            T('Gravedad', { t: 'ro', v: 'MUY GRAVE' }),
            T('Base UIT (S/)', { t: 'ro', v: '5,350.00' }),
            T('Porcentaje de UIT', { t: 'ro', v: '10 %' }),
            T('Valor de la multa (S/)', { t: 'ro', v: '535.00' }),
            T('Puntos acumulados', { t: 'ro', v: '50' }),
            T('Medida preventiva', { t: 'sel', v: 'RETENCIÓN DE LICENCIA', opts: ['NINGUNA', 'RETENCIÓN DE LICENCIA', 'INTERNAMIENTO DEL VEHÍCULO', 'REMOCIÓN DEL VEHÍCULO'] }),
            T('Depósito municipal', { t: 'sel', v: 'NO APLICA', opts: ['NO APLICA', 'DEPÓSITO SULLANA NORTE', 'DEPÓSITO BELLAVISTA'] }),
            T('Descuento por pronto pago (5 días)', { t: 'ro', v: '− S/ 214.00' })
          ])
        ] },
        { label: 'Cancelación', sections: [
          S('Estado del pago', [
            T('Canceló', { t: 'chk', on: false, ph: 'Papeleta pagada' }),
            T('Nro. de recibo', { v: '' }),
            T('Fecha de pago', { t: 'date' }),
            T('Importe pagado (S/)', { t: 'ro', v: '0.00' }),
            T('Anuló', { t: 'chk', on: false, ph: 'Papeleta anulada' }),
            T('Referencia de anulación', { v: '', wide: 1 }),
            T('Motivo de anulación', { t: 'sel', v: '—', opts: ['—', 'ERROR EN EL REGISTRO', 'DESCARGO FUNDADO', 'DUPLICADA', 'RESOLUCIÓN JUDICIAL'] })
          ], 'Opcional')
        ] },
        { label: 'Observaciones', sections: [
          S('Notas de la intervención', [T('Observaciones', { t: 'area', wide: 1, ph: 'Detalle de la intervención y firmas' })], 'Opcional')
        ] }
      ],
      actions: ['Nuevo', 'Notificar', 'Imprimir', 'Guardar']
    },

    codigos_transito: {
      mod: 'Tránsito', title: 'Tabla de códigos de infracción de tránsito',
      endpoint: 'GET /api/v1/transito/codigos',
      desc: 'Catálogo del Reglamento Nacional de Tránsito con la sanción, los puntos y la medida preventiva que el sistema aplica al registrar cada papeleta.',
      filters: [T('Código', { v: '' }), T('Gravedad', { t: 'sel', v: 'Todas', opts: ['Todas', 'MUY GRAVE', 'GRAVE', 'LEVE'] }), T('Texto de la infracción', { v: '' })],
      table: {
        title: 'Códigos vigentes', count: '6 de 342',
        cols: ['Código', 'Descripción', 'Gravedad', '% UIT', 'Multa S/', 'Puntos', 'Medida preventiva'], num: [3, 4, 5],
        rows: [
          ['M-02', 'Conducir con presencia de alcohol en la sangre', ['Muy grave', 'bad'], '10 %', '535.00', '50', 'Retención de licencia'],
          ['M-08', 'Conducir sin licencia vigente', ['Muy grave', 'bad'], '8 %', '428.00', '50', 'Internamiento del vehículo'],
          ['M-20', 'Prestar servicio de transporte sin autorización', ['Muy grave', 'bad'], '12 %', '642.00', '50', 'Internamiento del vehículo'],
          ['G-40', 'Estacionar en zona rígida o prohibida', ['Grave', 'warn'], '8 %', '428.00', '20', 'Remoción del vehículo'],
          ['G-58', 'Exceder la velocidad permitida', ['Grave', 'warn'], '8 %', '428.00', '20', 'Ninguna'],
          ['L-11', 'No portar el certificado SOAT vigente', ['Leve', 'ok'], '4 %', '214.00', '10', 'Ninguna']
        ]
      },
      actions: ['Nuevo código', 'Guardar']
    },

    transito_descargos: {
      mod: 'Tránsito', title: 'Descargos y reclamos de papeletas',
      endpoint: 'POST /api/v1/transito/descargos',
      desc: 'Escrito de descargo presentado dentro del plazo, su evaluación y la resolución que declara fundada o infundada la impugnación.',
      filters: [T('Nº de expediente', { v: '' }), T('Papeleta', { v: 'MPS-2026-040877' }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'EN EVALUACIÓN', 'FUNDADO', 'INFUNDADO', 'IMPROCEDENTE'] })],
      sections: [
        S('Solicitud', [
          T('Nº de expediente', { t: 'ro', v: '2026-1188' }),
          T('Papeleta impugnada', { v: 'MPS-2026-040877' }),
          T('Fecha de presentación', { t: 'date', v: '2026-07-28' }),
          T('Dentro del plazo (5 días hábiles)', { t: 'chk', on: true, ph: 'Presentado en plazo' }),
          T('Tipo de recurso', { t: 'sel', v: 'DESCARGO', opts: ['DESCARGO', 'RECONSIDERACIÓN', 'APELACIÓN', 'NULIDAD'] }),
          T('Fundamento del administrado', { t: 'area', wide: 1, v: 'Señala que el vehículo se encontraba detenido por desperfecto mecánico y adjunta constancia del taller.' })
        ]),
        S('Evaluación y resolución', [
          T('Área evaluadora', { t: 'sel', v: 'SUBGERENCIA DE TRÁNSITO', opts: ['SUBGERENCIA DE TRÁNSITO', 'GERENCIA DE ADMINISTRACIÓN TRIBUTARIA', 'EJECUTORÍA COACTIVA'] }),
          T('Nº de resolución', { v: 'RSG-0812-2026-MPS' }),
          T('Fecha de resolución', { t: 'date', v: '2026-08-08' }),
          T('Sentido del fallo', { t: 'sel', v: 'INFUNDADO', opts: ['FUNDADO', 'INFUNDADO', 'IMPROCEDENTE', 'FUNDADO EN PARTE'] }),
          T('Efecto sobre la multa', { t: 'sel', v: 'SE MANTIENE', opts: ['SE MANTIENE', 'SE DEJA SIN EFECTO', 'SE REDUCE'] }),
          T('Sustento de la resolución', { t: 'area', wide: 1 })
        ])
      ],
      actions: ['Registrar descargo', 'Resolver', 'Notificar al administrado']
    },

    internamiento: {
      mod: 'Tránsito', title: 'Internamiento vehicular',
      endpoint: 'GET /api/v1/transito/internamientos',
      desc: 'Control de vehículos en el depósito municipal, con el cómputo diario de la tasa de custodia y los requisitos para la liberación.',
      filters: [T('Placa', { v: '' }), T('Depósito', { t: 'sel', v: 'Todos', opts: ['Todos', 'DEPÓSITO SULLANA NORTE', 'DEPÓSITO BELLAVISTA'] }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'INTERNADO', 'LIBERADO', 'EN ABANDONO'] })],
      table: {
        title: 'Vehículos en depósito', count: '3 de 118',
        cols: ['Placa', 'Papeleta', 'Fecha de ingreso', 'Días', 'Tasa diaria S/', 'Custodia S/', 'Estado'], num: [3, 4, 5],
        rows: [
          ['T2G-418', 'MPS-2026-041182', '02/08/2026', '11', '18.00', '198.00', ['Internado', 'bad']],
          ['C2P-704', 'MPS-2026-040991', '28/07/2026', '16', '18.00', '288.00', ['Internado', 'bad']],
          ['B7T-221', 'MPS-2026-040412', '09/06/2026', '3', '18.00', '54.00', ['Liberado', 'ok']]
        ],
        note: 'Para liberar el vehículo el administrado debe cancelar la multa, la tasa de custodia y acreditar la titularidad y el SOAT vigente.'
      },
      sections: [
        S('Liberación del vehículo', [
          T('Placa', { v: 'T2G-418' }),
          T('Fecha de liberación', { t: 'date', v: '2026-08-13' }),
          T('Multa cancelada', { t: 'chk', on: false, ph: 'Recibo de la papeleta' }),
          T('Custodia cancelada', { t: 'chk', on: false, ph: 'Recibo de la tasa diaria' }),
          T('SOAT vigente acreditado', { t: 'chk', on: true, ph: 'Copia del certificado' }),
          T('Persona que retira', { v: '' }),
          T('Documento de quien retira', { v: '' })
        ])
      ],
      actions: ['Registrar ingreso', 'Liberar vehículo']
    },

    infracciones_adm: {
      mod: 'Infracciones', title: 'Infracción administrativa',
      endpoint: 'GET /api/v1/infracciones/actas',
      desc: 'Procedimiento sancionador municipal: notificación preventiva, acta de constatación y resolución de infracción y sanción con multa y medida complementaria.',
      filters: [T('Nro. de acta', { v: '' }), T('Administrado', { v: '' }), T('Código CUIS', { t: 'sel', v: 'Todos', opts: ['Todos', 'C-101', 'C-214', 'S-018', 'A-042'] }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'PREVENTIVA', 'CONSTATADA', 'SANCIONADA', 'PAGADA', 'COACTIVA'] })],
      table: {
        title: 'Procedimientos sancionadores', count: '4 de 2,118',
        cols: ['Nro. acta', 'Administrado', 'CUIS', 'Infracción', '% UIT', 'Multa S/', 'Medida complementaria', 'Estado'], num: [4, 5],
        rows: [
          ['AC-2026-0912', 'NOBLECILLA ARISMENDIZ SAC', 'C-101', 'Funcionar sin licencia municipal', '50 %', '2,675.00', 'Clausura temporal', ['Sancionada', 'bad']],
          ['AC-2026-0918', 'RESTAURANT SABOR Y SAZÓN', 'S-018', 'Deficiencias de salubridad', '20 %', '1,070.00', 'Retiro de productos', ['Constatada', 'warn']],
          ['AC-2026-0921', 'DÍAZ MADRID, JULIO CÉSAR', 'A-042', 'Anuncio sin autorización', '10 %', '535.00', 'Retiro del anuncio', ['Preventiva', 'warn']],
          ['AC-2026-0904', 'INVERSIONES DEL NORTE SAC', 'C-214', 'Obra sin licencia de edificación', '100 %', '5,350.00', 'Paralización de obra', ['Coactiva', 'bad']]
        ]
      },
      tabs: [
        { label: 'Acta de constatación', sections: [
          S('Datos del acta', [
            T('Nro. de acta', { t: 'ro', v: 'AC-2026-0912' }),
            T('Fecha', { t: 'date', v: '2026-08-05' }), T('Hora', { v: '11:20' }),
            T('Administrado', { v: 'NOBLECILLA ARISMENDIZ SAC' }),
            T('R.U.C. / D.N.I.', { v: '20525118447' }),
            T('Nombre comercial', { v: 'DEPÓSITO NOBLECILLA' }),
            T('Establecimiento', { v: 'AV. JOSÉ DE LAMA 1180', wide: 1 }),
            T('CIIU del negocio', { t: 'sel', v: 'G-5234-01 — VENTA DE MATERIALES DE CONSTRUCCIÓN', opts: ['G-5234-01 — VENTA DE MATERIALES DE CONSTRUCCIÓN', 'D-1549-19 — RESTAURANTE-POLLERÍA', 'G-5211-01 — VENTA AL POR MENOR EN ALMACENES'] }),
            T('Inspector', { t: 'sel', v: 'L. PEÑA SANDOVAL', opts: ['L. PEÑA SANDOVAL', 'A. VÍLCHEZ ROJAS'] }),
            T('Supervisor', { t: 'sel', v: 'C. ANCAJIMA FLORES', opts: ['C. ANCAJIMA FLORES', 'R. MENDOZA CRUZ'] }),
            T('Persona que atiende', { v: 'NOBLECILLA RUIZ, CARLOS' }),
            T('Se negó a firmar', { t: 'chk', on: true, ph: 'Dejar constancia en el acta' }),
            T('Descripción de los hechos', { t: 'area', wide: 1, v: 'Establecimiento comercial en funcionamiento sin contar con licencia municipal vigente.' })
          ])
        ] },
        { label: 'Sanción', sections: [
          S('Resolución de infracción y sanción', [
            T('Código CUIS', { t: 'sel', v: 'C-101', opts: ['C-101', 'C-214', 'S-018', 'A-042'] }),
            T('Descripción de la infracción', { t: 'ro', v: 'FUNCIONAR SIN LICENCIA MUNICIPAL DE FUNCIONAMIENTO', wide: 1 }),
            T('Base UIT (S/)', { t: 'ro', v: '5,350.00' }),
            T('Porcentaje de UIT', { t: 'sel', v: '50 %', opts: ['10 %', '20 %', '50 %', '100 %', '200 %'] }),
            T('Valor de la multa (S/)', { t: 'ro', v: '2,675.00' }),
            T('Medida complementaria', { t: 'sel', v: 'CLAUSURA TEMPORAL', opts: ['NINGUNA', 'CLAUSURA TEMPORAL', 'CLAUSURA DEFINITIVA', 'DECOMISO', 'RETIRO', 'PARALIZACIÓN DE OBRA', 'DEMOLICIÓN'] }),
            T('Nro. de resolución (RIS)', { v: 'RIS-0912-2026-MPS' }),
            T('Fecha de notificación', { t: 'date', v: '2026-08-07' }),
            T('Descuento pronto pago (50 %)', { t: 'ro', v: '− S/ 1,337.50' }),
            T('Plazo de descargo', { t: 'ro', v: '5 días hábiles' })
          ])
        ] },
        { label: 'Cancelación', sections: [
          S('Pago y anulación', [
            T('Canceló', { t: 'chk', on: false, ph: 'Multa pagada' }),
            T('Nro. de recibo', { v: '' }),
            T('Fecha de pago', { t: 'date' }),
            T('Anuló', { t: 'chk', on: false, ph: 'Acta anulada' }),
            T('Referencia de anulación', { v: '', wide: 1 })
          ], 'Opcional')
        ] }
      ],
      actions: ['Nuevo', 'Emitir RIS', 'Imprimir', 'Guardar']
    },

    codigos_cuis: {
      mod: 'Infracciones', title: 'Cuadro único de infracciones y sanciones (CUIS)',
      endpoint: 'GET /api/v1/infracciones/cuis',
      desc: 'Catálogo aprobado por ordenanza con el porcentaje de UIT y la medida complementaria de cada infracción administrativa.',
      filters: [T('Código', { v: '' }), T('Materia', { t: 'sel', v: 'Todas', opts: ['Todas', 'COMERCIALIZACIÓN', 'SALUBRIDAD', 'ANUNCIOS', 'OBRAS', 'LIMPIEZA', 'TRANSPORTE'] })],
      table: {
        title: 'Infracciones tipificadas', count: '6 de 284',
        cols: ['Código', 'Materia', 'Descripción', '% UIT', 'Multa S/', 'Medida complementaria'], num: [3, 4],
        rows: [
          ['C-101', 'Comercialización', 'Funcionar sin licencia municipal de funcionamiento', '50 %', '2,675.00', 'Clausura temporal'],
          ['C-108', 'Comercialización', 'Funcionar en giro distinto al autorizado', '30 %', '1,605.00', 'Clausura temporal'],
          ['C-214', 'Obras', 'Ejecutar obra sin licencia de edificación', '100 %', '5,350.00', 'Paralización de obra'],
          ['S-018', 'Salubridad', 'Deficiencias de salubridad en el establecimiento', '20 %', '1,070.00', 'Retiro de productos'],
          ['A-042', 'Anuncios', 'Instalar anuncio sin autorización municipal', '10 %', '535.00', 'Retiro del anuncio'],
          ['L-007', 'Limpieza', 'Arrojar residuos sólidos en la vía pública', '10 %', '535.00', 'Ninguna']
        ]
      },
      actions: ['Nuevo', 'Guardar']
    },

    /* ── TESORERÍA ────────────────────────────────────────── */

    caja_tributaria: {
      mod: 'Tesorería', title: 'Caja tributaria',
      endpoint: 'POST /api/v1/tesoreria/caja/cobranza',
      desc: 'Cobranza en ventanilla. Se elige la forma de pago, se filtra la deuda del contribuyente, se aplica el beneficio vigente y se emite el recibo.',
      sections: [
        S('Forma de pago y beneficio', [
          T('Forma de pago', { t: 'sel', v: 'NORMAL TRIBUTARIO', opts: ['NORMAL TRIBUTARIO', 'A CUENTA', 'SÓLO GASTOS', 'BENEFICIO TOTAL AÑO', 'BENEFICIO PARCIAL AÑO', 'ADELANTO DE CONVENIO', 'PRECONVENIO', 'CONTADO TOTAL', 'PRESCRIPCIÓN'] }),
          T('Beneficio aplicable', { t: 'sel', v: 'ORD. 012-2026-MPS — 100 % INTERESES', opts: ['NINGUNO', 'ORD. 012-2026-MPS — 100 % INTERESES', 'AMNISTÍA PREDIAL 2026', 'DESCUENTO PRONTO PAGO'] }),
          T('Buscar por', { t: 'sel', v: 'CONTRIBUYENTE', opts: ['CONTRIBUYENTE', 'OPERACIÓN'] }),
          T('Cod. Contribuyente', { v: '00000003541' }),
          T('Nombre', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA' }),
          T('Domicilio fiscal', { t: 'ro', v: 'CALLE LAMA 482 — ZONA 2 INDUSTRIAL, SULLANA', wide: 1 })
        ]),
        S('Filtros de deuda', [
          T('Año desde', { t: 'sel', v: '2022', opts: yrs }), T('Año hasta', { t: 'sel', v: '2026', opts: yrs }),
          T('Cuota desde', { v: '1' }), T('Cuota hasta', { v: '12' }),
          T('Tributo', { t: 'sel', v: 'TODOS', opts: ['TODOS', 'IMPUESTO PREDIAL', 'LIMPIEZA PÚBLICA', 'PARQUES Y JARDINES', 'SERENAZGO', 'PATRIMONIO VEHICULAR', 'MULTAS'] }),
          T('Fase', { t: 'sel', v: 'TODAS', opts: ['TODAS', 'ORDINARIA', 'VALOR EMITIDO', 'COACTIVA'] }),
          T('Concepto', { v: '' }),
          T('Cód. unidad', { v: '02-014-D-14-01' }),
          T('Cód. convenio', { v: '' }),
          T('Recaudador', { v: '' }),
          T('Coactiva', { t: 'sel', v: 'TODAS', opts: ['TODAS', 'SÍ', 'NO'] })
        ], 'Filtro rápido')
      ],
      table: {
        title: 'Deudas del contribuyente', count: '5 registros · 3 seleccionados',
        cols: ['', 'Año', 'Unidad', 'Cuota', 'Tributo', 'Fase', 'Insoluto', 'Reajuste', 'Interés', 'Gastos', 'Total'], num: [6, 7, 8, 9, 10],
        actions: ['Marcar todos', 'Quitar selección'],
        rows: [
          ['✓', '2026', '02-014-D-14-01', '1', 'IMPUESTO PREDIAL', 'Ordinaria', '147.98', '0.00', '0.00', '0.00', '147.98'],
          ['✓', '2026', '02-014-D-14-01', '2', 'IMPUESTO PREDIAL', 'Ordinaria', '146.86', '2.14', '4.82', '0.00', '153.82'],
          ['✓', '2026', '02-014-D-14-01', '1-12', 'ARBITRIOS', 'Ordinaria', '486.00', '7.20', '18.44', '0.00', '511.64'],
          ['', '2025', '02-014-D-14-01', '3', 'IMPUESTO PREDIAL', 'Valor emitido', '144.20', '8.60', '31.18', '12.00', '195.98'],
          ['', '2024', 'T2G-418', '1', 'PATRIMONIO VEHICULAR', 'Coactiva', '614.00', '48.20', '182.44', '96.00', '940.64']
        ],
        note: 'La fase coactiva incluye costas y gastos del procedimiento; solo el ejecutor puede levantarlos.'
      },
      totals: [
        { label: 'Deuda total', value: 'S/ 1,950.06' },
        { label: 'Deuda acogida', value: 'S/ 813.44' },
        { label: 'Beneficio aplicado', value: '− S/ 25.40' },
        { label: 'Total a cobrar', value: 'S/ 788.04', strong: 1 }
      ],
      actions: ['Limpiar', 'Cargar deudas', 'Cobrar deuda']
    },

    caja_tasas: {
      mod: 'Tesorería', title: 'Caja de tasas y derechos administrativos',
      endpoint: 'POST /api/v1/tesoreria/caja/tasas',
      desc: 'Cobro de conceptos del TUPA que no forman parte de la cuenta corriente: constancias, copias, certificados y derechos de trámite.',
      filters: [T('Cod. Contribuyente', { v: '00000003541' }), T('Partida', { v: '' }), T('Concepto TUPA', { v: '' })],
      table: {
        title: 'Conceptos a cobrar', count: '3 conceptos seleccionados',
        cols: ['', 'Partida', 'Concepto TUPA', 'Área', 'Cantidad', 'Precio S/', 'Importe S/'], num: [4, 5, 6],
        rows: [
          ['✓', '1.3.2.5.2.2', 'INSPECCIÓN OCULAR', 'Fiscalización', '1', '88.40', '88.40'],
          ['✓', '1.3.2.10.1.99', 'CONSTANCIA DE NO ADEUDO', 'Rentas', '1', '18.00', '18.00'],
          ['✓', '1.3.2.10.1.99', 'COPIA CERTIFICADA DE FICHA', 'Catastro', '2', '12.00', '24.00'],
          ['', '1.3.2.9.1.6', 'DERECHO DE ANUNCIO Y PROPAGANDA', 'Comercialización', '1', '412.00', '412.00']
        ]
      },
      totals: [
        { label: 'Conceptos', value: '3' },
        { label: 'Subtotal', value: 'S/ 130.40' },
        { label: 'Descuentos', value: 'S/ 0.00' },
        { label: 'Total a cobrar', value: 'S/ 130.40', strong: 1 }
      ],
      actions: ['Limpiar', 'Cobrar y emitir recibo']
    },

    fraccionamiento: {
      mod: 'Tesorería', title: 'Fraccionamiento tributario',
      endpoint: 'POST /api/v1/tesoreria/fraccionamientos',
      desc: 'Acogimiento de la deuda a pago fraccionado. El sistema simula el cronograma antes de generar el convenio; dos cuotas consecutivas impagas producen la pérdida del beneficio.',
      sections: [
        S('Total deuda', [
          T('Total deuda (S/)', { t: 'ro', v: '262.160' }),
          T('Gastos Deuda (S/)', { t: 'ro', v: '0.000' }),
          T('Cod. Contribuyente', { t: 'ro', v: '00000003541' }),
          T('Nombre', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA', wide: 1 })
        ]),
        S('Datos fraccionamiento', [
          T('Nro. de Cuotas', { v: '6' }),
          T('Monto de Cuota (S/)', { v: '0' }),
          T('Cuota inicial (%)', { t: 'sel', v: '20 %', opts: ['0 %', '10 %', '20 %', '30 %', '50 %'] }),
          T('Interés de fraccionamiento mensual', { t: 'ro', v: '0.80 %' }),
          T('Primera cuota vence', { t: 'date', v: '2026-11-30' }),
          T('Estado', { t: 'sel', v: 'VIGENTE', opts: ['VIGENTE', 'CUMPLIDO', 'EN RIESGO', 'QUEBRADO'] })
        ]),
        S('Ofrecimiento de garantía', [
          T('Tipo de garantía', { t: 'sel', v: 'NO REQUIERE', opts: ['NO REQUIERE', 'CARTA FIANZA', 'HIPOTECA', 'AVAL', 'PRENDA'] }),
          T('Detalle del ofrecimiento', { t: 'area', wide: 1, ph: 'Descripción del bien o documento ofrecido en garantía' })
        ], 'Opcional'),
        S('Impresión', [
          T('Convenio', { t: 'ro', v: 'CONV-2026-00412' }),
          T('Enviar a OpenOffice', { t: 'chk', on: false, ph: 'Exporta en lugar de imprimir' }),
          T('Solicitud', { t: 'chk', on: true, ph: 'Imprimir solicitud' }),
          T('Compromiso', { t: 'chk', on: true, ph: 'Imprimir compromiso de pago' }),
          T('Resolución', { t: 'chk', on: false, ph: 'Imprimir resolución de aprobación' })
        ], 'Opcional')
      ],
      table: {
        title: 'Detalle cuotas', count: '6 cuotas',
        cols: ['Nro', 'Cuota', 'Capital', 'Interes', 'Gasto.Conv.', 'Gasto.Cuota', 'Vencimiento'], num: [1, 2, 3, 4, 5],
        rows: [
          ['001', '46.17', '42.65', '2.52', '0.00', '1.00', '30/11/2026'],
          ['002', '46.17', '43.06', '2.11', '0.00', '1.00', '30/12/2026'],
          ['003', '46.17', '43.48', '1.69', '0.00', '1.00', '30/01/2027'],
          ['004', '46.17', '43.89', '1.28', '0.00', '1.00', '28/02/2027'],
          ['005', '46.17', '44.31', '0.86', '0.00', '1.00', '30/03/2027'],
          ['006', '46.20', '44.77', '0.43', '0.00', '1.00', '30/04/2027']
        ],
        note: 'Totales: 6 cuotas · 277.05 · capital 262.16 · interés 8.89 · gasto convenio 0.00 · gasto cuota 6.00'
      },
      totals: [
        { label: 'Total cuotas', value: 'S/ 277.05' },
        { label: 'Capital', value: 'S/ 262.16' },
        { label: 'Interés', value: 'S/ 8.89' },
        { label: 'Gastos', value: 'S/ 6.00', strong: 1 }
      ],
      actions: ['Fraccionar', 'Imprimir simulación', 'Aceptar']
    },

    consulta_convenios: {
      mod: 'Tesorería', title: 'Consulta de convenios',
      endpoint: 'GET /api/v1/tesoreria/convenios',
      desc: 'Seguimiento de los convenios suscritos, con las cuotas pagadas, las vencidas y los que están por quebrarse.',
      filters: [T('Nro. de convenio', { v: '' }), T('Cod. Contribuyente', { v: '' }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'VIGENTE', 'CUMPLIDO', 'EN RIESGO', 'QUEBRADO'] }), T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' })],
      table: {
        title: 'Convenios registrados', count: '4 de 2,184',
        cols: ['Nro. convenio', 'Contribuyente', 'Fecha', 'Deuda acogida S/', 'Cuotas', 'Pagadas', 'Vencidas', 'Saldo S/', 'Estado'], num: [3, 4, 5, 6, 7],
        rows: [
          ['CONV-2026-00412', 'CASTILLO PASCUALA, MARÍA E.', '12/08/2026', '262.16', '6', '1', '0', '231.03', ['Vigente', 'ok']],
          ['CONV-2026-00388', 'DÍAZ MADRID, JULIO CÉSAR', '04/06/2026', '9,412.15', '12', '2', '2', '7,844.10', ['En riesgo', 'warn']],
          ['CONV-2025-00944', 'REYES CHUNGA, PEDRO', '18/09/2025', '3,180.00', '6', '6', '0', '0.00', ['Cumplido', 'ok']],
          ['CONV-2025-00812', 'INVERSIONES DEL NORTE SAC', '02/04/2025', '18,412.00', '24', '3', '5', '16,102.40', ['Quebrado', 'bad']]
        ],
        note: 'El quiebre del convenio devuelve la deuda a su fase original y habilita la cobranza coactiva por el saldo.'
      },
      totals: [
        { label: 'Convenios vigentes', value: '1,842' },
        { label: 'En riesgo', value: '141' },
        { label: 'Quebrados 2026', value: '88' },
        { label: 'Saldo por cobrar', value: 'S/ 4.21 M', strong: 1 }
      ],
      actions: ['Exportar', 'Imprimir cronograma']
    },

    duplicado_recibo: {
      mod: 'Tesorería', title: 'Duplicado de recibo',
      endpoint: 'GET /api/v1/tesoreria/recibos/{nro}/duplicado',
      desc: 'Reimpresión de un recibo ya emitido. El duplicado sale marcado como tal y queda registrado en la bitácora con el usuario que lo generó.',
      filters: [T('Nro. de recibo', { v: '0003-0041182' }), T('Cod. Contribuyente', { v: '' }), T('Fecha', { t: 'date', v: '2026-08-12' }), T('Caja', { t: 'sel', v: 'Todas', opts: ['Todas', 'C-1', 'C-2', 'C-3', 'C-4'] })],
      table: {
        title: 'Recibos localizados', count: '3 recibos',
        cols: ['Nro. recibo', 'Fecha', 'Hora', 'Contribuyente', 'Concepto', 'Importe S/', 'Duplicados', 'Estado'], num: [5, 6],
        rows: [
          ['0003-0041182', '12/08/2026', '09:14', 'CASTILLO PASCUALA, MARÍA E.', 'Impuesto predial cuotas 1 y 2', '301.80', '1', ['Emitido', 'ok']],
          ['0003-0041183', '12/08/2026', '09:22', 'QUIROGA RAMOS, ELEODORO', 'Arbitrios 2026', '437.40', '0', ['Emitido', 'ok']],
          ['0003-0041184', '12/08/2026', '09:41', 'DÍAZ MADRID, JULIO CÉSAR', 'Alcabala', '1,245.00', '0', ['Anulado', 'bad']]
        ]
      },
      actions: ['Vista previa', 'Imprimir duplicado']
    },

    anulacion_recibo: {
      mod: 'Tesorería', title: 'Anulación de recibo',
      endpoint: 'POST /api/v1/tesoreria/recibos/{nro}/anulacion',
      desc: 'Deja sin efecto un recibo y devuelve la deuda a la cuenta corriente. Requiere autorización del responsable de tesorería y solo procede mientras la caja del turno siga abierta.',
      sections: [
        S('Recibo a anular', [
          T('Nro. de recibo', { v: '0003-0041184' }),
          T('Fecha de emisión', { t: 'ro', v: '12/08/2026 09:41' }),
          T('Caja / cajero', { t: 'ro', v: 'C-3 — J. CÁRDENAS VEGA' }),
          T('Contribuyente', { t: 'ro', v: 'DÍAZ MADRID, JULIO CÉSAR' }),
          T('Concepto', { t: 'ro', v: 'IMPUESTO DE ALCABALA — EXPEDIENTE 2026-0918', wide: 1 }),
          T('Importe (S/)', { t: 'ro', v: '1,245.00' }),
          T('Medio de pago', { t: 'ro', v: 'DEPÓSITO EN CUENTA' })
        ]),
        S('Sustento de la anulación', [
          T('Motivo', { t: 'sel', v: 'ERROR EN EL CONCEPTO COBRADO', opts: ['ERROR EN EL CONCEPTO COBRADO', 'ERROR EN EL IMPORTE', 'ERROR EN EL CONTRIBUYENTE', 'PAGO DUPLICADO', 'DESISTIMIENTO DEL ADMINISTRADO', 'FALLA DE IMPRESIÓN'] }),
          T('Autorizado por', { t: 'sel', v: 'RESPONSABLE DE TESORERÍA', opts: ['RESPONSABLE DE TESORERÍA', 'GERENTE DE ADMINISTRACIÓN TRIBUTARIA'] }),
          T('Nº de memorando', { v: 'MEM-0418-2026-MPS-T' }),
          T('Devuelve la deuda a cuenta corriente', { t: 'chk', on: true, ph: 'Restablece las obligaciones canceladas' }),
          T('Detalle', { t: 'area', wide: 1, v: 'Se cobró alcabala sobre el 100 % del predio cuando la transferencia fue del 50 %.' })
        ])
      ],
      actions: ['Verificar recibo', 'Anular recibo']
    },

    cierre_caja: {
      mod: 'Tesorería', title: 'Cierre y arqueo de caja',
      endpoint: 'POST /api/v1/tesoreria/caja/cierre',
      desc: 'Arqueo del turno: recaudación por medio de pago, recibos emitidos y anulados, y diferencia entre lo declarado y lo registrado por el sistema.',
      sections: [
        S('Turno', [
          T('Caja', { t: 'ro', v: 'C-3' }),
          T('Cajero', { t: 'ro', v: 'J. CÁRDENAS VEGA' }),
          T('Fecha', { t: 'date', v: '2026-08-12' }),
          T('Turno', { t: 'sel', v: 'MAÑANA', opts: ['MAÑANA', 'TARDE', 'CONTINUO'] }),
          T('Hora de apertura', { t: 'ro', v: '08:00' }),
          T('Hora de cierre', { v: '13:30' })
        ]),
        S('Arqueo', [
          T('Efectivo (S/)', { v: '12,418.40' }),
          T('Tarjeta de débito / crédito (S/)', { v: '4,120.00' }),
          T('Depósito en cuenta (S/)', { v: '8,940.60' }),
          T('Pago en línea (S/)', { v: '2,214.30' }),
          T('Total declarado (S/)', { t: 'ro', v: '27,693.30' }),
          T('Total sistema (S/)', { t: 'ro', v: '27,693.30' }),
          T('Diferencia (S/)', { t: 'ro', v: '0.00' }),
          T('Recibos emitidos', { t: 'ro', v: '148' }),
          T('Recibos anulados', { t: 'ro', v: '3' }),
          T('Observaciones del arqueo', { t: 'area', wide: 1 })
        ])
      ],
      actions: ['Cuadrar', 'Imprimir arqueo', 'Cerrar caja']
    },

    avance_recaudacion: {
      mod: 'Tesorería', title: 'Avance de recaudación',
      endpoint: 'GET /api/v1/tesoreria/recaudacion/avance',
      desc: 'Comparación de lo emitido contra lo recaudado por tributo y periodo, base del seguimiento de la meta anual.',
      filters: [T('Ejercicio', { t: 'sel', v: '2026', opts: yrs }), T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Tributo', { t: 'sel', v: 'Todos', opts: ['Todos', 'IMPUESTO PREDIAL', 'ARBITRIOS', 'PATRIMONIO VEHICULAR', 'ALCABALA', 'MULTAS'] })],
      table: {
        title: 'Emitido contra recaudado', count: 'Ejercicio 2026 al 13/08',
        cols: ['Tributo', 'Emitido S/', 'Recaudado S/', 'Saldo S/', '% avance', 'Meta S/', '% de meta'], num: [1, 2, 3, 4, 5, 6],
        rows: [
          ['IMPUESTO PREDIAL', '9,418,204.60', '8,420,118.40', '998,086.20', '89.4 %', '9,600,000.00', '87.7 %'],
          ['ARBITRIOS MUNICIPALES', '5,884,110.20', '5,112,440.80', '771,669.40', '86.9 %', '6,100,000.00', '83.8 %'],
          ['PATRIMONIO VEHICULAR', '2,884,000.00', '1,882,400.00', '1,001,600.00', '65.3 %', '2,900,000.00', '64.9 %'],
          ['ALCABALA', '1,420,880.00', '1,420,880.00', '0.00', '100.0 %', '1,600,000.00', '88.8 %'],
          ['MULTAS Y PAPELETAS', '4,118,200.00', '1,588,412.00', '2,529,788.00', '38.6 %', '3,200,000.00', '49.6 %']
        ]
      },
      totals: [
        { label: 'Emitido', value: 'S/ 23.73 M' },
        { label: 'Recaudado', value: 'S/ 18.42 M' },
        { label: 'Saldo por cobrar', value: 'S/ 5.30 M' },
        { label: 'Avance', value: '77.6 %', strong: 1 }
      ],
      actions: ['Excel', 'Imprimir avance']
    },

    recaudacion_area: {
      mod: 'Tesorería', title: 'Recaudación por área',
      endpoint: 'GET /api/v1/tesoreria/recaudacion/por-area',
      desc: 'Recaudación desagregada por partida presupuestal y unidad orgánica generadora, para el reporte mensual a la gerencia de administración.',
      filters: [
        T('Área', { t: 'sel', v: '113300 — SUBGERENCIA DE FISCALIZACIÓN TRIBUTARIA', opts: ['113300 — SUBGERENCIA DE FISCALIZACIÓN TRIBUTARIA', '113100 — UNIDAD DE RENTAS', '113200 — TESORERÍA', '114000 — COMERCIALIZACIÓN'] }),
        T('Desde', { t: 'date', v: '2026-01-01' }),
        T('Hasta', { t: 'date', v: '2026-08-13' }),
        T('Agrupar por Área', { t: 'sel', v: 'No', opts: ['Sí', 'No'] }),
        T('Agrupar por Tributo', { t: 'sel', v: 'No', opts: ['Sí', 'No'] })
      ],
      table: {
        title: 'Recaudación por partida', count: '10 partidas',
        cols: ['Partida', 'Descripción', 'Monto S/'], num: [2],
        rows: [
          ['1.1. 2. 1. 1. 1', 'PREDIAL', '3,300.93'],
          ['1.1. 3. 3. 3. 4', 'IMPUESTO A LOS ESPECTÁCULOS PÚBLICOS NO DEPORTIVOS', '23,020.00'],
          ['1.1. 5. 3. 1.99', 'OTRAS MULTAS', '16.00'],
          ['1.1. 5. 3. 2.99', 'OTRAS SANCIONES', '1,041.06'],
          ['1.3. 2. 5. 2. 2', 'INSPECCIÓN OCULAR', '688.80'],
          ['1.3. 2. 9. 1. 6', 'ANUNCIOS Y PROPAGANDA', '5,924.75'],
          ['1.3. 2.10. 1.99', 'OTROS DERECHOS ADMINISTRATIVOS', '1,391.10'],
          ['1.3. 3. 9. 2.27', 'PARQUES Y JARDINES', '34.38'],
          ['1.3. 3. 9. 2.23', 'LIMPIEZA PUBLICA', '99.05'],
          ['1.3. 3. 9. 2.24', 'SERENAZGO', '63.26']
        ],
        note: 'Total del periodo para el área seleccionada: S/ 35,579.33'
      },
      actions: ['Excel', 'Imprimir por partida', 'Imprimir por tributo']
    },

    /* ── CONSULTAS ────────────────────────────────────────── */

    cuenta_corriente: {
      mod: 'Consultas', title: 'Estado de cuenta corriente',
      endpoint: 'GET /api/v1/consultas/cuenta-corriente/{codigo}',
      desc: 'Deuda y pagos del contribuyente por ejercicio y tributo, con la fase en la que se encuentra cada obligación.',
      filters: [T('Cod. Contribuyente', { v: '00000003541' }), T('Ejercicio', { t: 'sel', v: 'Todos', opts: ['Todos'].concat(yrs) }), T('Tributo', { t: 'sel', v: 'Todos', opts: ['Todos', 'IMPUESTO PREDIAL', 'ARBITRIOS', 'PATRIMONIO VEHICULAR', 'MULTAS'] }), T('Situación', { t: 'sel', v: 'Todas', opts: ['Todas', 'CON DEUDA', 'CANCELADO'] })],
      table: {
        title: 'Cuenta corriente — CASTILLO PASCUALA, MARÍA ELENA', count: '5 obligaciones',
        cols: ['Año', 'Tributo', 'Unidad', 'Cuota', 'Emitido S/', 'Pagado S/', 'Saldo S/', 'Fase'], num: [4, 5, 6],
        rows: [
          ['2026', 'IMPUESTO PREDIAL', '02-014-D-14-01', '1 de 4', '147.98', '147.98', '0.00', ['Cancelado', 'ok']],
          ['2026', 'IMPUESTO PREDIAL', '02-014-D-14-01', '2 de 4', '146.86', '0.00', '153.82', ['Ordinaria', 'warn']],
          ['2026', 'ARBITRIOS', '02-014-D-14-01', '1-12', '486.00', '0.00', '511.64', ['Ordinaria', 'warn']],
          ['2025', 'IMPUESTO PREDIAL', '02-014-D-14-01', '3 de 4', '144.20', '0.00', '195.98', ['Valor emitido', 'warn']],
          ['2024', 'PATRIMONIO VEHICULAR', 'T2G-418', '1 de 1', '614.00', '0.00', '940.64', ['Coactiva', 'bad']]
        ]
      },
      totals: [
        { label: 'Deuda insoluta', value: 'S/ 1,591.06' },
        { label: 'Reajuste e interés', value: 'S/ 263.00' },
        { label: 'Costas y gastos', value: 'S/ 96.00' },
        { label: 'Saldo total', value: 'S/ 1,802.06', strong: 1 }
      ],
      actions: ['Excel', 'Imprimir estado de cuenta']
    },

    consulta_pagos: {
      mod: 'Consultas', title: 'Consulta de pagos',
      endpoint: 'GET /api/v1/consultas/pagos',
      desc: 'Historial de pagos con el recibo, la caja y el concepto imputado.',
      filters: [T('Cod. Contribuyente', { v: '00000003541' }), T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' }), T('Medio de pago', { t: 'sel', v: 'Todos', opts: ['Todos', 'EFECTIVO', 'TARJETA', 'DEPÓSITO', 'PAGO EN LÍNEA'] })],
      table: {
        title: 'Pagos registrados', count: '4 pagos · S/ 1,238.78',
        cols: ['Fecha', 'Recibo', 'Concepto', 'Año', 'Medio', 'Caja', 'Importe S/'], num: [6],
        rows: [
          ['12/08/2026', '0003-0041182', 'Impuesto predial cuotas 1 y 2', '2026', 'EFECTIVO', 'C-3', '301.80'],
          ['28/02/2026', '0001-0038114', 'Impuesto predial cuota 1', '2026', 'PAGO EN LÍNEA', 'WEB', '147.98'],
          ['14/12/2025', '0002-0034477', 'Arbitrios 2025', '2025', 'TARJETA', 'C-2', '412.00'],
          ['30/08/2025', '0003-0031208', 'Impuesto predial cuota 3', '2025', 'EFECTIVO', 'C-3', '377.00']
        ]
      },
      actions: ['Excel', 'Imprimir']
    },

    consulta_deuda: {
      mod: 'Consultas', title: 'Consulta de deuda',
      endpoint: 'GET /api/v1/consultas/deuda',
      desc: 'Deuda exigible a una fecha de corte, con el interés moratorio calculado al día y el desglose por fase de cobranza.',
      filters: [T('Cod. Contribuyente', { v: '00000006550' }), T('Fecha de corte', { t: 'date', v: '2026-08-13' }), T('Fase', { t: 'sel', v: 'Todas', opts: ['Todas', 'ORDINARIA', 'VALOR EMITIDO', 'COACTIVA'] }), T('Incluye convenios', { t: 'sel', v: 'No', opts: ['Sí', 'No'] })],
      table: {
        title: 'Deuda al 13/08/2026 — DÍAZ MADRID, JULIO CÉSAR', count: '4 obligaciones',
        cols: ['Año', 'Tributo', 'Cuota', 'Insoluto S/', 'Reajuste S/', 'Interés S/', 'Gastos S/', 'Total S/', 'Fase'], num: [3, 4, 5, 6, 7],
        rows: [
          ['2026', 'IMPUESTO PREDIAL', '1-4', '1,842.60', '26.40', '84.12', '0.00', '1,953.12', ['Ordinaria', 'warn']],
          ['2025', 'ARBITRIOS', '1-12', '1,184.00', '38.20', '188.44', '0.00', '1,410.64', ['Valor emitido', 'warn']],
          ['2024', 'IMPUESTO PREDIAL', '1-4', '2,880.00', '142.80', '682.44', '96.00', '3,801.24', ['Coactiva', 'bad']],
          ['2023', 'MULTA ADMINISTRATIVA', '1', '1,840.00', '98.40', '308.75', '184.00', '2,431.15', ['Coactiva', 'bad']]
        ],
        note: 'El interés moratorio se calcula con la TIM vigente de 0.90 % mensual desde el día siguiente al vencimiento.'
      },
      totals: [
        { label: 'Fase ordinaria', value: 'S/ 1,953.12' },
        { label: 'Valor emitido', value: 'S/ 1,410.64' },
        { label: 'Fase coactiva', value: 'S/ 6,232.39' },
        { label: 'Deuda total', value: 'S/ 9,596.15', strong: 1 }
      ],
      actions: ['Excel', 'Imprimir liquidación de deuda']
    },

    consulta_predios: {
      mod: 'Consultas', title: 'Consulta de predios',
      endpoint: 'GET /api/v1/consultas/predios',
      desc: 'Búsqueda de predios por titular, ubicación o código, con el autovalúo vigente y la deuda asociada a cada unidad.',
      filters: [T('Código predial', { v: '' }), T('Contribuyente', { v: '' }), T('Calle', { v: '' }), T('Manzana', { v: '' }), T('Lote', { v: '' })],
      table: {
        title: 'Predios encontrados', count: '4 de 78,204',
        cols: ['Código predial', 'Titular', 'Dirección', 'Uso', 'Terreno m²', 'Const. m²', 'Autovalúo S/', 'Deuda S/'], num: [4, 5, 6, 7],
        rows: [
          ['02-014-D-14-01', 'MEDINA MEDINA, RUFINA (SUC.)', 'CALLE SANTA ROSA 116', 'Casa habitación', '210.00', '164.50', '132,196.75', '1,842.60'],
          ['02-014-D-14-02', 'QUIROGA RAMOS, ELEODORO', 'CALLE SANTA ROSA 118', 'Comercio', '120.00', '96.00', '88,412.00', '0.00'],
          ['04-021-B-07-00', 'CASTILLO PASCUALA, MARÍA E.', 'MZ. B LT. 7 — BELLAVISTA', 'Terreno sin construir', '184.00', '0.00', '38,420.00', '0.00'],
          ['03-088-A-01-00', 'INVERSIONES DEL NORTE SAC', 'CARRETERA SULLANA-PAITA KM 2', 'Industria', '1,840.00', '640.00', '842,000.00', '18,412.00']
        ]
      },
      actions: ['Excel', 'Ver ficha']
    },

    consulta_vehiculos: {
      mod: 'Consultas', title: 'Consulta de vehículos',
      endpoint: 'GET /api/v1/consultas/vehiculos',
      desc: 'Padrón vehicular consultable por placa, motor o titular, con los ejercicios afectos y la deuda vigente.',
      filters: [T('Placa', { v: '' }), T('Nro. Motor', { v: '' }), T('Contribuyente', { v: '' }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'AFECTO', 'INAFECTO', 'EXONERADO', 'BAJA'] })],
      table: {
        title: 'Vehículos encontrados', count: '4 de 3,204',
        cols: ['Placa', 'Clase', 'Marca y modelo', 'Año fab.', 'Titular', 'Afectación', 'Base imponible S/', 'Deuda S/'], num: [6, 7],
        rows: [
          ['T2G-418', 'AUTOMÓVIL', 'TOYOTA YARIS GLI', '2018', 'CASTILLO PASCUALA, MARÍA E.', '2019 — 2021', '61,400.00', '940.64'],
          ['V1H-882', 'CAMIONETA', 'HYUNDAI TUCSON', '2024', 'CASTILLO PASCUALA, MARÍA E.', '2025 — 2027', '112,800.00', '1,128.00'],
          ['B7T-221', 'AUTOMÓVIL', 'KIA RIO', '2020', 'REYES CHUNGA, PEDRO', '2021 — 2023', '62,400.00', '0.00'],
          ['T4M-119', 'CAMIÓN', 'HYUNDAI HD-78', '2022', 'INVERSIONES DEL NORTE SAC', '2023 — 2025', '84,000.00', '840.00']
        ]
      },
      actions: ['Excel', 'Ver ficha']
    },

    consulta_valores: {
      mod: 'Consultas', title: 'Consulta de valores emitidos',
      endpoint: 'GET /api/v1/consultas/valores',
      desc: 'Órdenes de pago, resoluciones de determinación y de multa emitidas a un contribuyente, con su estado de notificación y firmeza.',
      filters: [T('Nro. de valor', { v: '' }), T('Cod. Contribuyente', { v: '' }), T('Tipo', { t: 'sel', v: 'Todos', opts: ['Todos', 'ORDEN DE PAGO', 'RES. DETERMINACIÓN', 'RES. DE MULTA'] }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'EMITIDO', 'NOTIFICADO', 'FIRME', 'RECLAMADO', 'COACTIVA', 'ANULADO'] })],
      table: {
        title: 'Valores encontrados', count: '4 de 1,284',
        cols: ['Nro. valor', 'Tipo', 'Contribuyente', 'Tributo', 'Periodo', 'Monto S/', 'Notificado', 'Estado'], num: [5],
        rows: [
          ['OP-2026-004182', 'ORDEN DE PAGO', 'CASTILLO PASCUALA, MARÍA E.', 'IMPUESTO PREDIAL', '2025 — cuota 3', '195.98', '18/07/2026', ['Firme', 'bad']],
          ['RD-2026-000418', 'RES. DETERMINACIÓN', 'INVERSIONES DEL NORTE SAC', 'IMPUESTO PREDIAL', '2021 — 2026', '18,412.00', '02/08/2026', ['Reclamado', 'warn']],
          ['RM-2026-000912', 'RES. DE MULTA', 'NOBLECILLA ARISMENDIZ SAC', 'MULTA ADMINISTRATIVA', '2026', '2,675.00', 'Pendiente', ['Emitido', 'warn']],
          ['OP-2026-004044', 'ORDEN DE PAGO', 'DÍAZ MADRID, JULIO CÉSAR', 'PATRIMONIO VEHICULAR', '2024', '940.64', '11/06/2026', ['Coactiva', 'bad']]
        ]
      },
      actions: ['Excel', 'Imprimir valor']
    },

    constancia: {
      mod: 'Consultas', title: 'Constancia de no adeudo',
      endpoint: 'GET /api/v1/consultas/constancias/no-adeudo',
      kind: 'report',
      desc: 'Vista previa del documento que se entrega al contribuyente. Se imprime con el mismo formato en papel membretado.',
      report: {
        code: 'CNA-2026-01184', date: '13 de agosto de 2026',
        title: 'Constancia de no adeudo',
        subtitle: 'Emitida conforme al Texto Único de Procedimientos Administrativos vigente',
        meta: [
          { k: 'Contribuyente', v: 'CASTILLO PASCUALA, MARÍA ELENA' },
          { k: 'Documento', v: 'DNI 44218937' },
          { k: 'Código', v: '00000003541' },
          { k: 'Predio', v: '02-014-D-14-01' },
          { k: 'Ejercicios verificados', v: '2022 — 2026' },
          { k: 'Vigencia', v: '30 días calendario' }
        ],
        cols: ['Tributo', 'Ejercicios', 'Situación', 'Saldo S/'], num: [3],
        rows: [
          ['Impuesto predial', '2022 — 2026', 'Cancelado', '0.00'],
          ['Arbitrios municipales', '2022 — 2026', 'Cancelado', '0.00'],
          ['Patrimonio vehicular', '2019 — 2021', 'Cancelado', '0.00'],
          ['Multas administrativas', '2022 — 2026', 'Sin registros', '0.00']
        ],
        footer: 'Se deja constancia de que el contribuyente identificado no registra deuda pendiente por los tributos y ejercicios señalados a la fecha de emisión. El presente documento pierde validez si con posterioridad se detecta deuda omitida producto de un procedimiento de fiscalización.'
      }
    }

  });
})();
