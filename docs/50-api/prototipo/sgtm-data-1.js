/* SGTM — catálogo de pantallas: Catastro y Rentas (Registro).
   Campos tomados del Manual de Usuario SGTM (figuras 004-100). */
(function () {
  var T = function (label, o) { return Object.assign({ label: label, t: 'text', v: '', ph: '', opts: null, wide: 0, on: false }, o || {}); };
  var S = function (label, fields, hint) { return { label: label, fields: fields, hint: hint || '' }; };
  var W = window.SGTM_SCREENS = window.SGTM_SCREENS || {};
  var yrs = ['2026', '2025', '2024', '2023', '2022', '2021', '2020'];
  var estados = ['A — Activa', 'I — Inactiva', 'P — Pendiente', 'X — Anulada'];
  var cat7 = ['A', 'B', 'C', 'D', 'E', 'F', 'G'];

  Object.assign(W, {

    /* ── CATASTRO ─────────────────────────────────────────── */

    ficha_urbana: {
      mod: 'Catastro', title: 'Ficha catastral urbana individual',
      endpoint: 'GET /api/v1/catastro/fichas/urbana/{codRefCatastral}',
      desc: 'Ficha del predio urbano. El código de referencia catastral se compone de sector, manzana, lote, edificación, entrada, piso y unidad; su cambio obliga a recalcular el autovalúo.',
      filters: [
        T('Código de Ref. Catastral', { v: '200601010150010101001' }),
        T('Cod. Contribuyente Rentas', { v: '00000025673' }),
        T('Nro. Ficha', { v: '' }),
        T('Uso', { t: 'sel', v: 'Todos', opts: ['Todos', 'Casa habitación', 'Comercio', 'Industria', 'Terreno sin construir', 'Servicios', 'Educación', 'Salud'] })
      ],
      table: {
        title: 'Ubicación del predio catastral', count: '3 vías registradas',
        cols: ['Nombre Calle', 'Tipo Vía', 'Tip. Puerta', 'Número', 'Num. Adicional', 'Nom. Tipo Num.'],
        rows: [
          ['SANTA ROSA', 'CALLE', 'P — Principal', '116', '—', 'MUNICIPAL'],
          ['EL ALTO', 'PASAJE', 'S — Secundaria', '116-A', 'INT. 2', 'MUNICIPAL'],
          ['LOS ALGARROBOS', 'AVENIDA', 'C — Cochera', '118', '—', 'ANTERIOR']
        ]
      },
      tabs: [
        { label: 'Datos Generales', sections: [
          S('Ficha catastral urbana individual', [
            T('Código de Ref. Catastral', { t: 'ro', v: '200601010150010101001' }),
            T('Uso', { t: 'sel', v: 'Casa habitación', opts: ['Casa habitación', 'Comercio', 'Industria', 'Terreno sin construir', 'Servicios'] }),
            T('CUC', { v: '0015001' }),
            T('Código Hoja Catastral', { v: '200601-15' }),
            T('Cod. Catastral Anterior', { v: '' }),
            T('Cod. Ref Catastral / Urb.', { t: 'ro', v: '200601 · 01 · 015 · 001 · 01 · 01 · 01 · 001', wide: 1 }),
            T('Cod. Contribuyente Rentas', { v: '00000025673' }),
            T('Nombre del contribuyente', { t: 'ro', v: 'SUC. RUFINA MEDINA MEDINA' }),
            T('Código Predial de Rentas', { v: '02-014-D-14-01' }),
            T('Código Anterior', { t: 'ro', v: '—' }),
            T('Nro. Ficha', { v: '000418' }),
            T('Unid. Acum. a Cod. Predial de Rentas', { v: '' }),
            T('Arancel', { t: 'ro', v: '198.40' }),
            T('Número de Ficha por lote', { v: '01 de 03' })
          ]),
          S('Ubicación del predio catastral', [
            T('Tipo de Vía', { t: 'sel', v: '02 — CALLE', opts: ['99 — NO ESPECIFICADO', '01 — AVENIDA', '02 — CALLE', '03 — JIRÓN', '04 — PASAJE', '05 — CARRETERA'] }),
            T('Calle', { v: 'SANTA ROSA' }),
            T('Tipo de Puerta', { t: 'sel', v: 'P — PRINCIPAL', opts: ['P — PRINCIPAL', 'S — SECUNDARIA', 'C — COCHERA'] }),
            T('Ant. Num. Municipal', { v: '' }),
            T('Cond. Numeración', { t: 'sel', v: '99 — NO ESPECIFICADO', opts: ['99 — NO ESPECIFICADO', '01 — CON CERTIFICADO', '02 — SIN CERTIFICADO'] }),
            T('Num. de Cert. de Numeración', { v: '' }),
            T('Nuevo N. Municipal', { v: '116' }),
            T('Número Adicional', { v: '' })
          ])
        ] },
        { label: 'Ubicación', sections: [
          S('Localización', [
            T('Departamento', { t: 'sel', v: 'PIURA', opts: ['PIURA', 'LIMA', 'TUMBES', 'LAMBAYEQUE'] }),
            T('Provincia', { t: 'sel', v: 'SULLANA', opts: ['SULLANA', 'PIURA', 'TALARA', 'PAITA'] }),
            T('Distrito', { t: 'sel', v: 'SULLANA', opts: ['SULLANA', 'BELLAVISTA', 'MARCAVELICA', 'QUERECOTILLO', 'SALITRAL', 'IGNACIO ESCUDERO', 'LANCONES', 'MIGUEL CHECA'] }),
            T('Sector', { v: '01' }), T('Manzana', { v: '015' }), T('Lote', { v: '001' }),
            T('Edificación', { v: '01' }), T('Entrada', { v: '01' }), T('Piso', { v: '01' }), T('Unidad', { v: '001' }),
            T('Habilitación urbana', { v: 'URB. SANTA ROSA — EL ALTO' }),
            T('Zona / sector catastral', { t: 'sel', v: 'Zona 2', opts: ['Zona 1', 'Zona 2', 'Zona 3', 'Zona 4'] }),
            T('Referencia', { v: 'Frente al parque', wide: 1 })
          ])
        ] },
        { label: 'Carac. Titularidad', sections: [
          S('Características de la titularidad', [
            T('Condición del titular', { t: 'sel', v: 'PROPIETARIO ÚNICO', opts: ['PROPIETARIO ÚNICO', 'COPROPIETARIO', 'POSEEDOR', 'SUCESIÓN INDIVISA', 'ARRENDATARIO', 'OCUPANTE'] }),
            T('Forma de adquisición', { t: 'sel', v: 'COMPRA-VENTA', opts: ['COMPRA-VENTA', 'DONACIÓN', 'HERENCIA', 'ADJUDICACIÓN', 'PERMUTA', 'PRESCRIPCIÓN ADQUISITIVA'] }),
            T('Fecha de adquisición', { t: 'date', v: '2004-06-18' }),
            T('Documento que acredita', { t: 'sel', v: 'ESCRITURA PÚBLICA', opts: ['ESCRITURA PÚBLICA', 'MINUTA', 'CONTRATO PRIVADO', 'FICHA REGISTRAL', 'TÍTULO DE PROPIEDAD', 'CONSTANCIA DE POSESIÓN'] }),
            T('Nº de partida registral', { v: 'P11024478' }),
            T('Oficina registral', { t: 'sel', v: 'SUNARP — SULLANA', opts: ['SUNARP — SULLANA', 'SUNARP — PIURA'] }),
            T('% de participación', { v: '100.00' }),
            T('Predio en litigio', { t: 'chk', on: false, ph: 'Existe proceso judicial en curso' })
          ])
        ] },
        { label: 'Propietarios', sections: [
          S('Titulares registrados', [
            T('Cod. Contribuyente', { v: '00000025673' }),
            T('Nombre / razón social', { t: 'ro', v: 'SUC. RUFINA MEDINA MEDINA' }),
            T('D.N.I.', { v: '03593174' }), T('R.U.C.', { v: '' }),
            T('% de propiedad', { v: '100.00' }),
            T('Condición', { t: 'sel', v: 'TITULAR', opts: ['TITULAR', 'CÓNYUGE', 'COPROPIETARIO', 'REPRESENTANTE'] }),
            T('Estado civil', { t: 'sel', v: 'VIUDO(A)', opts: ['SOLTERO(A)', 'CASADO(A)', 'VIUDO(A)', 'DIVORCIADO(A)', 'CONVIVIENTE'] }),
            T('Fecha desde', { t: 'date', v: '2004-06-18' })
          ])
        ] },
        { label: 'Construcción', sections: [
          S('Características de construcción — piso 01', [
            T('Nº Piso', { v: '01' }), T('Mes', { v: '01' }), T('Año', { v: '2000' }),
            T('MEP', { t: 'sel', v: '02 — LADRILLO', opts: ['01 — CONCRETO', '02 — LADRILLO', '03 — ADOBE', '04 — QUINCHA', '05 — MADERA', '06 — ESTERA'] }),
            T('ECS', { t: 'sel', v: '02 — BUENO', opts: ['01 — MUY BUENO', '02 — BUENO', '03 — REGULAR', '04 — MALO', '05 — MUY MALO'] }),
            T('ECC', { t: 'sel', v: '03 — TERMINADO', opts: ['01 — EN CONSTRUCCIÓN', '02 — INCONCLUSO', '03 — TERMINADO', '04 — EN RUINAS'] }),
            T('Muros', { t: 'sel', v: 'C', opts: cat7 }),
            T('Techos', { t: 'sel', v: 'D', opts: cat7 }),
            T('Pisos', { t: 'sel', v: 'E', opts: cat7 }),
            T('Puertas', { t: 'sel', v: 'E', opts: cat7 }),
            T('Revest.', { t: 'sel', v: 'E', opts: cat7 }),
            T('Baños', { t: 'sel', v: 'E', opts: cat7 }),
            T('Instalaciones Eléctricas', { t: 'sel', v: 'F', opts: cat7 }),
            T('Área Construida Declarada', { v: '100.00' }),
            T('Área Construida Verificada', { v: '100.00' }),
            T('UCA', { t: 'sel', v: '99 — NO ESPECIFICADO', opts: ['99 — NO ESPECIFICADO', '01 — VIVIENDA', '02 — COMERCIO', '03 — DEPÓSITO'] })
          ]),
          S('Áreas legal y física', [
            T('Terreno — LEGAL', { v: '210.00' }), T('Terreno — FÍSICO', { v: '210.00' }),
            T('Construc. — LEGAL', { v: '164.50' }), T('Construc. — FÍSICO', { v: '198.00' })
          ], 'Opcional')
        ] },
        { label: 'Otras Instalaciones', sections: [
          S('Obras complementarias', [
            T('Tipo de obra', { t: 'sel', v: 'CERCO PERIMÉTRICO', opts: ['CERCO PERIMÉTRICO', 'LOSA DEPORTIVA', 'PISCINA', 'TANQUE ELEVADO', 'POZO', 'PAVIMENTO', 'PORTÓN'] }),
            T('Unidad de medida', { t: 'sel', v: 'ml', opts: ['m²', 'ml', 'm³', 'Unidad'] }),
            T('Metrado', { v: '38.00' }),
            T('Año', { v: '2006' }), T('Mes', { v: '03' }),
            T('Estado de conservación', { t: 'sel', v: 'BUENO', opts: ['MUY BUENO', 'BUENO', 'REGULAR', 'MALO'] }),
            T('Valor unitario (S/)', { v: '142.00' }),
            T('Valor de la obra (S/)', { t: 'ro', v: '4,120.00' })
          ])
        ] },
        { label: 'Inquilinos', sections: [
          S('Ocupantes no propietarios', [
            T('Documento', { v: '02718844' }),
            T('Nombre del inquilino', { v: 'DÍAZ MADRID, JULIO CÉSAR' }),
            T('Área ocupada (m²)', { v: '48.00' }),
            T('Uso que da al predio', { t: 'sel', v: 'COMERCIO', opts: ['VIVIENDA', 'COMERCIO', 'DEPÓSITO', 'SERVICIOS'] }),
            T('Fecha de inicio', { t: 'date', v: '2024-01-02' }),
            T('Fecha de término', { t: 'date' }),
            T('Merced conductiva (S/)', { v: '450.00' })
          ], 'Opcional')
        ] },
        { label: 'Arbitrios', sections: [
          S('Datos para el cálculo de arbitrios', [
            T('Cod. Uso REC (recolección)', { t: 'sel', v: '01 — CASA HABITACIÓN', opts: ['01 — CASA HABITACIÓN', '02 — COMERCIO', '03 — INDUSTRIA', '04 — SERVICIOS', '05 — TERRENO SIN CONSTRUIR'] }),
            T('Cod. Uso BAR (barrido)', { t: 'sel', v: '01 — CASA HABITACIÓN', opts: ['01 — CASA HABITACIÓN', '02 — COMERCIO', '03 — INDUSTRIA', '04 — SERVICIOS'] }),
            T('Frecuencia de recolección', { t: 'sel', v: 'INTERDIARIA', opts: ['DIARIA', 'INTERDIARIA', 'DOS VECES POR SEMANA', 'SEMANAL'] }),
            T('Frecuencia de barrido', { t: 'sel', v: 'DIARIA', opts: ['DIARIA', 'INTERDIARIA', 'SEMANAL'] }),
            T('Frontis (ml)', { v: '10.50' }),
            T('Posición del predio', { t: 'sel', v: 'ESQUINA', opts: ['INTERIOR', 'ESQUINA', 'FRENTE A PARQUE', 'FRENTE A VÍA PRINCIPAL'] }),
            T('Peligrosidad de la zona', { t: 'sel', v: 'MEDIA', opts: ['BAJA', 'MEDIA', 'ALTA'] }),
            T('Factor de distribución de costo', { t: 'ro', v: '0.00842' }),
            T('Inafecto a arbitrios', { t: 'chk', on: false, ph: 'Predio inafecto por norma' })
          ])
        ] },
        { label: 'Observaciones', sections: [
          S('Notas de la ficha', [
            T('Observaciones', { t: 'area', wide: 1, v: 'Ampliación del segundo piso verificada en inspección del 03/2026; pendiente de declaración jurada rectificatoria.' }),
            T('Ficha verificada en campo', { t: 'chk', on: true, ph: 'Inspección realizada' }),
            T('Fecha de verificación', { t: 'date', v: '2026-03-14' })
          ])
        ] },
        { label: 'Inf. Complementaria', sections: [
          S('Información complementaria', [
            T('Nº de suministro de luz', { v: '4471182' }),
            T('Nº de suministro de agua', { v: '221884' }),
            T('Teléfono del predio', { v: '073-502147' }),
            T('Nº de licencia de funcionamiento', { v: '2010-006549' }),
            T('Predio declarado patrimonio', { t: 'chk', on: false, ph: 'Inmueble con valor monumental' }),
            T('Fuente de la información', { t: 'sel', v: 'DECLARACIÓN DEL TITULAR', opts: ['DECLARACIÓN DEL TITULAR', 'INSPECCIÓN DE CAMPO', 'CONVENIO INTERINSTITUCIONAL', 'BARRIDO CATASTRAL'] })
          ], 'Opcional')
        ] },
        { label: 'Servicios', sections: [
          S('Servicios básicos del predio', [
            T('Agua potable', { t: 'chk', on: true, ph: 'Cuenta con conexión' }),
            T('Desagüe', { t: 'chk', on: true, ph: 'Cuenta con conexión' }),
            T('Energía eléctrica', { t: 'chk', on: true, ph: 'Cuenta con conexión' }),
            T('Teléfono', { t: 'chk', on: true, ph: 'Cuenta con línea fija' }),
            T('Gas natural', { t: 'chk', on: false, ph: 'Cuenta con conexión' }),
            T('Tipo de vía frente al predio', { t: 'sel', v: 'ASFALTADA', opts: ['ASFALTADA', 'AFIRMADA', 'TROCHA', 'ADOQUINADA'] }),
            T('Alumbrado público', { t: 'chk', on: true, ph: 'La vía cuenta con alumbrado' })
          ], 'Opcional')
        ] }
      ],
      actions: ['Nuevo', 'Modificar', 'Deshacer', 'Imprimir', 'Guardar']
    },

    ficha_economica: {
      mod: 'Catastro', title: 'Ficha catastral económica',
      endpoint: 'GET /api/v1/catastro/fichas/economica/{codRefCatastral}',
      desc: 'Actividad económica que se desarrolla en la unidad catastral, usada para verificar licencias y determinar el uso real del predio.',
      filters: [T('Código de Ref. Catastral', { v: '200601010150010101001' }), T('Contribuyente', { v: '' }), T('CIIU', { v: '' })],
      sections: [
        S('Actividad económica', [
          T('Código de Ref. Catastral', { t: 'ro', v: '200601010150010101001' }),
          T('Nombre comercial', { v: 'BODEGA EL SOL' }),
          T('CIIU', { t: 'sel', v: 'G-5211-01 — VENTA AL POR MENOR EN ALMACENES', opts: ['G-5211-01 — VENTA AL POR MENOR EN ALMACENES', 'D-1549-19 — RESTAURANTE-POLLERÍA', 'H-5520-02 — RESTAURANTES A DOMICILIO'] }),
          T('Nº de licencia de funcionamiento', { v: '2010-006549' }),
          T('Estado de la licencia', { t: 'sel', v: 'ACTIVA', opts: estados }),
          T('Área destinada al negocio (m²)', { v: '48.00' }),
          T('Nº de trabajadores', { v: '2' }),
          T('Horario de atención', { v: '07:00 — 22:00' }),
          T('Fecha de inicio de actividades', { t: 'date', v: '2010-09-16' }),
          T('Cuenta con anuncio publicitario', { t: 'chk', on: true, ph: 'Verificar autorización de anuncio' })
        ])
      ],
      actions: ['Nuevo', 'Guardar', 'Imprimir']
    },

    ficha_bienes: {
      mod: 'Catastro', title: 'Ficha de bienes comunes',
      endpoint: 'GET /api/v1/catastro/fichas/bienes-comunes/{codEdificacion}',
      desc: 'Áreas comunes de una edificación en régimen de propiedad exclusiva y común, cuyo valor se distribuye entre las unidades según su porcentaje de participación.',
      filters: [T('Cod. Edificación', { v: '200601010150010101' }), T('Denominación', { v: '' })],
      table: {
        title: 'Unidades que participan', count: '6 unidades',
        cols: ['Unidad', 'Contribuyente', 'Área exclusiva m²', '% participación', 'Valor asignado S/'], num: [2, 3, 4],
        rows: [
          ['001', 'MEDINA MEDINA, RUFINA (SUC.)', '86.00', '18.40', '4,412.00'],
          ['002', 'QUIROGA RAMOS, ELEODORO', '86.00', '18.40', '4,412.00'],
          ['003', 'DÍAZ MADRID, JULIO CÉSAR', '92.00', '19.68', '4,720.00'],
          ['004', 'REYES CHUNGA, PEDRO', '86.00', '18.40', '4,412.00'],
          ['005', 'SILVA CÓRDOVA, ANA', '61.00', '13.05', '3,128.00'],
          ['006', 'NOBLECILLA ARISMENDIZ SAC', '56.00', '11.98', '2,872.00']
        ],
        note: 'La suma de porcentajes de participación debe ser exactamente 100.00 para que el sistema permita grabar la ficha.'
      },
      sections: [
        S('Bienes comunes de la edificación', [
          T('Cod. Edificación', { t: 'ro', v: '200601010150010101' }),
          T('Denominación', { v: 'EDIFICIO SANTA ROSA' }),
          T('Nº de pisos', { v: '3' }), T('Nº de unidades', { v: '6' }),
          T('Área común de terreno (m²)', { v: '124.00' }),
          T('Área común construida (m²)', { v: '86.00' }),
          T('Valor de bienes comunes (S/)', { t: 'ro', v: '23,956.00' }),
          T('Reglamento interno inscrito', { t: 'chk', on: true, ph: 'Partida registral del régimen' }),
          T('Partida del régimen', { v: 'P11088412' })
        ])
      ],
      totals: [
        { label: 'Área común total', value: '210.00 m²' },
        { label: 'Valor bienes comunes', value: 'S/ 23,956.00' },
        { label: 'Participación asignada', value: '100.00 %' },
        { label: 'Unidades', value: '6', strong: 1 }
      ],
      actions: ['Distribuir valor', 'Guardar']
    },

    ficha_rural: {
      mod: 'Catastro', title: 'Ficha catastral rural',
      endpoint: 'GET /api/v1/catastro/fichas/rural/{codUnidad}',
      desc: 'Predio rústico valorizado por hectárea según el arancel rural, el tipo de tierra y la disponibilidad de riego.',
      filters: [T('Cod. Unidad Catastral (UC)', { v: '11024-0418' }), T('Contribuyente', { v: '' }), T('Valle / sector', { t: 'sel', v: 'Todos', opts: ['Todos', 'Valle del Chira', 'Cieneguillo', 'Miguel Checa', 'Lancones'] })],
      sections: [
        S('Identificación del predio rústico', [
          T('Cod. Unidad Catastral (UC)', { t: 'ro', v: '11024-0418' }),
          T('Nombre del predio', { v: 'FUNDO LA CAPILLA' }),
          T('Valle / sector', { t: 'sel', v: 'Valle del Chira', opts: ['Valle del Chira', 'Cieneguillo', 'Miguel Checa', 'Lancones'] }),
          T('Comisión de regantes', { v: 'JUNTA DE USUARIOS DEL CHIRA' }),
          T('Cod. Contribuyente Rentas', { v: '00000006551' }),
          T('Partida registral', { v: 'P11033872' })
        ]),
        S('Tierras y valuación', [
          T('Área total (ha)', { v: '4.5000' }),
          T('Tipo de tierra', { t: 'sel', v: 'A2 — CULTIVO EN LIMPIO', opts: ['A1 — CULTIVO EN LIMPIO', 'A2 — CULTIVO EN LIMPIO', 'C — CULTIVO PERMANENTE', 'P — PASTOS', 'F — FORESTAL', 'X — PROTECCIÓN'] }),
          T('Condición de riego', { t: 'sel', v: 'BAJO RIEGO', opts: ['BAJO RIEGO', 'SECANO'] }),
          T('Cultivo predominante', { t: 'sel', v: 'ARROZ', opts: ['ARROZ', 'BANANO', 'MANGO', 'LIMÓN', 'MAÍZ AMARILLO', 'ALGODÓN'] }),
          T('Arancel rural (S/ por ha)', { t: 'ro', v: '18,400.00' }),
          T('Valor del terreno rústico (S/)', { t: 'ro', v: '82,800.00' }),
          T('Valor de instalaciones fijas (S/)', { v: '12,400.00' }),
          T('Autovalúo rural (S/)', { t: 'ro', v: '95,200.00' })
        ])
      ],
      actions: ['Calcular', 'Guardar', 'Imprimir ficha rural']
    },

    calles: {
      mod: 'Catastro', title: 'Mantenimiento de vías y calles',
      endpoint: 'GET /api/v1/catastro/vias',
      desc: 'Nomenclatura vial que alimenta el domicilio fiscal y la ubicación del predio. Cada vía guarda su tipo, sector y arancel unitario por tramo.',
      filters: [T('Código de vía', { v: '' }), T('Nombre de calle', { v: '' }), T('Tipo de vía', { t: 'sel', v: 'Todos', opts: ['Todos', 'AVENIDA', 'CALLE', 'JIRÓN', 'PASAJE', 'CARRETERA', 'PROLONGACIÓN'] }), T('Sector', { t: 'sel', v: 'Todos', opts: ['Todos', '01', '02', '03', '04', '05'] })],
      table: {
        title: 'Vías registradas', count: '5 de 2,184',
        cols: ['Código', 'Tipo Vía', 'Nombre', 'Sector', 'Zona', 'Arancel S/ m²', 'Estado'], num: [5],
        rows: [
          ['00001182', 'AVENIDA', 'JOSÉ DE LAMA', '01', 'Zona 1', '412.60', ['ACTIVA', 'ok']],
          ['00001183', 'CALLE', 'SANTA ROSA', '01', 'Zona 2', '198.40', ['ACTIVA', 'ok']],
          ['00001184', 'CALLE', 'LAMA', '02', 'Zona 2', '198.40', ['ACTIVA', 'ok']],
          ['00001185', 'PASAJE', 'EL ALTO', '02', 'Zona 3', '142.80', ['ACTIVA', 'ok']],
          ['00001186', 'CARRETERA', 'SULLANA — PAITA', '05', 'Zona 4', '96.20', ['INACTIVA', 'bad']]
        ]
      },
      sections: [
        S('Datos de la vía', [
          T('Código de vía', { t: 'ro', v: '00001183' }),
          T('Tipo de vía', { t: 'sel', v: 'CALLE', opts: ['AVENIDA', 'CALLE', 'JIRÓN', 'PASAJE', 'CARRETERA', 'PROLONGACIÓN'] }),
          T('Nombre', { v: 'SANTA ROSA' }),
          T('Sector', { t: 'sel', v: '01', opts: ['01', '02', '03', '04', '05'] }),
          T('Zona de arancel', { t: 'sel', v: 'Zona 2', opts: ['Zona 1', 'Zona 2', 'Zona 3', 'Zona 4'] }),
          T('Cuadra desde', { v: '1' }), T('Cuadra hasta', { v: '12' }),
          T('Estado', { t: 'sel', v: 'ACTIVA', opts: ['ACTIVA', 'INACTIVA'] })
        ])
      ],
      actions: ['Nuevo', 'Guardar', 'Inactivar']
    },

    sectores: {
      mod: 'Catastro', title: 'Sectores, manzanas y lotes',
      endpoint: 'GET /api/v1/catastro/sectores',
      desc: 'Estructura territorial sobre la que se arma el código de referencia catastral y se agrupan los padrones por zona.',
      filters: [T('Sector', { t: 'sel', v: 'Todos', opts: ['Todos', '01', '02', '03', '04', '05'] }), T('Manzana', { v: '' })],
      table: {
        title: 'Estructura territorial', count: '5 sectores · 418 manzanas',
        cols: ['Sector', 'Denominación', 'Manzanas', 'Lotes', 'Predios inscritos', 'Zona de arbitrios', 'Estado'], num: [2, 3, 4],
        rows: [
          ['01', 'CERCADO DE SULLANA', '96', '2,418', '2,384', 'Zona 1', ['ACTIVO', 'ok']],
          ['02', 'ZONA INDUSTRIAL', '84', '1,982', '1,944', 'Zona 2', ['ACTIVO', 'ok']],
          ['03', 'BARRIO BUENOS AIRES', '112', '3,104', '3,018', 'Zona 2', ['ACTIVO', 'ok']],
          ['04', 'BELLAVISTA LÍMITE', '68', '1,412', '1,388', 'Zona 3', ['ACTIVO', 'ok']],
          ['05', 'EJE CARRETERA PAITA', '58', '984', '902', 'Zona 4', ['ACTIVO', 'ok']]
        ]
      },
      actions: ['Nuevo sector', 'Guardar']
    },

    aranceles: {
      mod: 'Catastro', title: 'Aranceles de terreno',
      endpoint: 'GET /api/v1/catastro/tablas/aranceles?anio=2026',
      desc: 'Valor oficial del metro cuadrado de terreno por vía y tramo, publicado anualmente. Es el multiplicador del área de terreno en el autovalúo.',
      filters: [T('Ejercicio', { t: 'sel', v: '2026', opts: yrs }), T('Vía', { v: '' }), T('Zona', { t: 'sel', v: 'Todas', opts: ['Todas', 'Zona 1', 'Zona 2', 'Zona 3', 'Zona 4'] })],
      table: {
        title: 'Aranceles vigentes 2026', count: '6 tramos',
        cols: ['Vía', 'Cuadra desde', 'Cuadra hasta', 'Zona', 'Arancel S/ m²', 'Variación vs. 2025'], num: [1, 2, 4, 5],
        rows: [
          ['AV. JOSÉ DE LAMA', '1', '6', 'Zona 1', '412.60', '+4.2 %'],
          ['AV. JOSÉ DE LAMA', '7', '14', 'Zona 1', '386.40', '+4.0 %'],
          ['CALLE SANTA ROSA', '1', '12', 'Zona 2', '198.40', '+3.8 %'],
          ['CALLE LAMA', '1', '10', 'Zona 2', '198.40', '+3.8 %'],
          ['PASAJE EL ALTO', '1', '4', 'Zona 3', '142.80', '+3.2 %'],
          ['CARRETERA SULLANA — PAITA', '1', '8', 'Zona 4', '96.20', '+2.8 %']
        ],
        note: 'Aranceles aprobados por el Ministerio de Vivienda, Construcción y Saneamiento para el ejercicio 2026.'
      },
      actions: ['Importar tabla del año', 'Guardar']
    },

    valores_unitarios: {
      mod: 'Catastro', title: 'Valores unitarios de edificación',
      endpoint: 'GET /api/v1/catastro/tablas/valores-unitarios?anio=2026',
      desc: 'Tabla oficial por categoría constructiva. El sistema suma las siete partidas declaradas en la ficha y les aplica la depreciación correspondiente.',
      filters: [T('Ejercicio', { t: 'sel', v: '2026', opts: yrs }), T('Región', { t: 'sel', v: 'COSTA', opts: ['COSTA', 'SIERRA', 'SELVA'] })],
      table: {
        title: 'Valores unitarios oficiales de edificación — costa 2026 (S/ por m²)', count: '7 categorías',
        cols: ['Cat.', 'Muros y columnas', 'Techos', 'Pisos', 'Puertas y ventanas', 'Revestimientos', 'Baños', 'Inst. eléct. y sanit.'], num: [1, 2, 3, 4, 5, 6, 7],
        rows: [
          ['A', '451.28', '212.90', '148.36', '204.12', '286.44', '78.20', '212.10'],
          ['B', '341.72', '162.14', '112.88', '158.42', '221.06', '58.72', '160.44'],
          ['C', '256.18', '118.92', '84.36', '112.60', '162.18', '42.10', '118.32'],
          ['D', '182.44', '86.20', '61.42', '78.14', '112.36', '28.44', '84.16'],
          ['E', '124.36', '58.72', '41.20', '52.88', '76.42', '18.62', '56.44'],
          ['F', '78.20', '34.16', '24.88', '31.44', '44.20', '10.36', '32.18'],
          ['G', '41.62', '18.44', '12.36', '16.20', '22.88', '4.12', '16.44']
        ]
      },
      actions: ['Importar tabla del año', 'Guardar']
    },

    depreciacion: {
      mod: 'Catastro', title: 'Tabla de depreciación',
      endpoint: 'GET /api/v1/catastro/tablas/depreciacion?anio=2026',
      desc: 'Porcentaje que se descuenta del valor de edificación según antigüedad, material predominante (MEP) y estado de conservación (ECS).',
      filters: [T('Material (MEP)', { t: 'sel', v: 'LADRILLO', opts: ['CONCRETO', 'LADRILLO', 'ADOBE', 'QUINCHA', 'MADERA'] }), T('Uso', { t: 'sel', v: 'CASA HABITACIÓN', opts: ['CASA HABITACIÓN', 'TIENDAS Y OFICINAS', 'INDUSTRIA'] })],
      table: {
        title: 'Depreciación por antigüedad y estado — ladrillo, casa habitación', count: '6 rangos',
        cols: ['Antigüedad', 'Muy bueno %', 'Bueno %', 'Regular %', 'Malo %'], num: [1, 2, 3, 4],
        rows: [
          ['Hasta 5 años', '0', '3', '8', '15'],
          ['6 a 10 años', '3', '8', '15', '24'],
          ['11 a 20 años', '8', '17', '27', '39'],
          ['21 a 30 años', '15', '25', '38', '52'],
          ['31 a 40 años', '22', '33', '48', '64'],
          ['Más de 40 años', '30', '42', '58', '76']
        ]
      }
    },

    consulta_fichas: {
      mod: 'Catastro', title: 'Consulta de fichas catastrales',
      endpoint: 'GET /api/v1/catastro/fichas',
      desc: 'Búsqueda transversal de fichas por código, titular o ubicación, con el estado de conciliación entre catastro y el padrón de rentas.',
      filters: [T('Cod. Ref. Catastral', { v: '' }), T('Contribuyente', { v: '' }), T('Manzana', { v: '' }), T('Lote', { v: '' }), T('Conciliada con rentas', { t: 'sel', v: 'Todas', opts: ['Todas', 'Sí', 'No'] })],
      table: {
        title: 'Fichas encontradas', count: '4 de 48,412',
        cols: ['Cod. Ref. Catastral', 'Cod. Predial Rentas', 'Titular', 'Uso', 'Área terreno m²', 'Área const. m²', 'Conciliada'], num: [4, 5],
        rows: [
          ['200601010150010101001', '02-014-D-14-01', 'MEDINA MEDINA, RUFINA (SUC.)', 'Casa habitación', '210.00', '164.50', ['Sí', 'ok']],
          ['200601010150010101002', '02-014-D-14-02', 'QUIROGA RAMOS, ELEODORO', 'Comercio', '120.00', '96.00', ['Sí', 'ok']],
          ['200601010160020101001', '—', 'REYES CHUNGA, PEDRO', 'Casa habitación', '160.00', '120.00', ['No', 'bad']],
          ['200601020210070100000', '04-021-B-07-00', 'CASTILLO PASCUALA, MARÍA E.', 'Terreno sin construir', '184.00', '0.00', ['Sí', 'ok']]
        ],
        note: 'Las fichas no conciliadas no generan deuda predial hasta que se les asigne código predial de rentas.'
      },
      actions: ['Exportar Excel', 'Conciliar seleccionadas']
    },

    /* ── RENTAS · REGISTRO ────────────────────────────────── */

    contribuyentes: {
      mod: 'Rentas · Registro', title: 'Contribuyentes',
      endpoint: 'GET /api/v1/rentas/contribuyentes',
      desc: 'Padrón único del contribuyente. Su código enlaza predios, vehículos, licencias, papeletas y la cuenta corriente.',
      filters: [T('Código', { v: '' }), T('Nombre / razón social', { v: '' }), T('D.N.I.', { v: '03593174' }), T('R.U.C.', { v: '' })],
      table: {
        title: 'Contribuyentes encontrados', count: '4 de 62,418',
        cols: ['Est.', 'Código', 'Nombre / razón social', 'D.N.I.', 'R.U.C.', 'Dirección', 'Predios', 'Deuda S/'], num: [6, 7],
        rows: [
          [['A', 'ok'], '00000025673', 'SUC. RUFINA MEDINA MEDINA', '03593174', '—', 'URB. SANTA ROSA — EL ALTO 116', '2', '1,842.60'],
          [['A', 'ok'], '00000003541', 'CASTILLO PASCUALA, MARÍA ELENA', '44218937', '—', 'CALLE LAMA 482', '2', '591.94'],
          [['A', 'ok'], '00000006550', 'DÍAZ MADRID, JULIO CÉSAR', '02718844', '—', 'C.P. BARRIO BUENOS AIRES', '3', '9,412.15'],
          [['I', 'bad'], '00000006551', 'NOBLECILLA ARISMENDIZ SAC', '—', '20525118447', 'AV. JOSÉ DE LAMA 1180', '1', '412.00']
        ]
      },
      tabs: [
        { label: 'Identificación del Contribuyente', sections: [
          S('Identificación', [
            T('Código', { t: 'ro', v: '00000025673' }),
            T('Tipo de persona', { t: 'sel', v: 'NATURAL', opts: ['NATURAL', 'JURÍDICA', 'SUCESIÓN INDIVISA', 'SOCIEDAD CONYUGAL'] }),
            T('Apellido paterno', { v: 'MEDINA' }), T('Apellido materno', { v: 'MEDINA' }),
            T('Nombres', { v: 'RUFINA' }),
            T('Razón social', { ph: 'Solo persona jurídica', wide: 1 }),
            T('D.N.I.', { v: '03593174' }), T('R.U.C.', { v: '' }),
            T('Fecha de nacimiento', { t: 'date', v: '1948-08-30' }),
            T('Sexo', { t: 'sel', v: 'FEMENINO', opts: ['MASCULINO', 'FEMENINO'] }),
            T('Estado civil', { t: 'sel', v: 'VIUDO(A)', opts: ['SOLTERO(A)', 'CASADO(A)', 'VIUDO(A)', 'DIVORCIADO(A)', 'CONVIVIENTE'] }),
            T('Cónyuge', { v: '' }),
            T('Calificación del contribuyente', { t: 'sel', v: '003 — PEQUEÑO CONTRIBUYENTE', opts: ['001 — PRINCIPAL CONTRIBUYENTE', '002 — MEDIANO CONTRIBUYENTE', '003 — PEQUEÑO CONTRIBUYENTE'] }),
            T('Estado', { t: 'sel', v: 'A — ACTIVO', opts: ['A — ACTIVO', 'I — INACTIVO', 'B — BAJA', 'F — FALLECIDO', 'N — NO HABIDO'] })
          ])
        ] },
        { label: 'Domicilio Fiscal', sections: [
          S('Domicilio fiscal', [
            T('Tipo de vía', { t: 'sel', v: '02 — CA - CALLE', opts: ['01 — AV - AVENIDA', '02 — CA - CALLE', '03 — JR - JIRÓN', '04 — PS - PASAJE', '05 — CR - CARRETERA', '99 — NO ESPECIFICADO'] }),
            T('Vía', { v: '99999999 — NO ESPECIFICADO', wide: 1 }),
            T('Hab. Urbana', { v: '200601000 — SULLANA', wide: 1 }),
            T('Número', { v: '116' }), T('Número adicional', { v: '' }),
            T('Departamento', { t: 'ro', v: 'PIURA' }), T('Provincia', { t: 'ro', v: 'SULLANA' }),
            T('Distrito', { t: 'ro', v: 'SULLANA' })
          ]),
          S('Edificación', [
            T('Nombre de la edificación', { v: '', wide: 1 }),
            T('Tipo edific.', { t: 'sel', v: '99 — NO ESPECIFICADO', opts: ['01 — CASA', '02 — EDIFICIO', '03 — QUINTA', '04 — CENTRO COMERCIAL', '99 — NO ESPECIFICADO'] }),
            T('Tipo interior', { t: 'sel', v: '99 — NO ESPECIFICADO', opts: ['01 — DEPARTAMENTO', '02 — INTERIOR', '03 — OFICINA', '04 — TIENDA', '99 — NO ESPECIFICADO'] }),
            T('Núm. interior', { v: '' })
          ]),
          S('Zona - Sector - Etapa', [
            T('Nombre', { v: 'URB. SANTA ROSA — EL ALTO', wide: 1 }),
            T('Manzana', { v: '015' }), T('Lote', { v: '001' }), T('Sub lote', { v: '' }),
            T('Dirección adicional', { v: '', wide: 1 })
          ])
        ] },
        { label: 'Documentos', sections: [
          S('Documentos del contribuyente', [
            T('Tipo de documento', { t: 'sel', v: '02 — DNI', opts: ['01 — NO PRESENTÓ DOCUMENTO', '02 — DNI', '03 — CARNET DE IDENTIDAD DE POLICÍA NACIONAL', '04 — CARNET DE IDENTIDAD DE FUERZAS ARMADAS', '05 — PARTIDA DE NACIMIENTO', '06 — PASAPORTE', '07 — CARNET DE EXTRANJERÍA', '08 — OTROS (ESPECIFICAR)', '09 — RUC', '99 — NO ESPECIFICADO'], wide: 1 }),
            T('Número de documento', { v: '03593174' })
          ], 'Nuevo · Agregar · Editar doc. · Quitar')
        ] },
        { label: 'Contactos', sections: [
          S('Contactos registrados', [
            T('Nombre del contacto', { v: 'FERNANDO RUIZ INGA', wide: 1 }),
            T('Cargo', { v: 'GERENTE' }),
            T('E-Mail', { v: 'FRUIZ159@GMAIL.COM' }),
            T('Teléfonos', { v: '969032194' })
          ], 'Nuevo · Agregar · Editar · Quitar')
        ] },
        { label: 'Gestores', sections: [
          S('Gestores del contribuyente', [
            T('Código gestor', { v: '00000001 — GESTOR 1', wide: 1 }),
            T('Fecha inicio', { t: 'date', v: '2026-01-01' }),
            T('Fecha fin', { t: 'date', v: '2026-12-31' }),
            T('Observación', { t: 'area', wide: 1 })
          ], 'Nuevo · Agregar · Editar · Quitar')
        ] },
        { label: 'Teléfonos - EMail', sections: [
          S('Teléfonos', [
            T('Tipo de teléfono', { t: 'sel', v: '01 — DOMICILIO 1', opts: ['01 — DOMICILIO 1', '02 — DOMICILIO 2', '03 — CELULAR', '04 — TRABAJO', '05 — FAX', '99 — NO ESPECIFICADO'] }),
            T('Número', { v: '073-413074' })
          ], 'Nuevo · Agregar · Editar · Quitar'),
          S('E-Mail', [
            T('Dirección', { v: 'FRUIZ159@GMAIL.COM', ph: 'Ej. micorreo@dominio.com', wide: 1 }),
            T('Autoriza notificación electrónica', { t: 'chk', on: true })
          ], 'Nuevo · Agregar · Editar · Quitar')
        ] },
        { label: 'Observaciones', sections: [
          S('Observaciones del registro', [
            T('Observación', { t: 'area', wide: 1, v: 'MODIFICACIÓN DE PRUEBA' }),
            T('Registrado por', { t: 'ro', v: 'MRIOS — 12/08/2026 09:14' }),
            T('Última modificación', { t: 'ro', v: 'MRIOS — 03/07/2026 16:02' })
          ], 'Nueva obs. · Agregar')
        ] },
        { label: 'Fotos', sections: [
          S('Foto álbum personal', [
            T('Historial de fotos', { t: 'ro', v: '2 imágenes — 12/08/2026, 03/07/2026', wide: 1 }),
            T('Descripción de la imagen', { v: '', wide: 1 })
          ], 'Capturar · Cargar · Guardar · Quitar')
        ] },
        { label: 'Predios y vehículos', sections: [
          S('Unidades afectas del contribuyente', [
            T('Predios registrados', { t: 'ro', v: '2' }),
            T('Autovalúo acumulado (S/)', { t: 'ro', v: '132,196.75' }),
            T('Vehículos afectos', { t: 'ro', v: '1' }),
            T('Licencias de funcionamiento', { t: 'ro', v: '1' }),
            T('Papeletas pendientes', { t: 'ro', v: '2' }),
            T('Convenios vigentes', { t: 'ro', v: '1' })
          ], 'Solo lectura')
        ] }
      ],
      actions: ['Nuevo', 'Modificar', 'Imprimir', 'Guardar']
    },

    predios_rentas: {
      mod: 'Rentas · Registro', title: 'Predios del contribuyente',
      endpoint: 'GET /api/v1/rentas/predios?contribuyente={codigo}',
      desc: 'Padrón predial de rentas. Cada predio guarda su autovalúo, condición de propiedad y la fecha desde la que genera obligación.',
      filters: [T('Cod. Contribuyente', { v: '00000025673' }), T('Código predial', { v: '' }), T('Sector', { t: 'sel', v: 'Todos', opts: ['Todos', '01', '02', '03', '04', '05'] }), T('Condición', { t: 'sel', v: 'Todas', opts: ['Todas', 'Afecto', 'Inafecto', 'Exonerado', 'Transferido'] })],
      table: {
        title: 'Predios registrados', count: '2 predios · autovalúo S/ 170,616.75',
        cols: ['Código predial', 'Ubicación', 'Uso', 'Terreno m²', 'Const. m²', '% prop.', 'Autovalúo S/', 'Condición'], num: [3, 4, 5, 6],
        rows: [
          ['02-014-D-14-01', 'CALLE SANTA ROSA 116', 'Casa habitación', '210.00', '164.50', '100.00', '132,196.75', ['Afecto', 'ok']],
          ['04-021-B-07-00', 'MZ. B LT. 7 — BELLAVISTA', 'Terreno sin construir', '184.00', '0.00', '50.00', '38,420.00', ['Afecto', 'ok']]
        ]
      },
      sections: [
        S('Datos del predio', [
          T('Código predial', { t: 'ro', v: '02-014-D-14-01' }),
          T('Cod. Ref. Catastral', { t: 'ro', v: '200601010150010101001' }),
          T('Uso del predio', { t: 'sel', v: 'CASA HABITACIÓN', opts: ['CASA HABITACIÓN', 'COMERCIO', 'INDUSTRIA', 'TERRENO SIN CONSTRUIR', 'SERVICIOS'] }),
          T('Clasificación', { t: 'sel', v: 'URBANO', opts: ['URBANO', 'RÚSTICO'] }),
          T('Condición de propiedad', { t: 'sel', v: 'PROPIETARIO ÚNICO', opts: ['PROPIETARIO ÚNICO', 'COPROPIETARIO', 'POSEEDOR', 'SUCESIÓN'] }),
          T('% de propiedad', { v: '100.00' }),
          T('Fecha de adquisición', { t: 'date', v: '2004-06-18' }),
          T('Afecto desde (ejercicio)', { t: 'ro', v: '2005' })
        ]),
        S('Valuación', [
          T('Área de terreno (m²)', { v: '210.00' }),
          T('Arancel (S/ m²)', { t: 'ro', v: '198.40' }),
          T('Valor del terreno (S/)', { t: 'ro', v: '41,664.00' }),
          T('Área construida (m²)', { t: 'ro', v: '164.50' }),
          T('Valor de construcción (S/)', { t: 'ro', v: '86,412.75' }),
          T('Obras complementarias (S/)', { t: 'ro', v: '4,120.00' }),
          T('Autovalúo del predio (S/)', { t: 'ro', v: '132,196.75' })
        ])
      ],
      actions: ['Nuevo', 'Guardar', 'Ver ficha catastral']
    },

    predial_individual: {
      mod: 'Rentas · Registro', title: 'Cálculo individual del impuesto predial',
      endpoint: 'POST /api/v1/rentas/predial/calculo-individual',
      desc: 'Determina el impuesto de un contribuyente sobre el autovalúo acumulado de todos sus predios en el distrito, con la escala progresiva acumulativa y el mínimo imponible de 0.6 % de la UIT.',
      filters: [
        T('Cod. Contribuyente', { v: '00000025673' }),
        T('Año', { t: 'sel', v: '2026', opts: yrs }),
        T('DJ N°', { v: '000418' }),
        T('Tipo de declaración', { t: 'sel', v: 'RECTIFICATORIA', opts: ['INSCRIPCIÓN', 'DESCARGO', 'RECTIFICATORIA', 'ANUAL MECANIZADA'] }),
        T('Fecha de declaración', { t: 'date', v: '2026-02-27' })
      ],
      table: {
        title: 'Predios que integran la base imponible', count: '2 predios',
        cols: ['Código predial', 'Ubicación', 'Uso', '% prop.', 'Valuo Total S/', 'Valuo Exonerado S/', 'Valuo Afecto S/'], num: [3, 4, 5, 6],
        rows: [
          ['02-014-D-14-01', 'CALLE SANTA ROSA 116', 'Casa habitación', '100.00', '132,196.75', '0.00', '132,196.75'],
          ['04-021-B-07-00', 'MZ. B LT. 7 — BELLAVISTA', 'Terreno sin construir', '50.00', '38,420.00', '0.00', '19,210.00']
        ],
        note: 'Fases del cálculo: REGISTRO → HR (hoja resumen) → PU (predio urbano) → PR (predio rústico). El sistema no permite emitir la cuponera si alguna fase presenta inconsistencia.'
      },
      sections: [
        S('Escala progresiva acumulativa', [
          T('UIT vigente 2026 (S/)', { t: 'ro', v: '5,350.00' }),
          T('Valuo Total (S/)', { t: 'ro', v: '170,616.75' }),
          T('Valuo Exonerado (S/)', { t: 'ro', v: '0.00' }),
          T('Valuo Afecto (S/)', { t: 'ro', v: '151,406.75' }),
          T('Tramo 1 — hasta 15 UIT (0.2 %)', { t: 'ro', v: 'S/ 80,250.00 → S/ 160.50' }),
          T('Tramo 2 — de 15 a 60 UIT (0.6 %)', { t: 'ro', v: 'S/ 71,156.75 → S/ 426.94' }),
          T('Tramo 3 — más de 60 UIT (1.0 %)', { t: 'ro', v: 'S/ 0.00 → S/ 0.00' }),
          T('Impuesto insoluto anual (S/)', { t: 'ro', v: '587.44' }),
          T('Mínimo imponible (0.6 % UIT)', { t: 'ro', v: '32.10' })
        ]),
        S('Beneficios aplicados', [
          T('Deducción pensionista / adulto mayor', { t: 'sel', v: 'NO APLICA', opts: ['NO APLICA', 'PENSIONISTA — 50 UIT', 'ADULTO MAYOR NO PENSIONISTA — 50 UIT'] }),
          T('Nº de resolución', { ph: 'RES-0000-2026-MPS' }),
          T('Inafectación', { t: 'sel', v: 'NINGUNA', opts: ['NINGUNA', 'GOBIERNO CENTRAL', 'ENTIDAD RELIGIOSA', 'CUERPO DE BOMBEROS', 'BENEFICENCIA'] }),
          T('Monto deducido (S/)', { t: 'ro', v: '0.00' })
        ], 'Opcional'),
        S('Emisión y cuotas', [
          T('Modalidad', { t: 'sel', v: 'FRACCIONADO EN 4 CUOTAS', opts: ['AL CONTADO', 'FRACCIONADO EN 4 CUOTAS'] }),
          T('Derecho de emisión (S/)', { t: 'ro', v: '4.50' }),
          T('Cuota 1 — vence 28/02', { t: 'ro', v: '147.98' }),
          T('Cuota 2 — vence 31/05', { t: 'ro', v: '146.86' }),
          T('Cuota 3 — vence 31/08', { t: 'ro', v: '146.86' }),
          T('Cuota 4 — vence 30/11', { t: 'ro', v: '146.86' })
        ])
      ],
      totals: [
        { label: 'Valuo afecto', value: 'S/ 151,406.75' },
        { label: 'Impuesto insoluto', value: 'S/ 587.44' },
        { label: 'Derecho de emisión', value: 'S/ 4.50' },
        { label: 'Total a pagar', value: 'S/ 591.94', strong: 1 }
      ],
      actions: ['Buscar', 'Simular', 'Calcular']
    },

    predial_masivo: {
      mod: 'Rentas · Registro', title: 'Cálculo masivo del impuesto predial',
      endpoint: 'POST /api/v1/rentas/predial/calculo-masivo',
      desc: 'Proceso batch de emisión anual. Recalcula todo el padrón para el ejercicio seleccionado y deja constancia de los contribuyentes observados que quedan fuera de la emisión.',
      sections: [
        S('Parámetros del proceso', [
          T('Ejercicio a calcular', { t: 'sel', v: '2026', opts: yrs }),
          T('Alcance', { t: 'sel', v: 'TODO EL PADRÓN', opts: ['TODO EL PADRÓN', 'POR SECTOR', 'POR RANGO DE CÓDIGO', 'SOLO OBSERVADOS'] }),
          T('Sector', { t: 'sel', v: 'Todos', opts: ['Todos', '01', '02', '03', '04', '05'] }),
          T('UIT del ejercicio (S/)', { t: 'ro', v: '5,350.00' }),
          T('Derecho de emisión (S/)', { v: '4.50' }),
          T('Incluye arbitrios', { t: 'chk', on: true, ph: 'Emitir arbitrios junto al predial' }),
          T('Recalcula ya emitidos', { t: 'chk', on: false, ph: 'Sobrescribe cuponeras existentes' }),
          T('Genera cuponera PDF', { t: 'chk', on: true, ph: 'Produce archivo para imprenta' })
        ])
      ],
      table: {
        title: 'Resultado de la última corrida', count: 'Ejecutada el 28/01/2026 — 02:14 h',
        cols: ['Etapa', 'Registros', 'Monto S/', 'Observados', 'Estado'], num: [1, 2, 3],
        rows: [
          ['Lectura del padrón', '62,418', '—', '0', ['Completa', 'ok']],
          ['Valuación de predios', '78,204', '1,842,116,420.00', '412', ['Completa', 'ok']],
          ['Determinación del impuesto', '61,884', '9,418,204.60', '534', ['Completa', 'ok']],
          ['Determinación de arbitrios', '61,884', '5,884,110.20', '188', ['Completa', 'ok']],
          ['Generación de cuponeras', '61,350', '—', '534', ['Con observados', 'warn']]
        ],
        note: 'Los contribuyentes observados quedan sin emisión hasta que se corrija la inconsistencia (predio sin arancel, ficha no conciliada o titularidad incompleta).'
      },
      actions: ['Simular', 'Ver observados', 'Ejecutar proceso']
    },

    declaracion_jurada: {
      mod: 'Rentas · Registro', title: 'Declaración jurada — HR, PU y PR',
      endpoint: 'GET /api/v1/rentas/declaraciones/{djNro}',
      desc: 'Formularios de la declaración: hoja resumen (HR), predio urbano (PU) y predio rústico (PR). Se imprimen para la firma del contribuyente y quedan como sustento del cálculo.',
      filters: [T('DJ N°', { v: '000418' }), T('Cod. Contribuyente', { v: '00000025673' }), T('Año', { t: 'sel', v: '2026', opts: yrs }), T('Tipo', { t: 'sel', v: 'Todas', opts: ['Todas', 'INSCRIPCIÓN', 'DESCARGO', 'RECTIFICATORIA', 'ANUAL MECANIZADA'] })],
      table: {
        title: 'Declaraciones presentadas', count: '4 de 1,184',
        cols: ['DJ N°', 'Año', 'Contribuyente', 'Tipo', 'Fecha', 'Predios', 'Valuo afecto S/', 'Estado'], num: [5, 6],
        rows: [
          ['000418', '2026', 'MEDINA MEDINA, RUFINA (SUC.)', 'RECTIFICATORIA', '27/02/2026', '2', '151,406.75', ['Procesada', 'ok']],
          ['000392', '2026', 'CASTILLO PASCUALA, MARÍA E.', 'ANUAL MECANIZADA', '15/01/2026', '2', '151,406.75', ['Procesada', 'ok']],
          ['000401', '2026', 'DÍAZ MADRID, JULIO CÉSAR', 'INSCRIPCIÓN', '04/03/2026', '3', '284,120.00', ['Observada', 'warn']],
          ['000388', '2025', 'NOBLECILLA ARISMENDIZ SAC', 'DESCARGO', '18/11/2025', '1', '0.00', ['Procesada', 'ok']]
        ]
      },
      sections: [
        S('Formularios a emitir', [
          T('HR — Hoja resumen', { t: 'chk', on: true, ph: 'Resumen de predios y determinación' }),
          T('PU — Predio urbano', { t: 'chk', on: true, ph: 'Un formulario por predio urbano' }),
          T('PR — Predio rústico', { t: 'chk', on: false, ph: 'Un formulario por predio rústico' }),
          T('Nº de ejemplares', { t: 'sel', v: '2', opts: ['1', '2', '3'] }),
          T('Enviar a OpenOffice', { t: 'chk', on: false, ph: 'Exporta en lugar de imprimir' })
        ])
      ],
      actions: ['Vista previa', 'Imprimir HR / PU / PR']
    },

    arbitrios: {
      mod: 'Rentas · Registro', title: 'Arbitrios municipales',
      endpoint: 'GET /api/v1/rentas/arbitrios?anio=2026',
      desc: 'Limpieza pública, parques y jardines y serenazgo. La tasa depende del uso del predio, la zona, la frecuencia del servicio y los metros de frontis declarados en la ficha.',
      filters: [T('Ejercicio', { t: 'sel', v: '2026', opts: yrs }), T('Código predial', { v: '02-014-D-14-01' }), T('Zona', { t: 'sel', v: 'Zona 2', opts: ['Zona 1', 'Zona 2', 'Zona 3', 'Zona 4'] }), T('Uso', { t: 'sel', v: 'CASA HABITACIÓN', opts: ['CASA HABITACIÓN', 'COMERCIO', 'INDUSTRIA', 'SERVICIOS', 'TERRENO SIN CONSTRUIR'] })],
      table: {
        title: 'Determinación por servicio', count: '4 servicios · 12 cuotas',
        cols: ['Servicio', 'Criterio de distribución', 'Frecuencia', 'Tasa mensual S/', 'Anual S/', 'Condición'], num: [3, 4],
        rows: [
          ['LIMPIEZA PÚBLICA — BARRIDO', 'Metros lineales de frontis', 'DIARIA', '8.40', '100.80', ['Afecto', 'ok']],
          ['LIMPIEZA PÚBLICA — RECOLECCIÓN', 'Área construida y uso', 'INTERDIARIA', '14.20', '170.40', ['Afecto', 'ok']],
          ['PARQUES Y JARDINES', 'Ubicación del predio', 'PERMANENTE', '6.10', '73.20', ['Afecto', 'ok']],
          ['SERENAZGO', 'Uso y peligrosidad de zona', 'PERMANENTE', '11.80', '141.60', ['Afecto', 'ok']]
        ]
      },
      totals: [
        { label: 'Arbitrio anual', value: 'S/ 486.00' },
        { label: 'Descuento pronto pago', value: '− S/ 48.60' },
        { label: 'Cuotas', value: '12 mensuales' },
        { label: 'Total 2026', value: 'S/ 437.40', strong: 1 }
      ],
      actions: ['Recalcular', 'Emitir cuponera de arbitrios']
    },

    transferencia_predio: {
      mod: 'Rentas · Registro', title: 'Transferencia de predio',
      endpoint: 'POST /api/v1/rentas/transferencias/predio',
      desc: 'Da de baja al transferente y de alta al adquirente desde la fecha del acto. La obligación del vendedor corre hasta el 31 de diciembre del año de la transferencia.',
      sections: [
        S('Datos del acto', [
          T('Nº de expediente', { v: '2026-0918' }),
          T('Tipo de acto', { t: 'sel', v: 'COMPRA-VENTA', opts: ['COMPRA-VENTA', 'DONACIÓN', 'PERMUTA', 'ANTICIPO DE LEGÍTIMA', 'ADJUDICACIÓN', 'DACIÓN EN PAGO', 'SUCESIÓN'] }),
          T('Fecha del acto', { t: 'date', v: '2026-07-18' }),
          T('Nº de minuta / escritura', { v: 'EP-2218-2026' }),
          T('Notaría', { v: 'NOTARÍA ZAPATA — SULLANA' }),
          T('Código predial', { v: '04-021-B-07-00' }),
          T('% transferido', { v: '50.00' })
        ]),
        S('Partes intervinientes', [
          T('Transferente — documento', { v: '44218937' }),
          T('Transferente — nombre', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA' }),
          T('Transferente afecto hasta', { t: 'ro', v: '31/12/2026' }),
          T('Adquirente — documento', { v: '02718844' }),
          T('Adquirente — nombre', { t: 'ro', v: 'DÍAZ MADRID, JULIO CÉSAR' }),
          T('Adquirente afecto desde', { t: 'ro', v: '01/01/2027' }),
          T('Genera alcabala', { t: 'chk', on: true, ph: 'Liquida el impuesto de alcabala' })
        ])
      ],
      actions: ['Validar deuda del transferente', 'Registrar transferencia']
    },

    alcabala: {
      mod: 'Rentas · Registro', title: 'Impuesto de alcabala',
      endpoint: 'POST /api/v1/rentas/alcabala',
      desc: 'Grava la transferencia de propiedad con el 3 % sobre el exceso de las primeras 10 UIT, tomando como base el mayor valor entre el de transferencia y el autovalúo ajustado por el IPM.',
      sections: [
        S('Liquidación', [
          T('Nº de liquidación', { t: 'ro', v: 'ALC-2026-00418' }),
          T('Nº de expediente', { v: '2026-0918' }),
          T('Fecha de la transferencia', { t: 'date', v: '2026-07-18' }),
          T('Valor de transferencia (S/)', { v: '95,000.00' }),
          T('Autovalúo del predio (S/)', { t: 'ro', v: '76,840.00' }),
          T('IPM aplicado', { t: 'ro', v: '1.0206' }),
          T('Autovalúo ajustado (S/)', { t: 'ro', v: '78,420.00' }),
          T('Base de cálculo (el mayor)', { t: 'ro', v: '95,000.00' }),
          T('Tramo inafecto — 10 UIT (S/)', { t: 'ro', v: '53,500.00' }),
          T('Base imponible (S/)', { t: 'ro', v: '41,500.00' }),
          T('Tasa', { t: 'ro', v: '3.0 %' }),
          T('Impuesto de alcabala (S/)', { t: 'ro', v: '1,245.00' }),
          T('Vence el último día hábil del mes siguiente', { t: 'ro', v: '31/08/2026' })
        ])
      ],
      totals: [
        { label: 'Base de cálculo', value: 'S/ 95,000.00' },
        { label: 'Tramo inafecto', value: 'S/ 53,500.00' },
        { label: 'Base imponible', value: 'S/ 41,500.00' },
        { label: 'Alcabala a pagar', value: 'S/ 1,245.00', strong: 1 }
      ],
      actions: ['Liquidar', 'Generar orden de pago', 'Imprimir liquidación']
    },

    vehiculos: {
      mod: 'Rentas · Registro', title: 'Ficha de vehículo',
      endpoint: 'GET /api/v1/rentas/vehiculos/{placa}',
      desc: 'Registro del vehículo. La afectación corre tres ejercicios desde el año siguiente a la primera inscripción registral.',
      filters: [T('Cod. Contribuyente', { v: '00000003541' }), T('Nombre', { v: '' }), T('Nro. Documento', { v: '' }), T('Placa', { v: 'T2G-418' }), T('Nro. Motor', { v: '' })],
      table: {
        title: 'Vehículos encontrados', count: '2 registros',
        cols: ['Est.', 'Placa', 'Clase', 'Marca', 'Modelo', 'Año fab.', 'Contribuyente', 'Afectación'],
        rows: [
          [['B', 'bad'], 'T2G-418', 'AUTOMÓVIL', 'TOYOTA', 'YARIS GLI', '2018', 'CASTILLO PASCUALA, MARÍA E.', '2019 — 2021'],
          [['A', 'ok'], 'V1H-882', 'CAMIONETA', 'HYUNDAI', 'TUCSON', '2024', 'CASTILLO PASCUALA, MARÍA E.', '2025 — 2027']
        ]
      },
      tabs: [
        { label: 'Datos del vehículo', sections: [
          S('Identificación', [
            T('Nro. de tarjeta', { v: 'B-4471182' }),
            T('Repartición', { t: 'sel', v: 'SULLANA', opts: ['SULLANA', 'PIURA', 'LIMA'] }),
            T('Placa', { v: 'T2G-418' }),
            T('Nro. de expediente', { v: '2026-0281' }),
            T('Fecha de inscripción', { t: 'date', v: '2019-02-11' }),
            T('Año de fabricación', { v: '2018' }),
            T('Fecha de ingreso MPS', { t: 'date', v: '2019-03-02' }),
            T('Clase', { t: 'sel', v: 'AUTOMÓVIL', opts: ['AUTOMÓVIL', 'STATION WAGON', 'CAMIONETA', 'CAMIÓN', 'ÓMNIBUS', 'REMOLCADOR'] }),
            T('Marca', { t: 'sel', v: 'TOYOTA', opts: ['TOYOTA', 'HYUNDAI', 'NISSAN', 'KIA', 'SUZUKI', 'CHEVROLET'] }),
            T('Modelo', { v: 'YARIS GLI' }),
            T('Carrocería', { t: 'sel', v: 'SEDÁN', opts: ['SEDÁN', 'HATCHBACK', 'FURGÓN', 'TOLVA', 'CISTERNA'] }),
            T('Combustible', { t: 'sel', v: 'GASOLINA', opts: ['GASOLINA', 'DIÉSEL', 'GLP', 'GNV', 'ELÉCTRICO', 'HÍBRIDO'] }),
            T('Categoría', { t: 'sel', v: 'M1', opts: ['M1', 'M2', 'M3', 'N1', 'N2', 'N3', 'L5'] })
          ]),
          S('Características técnicas', [
            T('Cilindraje (C.C.)', { v: '1497' }),
            T('Cilindros', { v: '4' }), T('Ejes', { v: '2' }), T('Ruedas', { v: '4' }),
            T('Colores', { v: 'PLATA METÁLICO' }),
            T('Nro. de motor', { v: '2NR0483117' }),
            T('Nro. de serie', { v: 'MR2B29F31K1084472' }),
            T('Pasajeros', { v: '5' }), T('Asientos', { v: '5' }),
            T('Peso seco (kg)', { v: '1,050' }), T('Peso bruto (kg)', { v: '1,510' }),
            T('Carga útil (kg)', { v: '460' }),
            T('Longitud (m)', { v: '4.42' }), T('Altura (m)', { v: '1.47' }), T('Ancho (m)', { v: '1.73' })
          ], 'Opcional')
        ] },
        { label: 'Propietario', sections: [
          S('Titular del vehículo', [
            T('Cod. Contribuyente', { t: 'ro', v: '00000003541' }),
            T('Nombre / razón social', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA' }),
            T('Documento', { t: 'ro', v: 'DNI 44218937' }),
            T('Domicilio fiscal', { t: 'ro', v: 'CALLE LAMA 482 — ZONA 2 INDUSTRIAL', wide: 1 }),
            T('Fecha de adquisición', { t: 'date', v: '2019-02-05' }),
            T('Forma de adquisición', { t: 'sel', v: 'COMPRA-VENTA', opts: ['COMPRA-VENTA', 'REMATE', 'DONACIÓN', 'HERENCIA', 'IMPORTACIÓN'] })
          ])
        ] },
        { label: 'Conductor', sections: [
          S('Conductor habitual', [
            T('Documento', { v: '44218937' }),
            T('Nombre', { v: 'CASTILLO PASCUALA, MARÍA ELENA' }),
            T('Nro. de licencia', { v: 'Q44218937' }),
            T('Clase / categoría', { t: 'sel', v: 'A-I', opts: ['A-I', 'A-IIa', 'A-IIb', 'A-IIIa', 'B-IIa', 'B-IIc'] }),
            T('Vencimiento de licencia', { t: 'date', v: '2027-05-30' })
          ], 'Opcional')
        ] },
        { label: 'Datos tributarios', sections: [
          S('Impuesto al patrimonio vehicular', [
            T('Primer año de afectación', { t: 'ro', v: '2019' }),
            T('Último año de afectación', { t: 'ro', v: '2021' }),
            T('Valor de adquisición (S/)', { v: '58,900.00' }),
            T('Tabla referencial MEF (S/)', { t: 'ro', v: '61,400.00' }),
            T('Base imponible — el mayor (S/)', { t: 'ro', v: '61,400.00' }),
            T('Tasa', { t: 'ro', v: '1.0 %' }),
            T('Impuesto anual (S/)', { t: 'ro', v: '614.00' }),
            T('Mínimo imponible (1.5 % UIT)', { t: 'ro', v: '80.25' }),
            T('Estado', { t: 'sel', v: 'B — BAJA POR VENCIMIENTO', opts: ['A — AFECTO', 'I — INAFECTO', 'E — EXONERADO', 'B — BAJA POR VENCIMIENTO'] })
          ])
        ] },
        { label: 'Beneficios', sections: [
          S('Inafectación y exoneración', [
            T('Tipo de beneficio', { t: 'sel', v: 'NINGUNO', opts: ['NINGUNO', 'GOBIERNO CENTRAL', 'CUERPO DIPLOMÁTICO', 'BOMBEROS', 'TRANSPORTE PÚBLICO', 'PERSONA CON DISCAPACIDAD'] }),
            T('Nro. de resolución', { ph: 'RES-0000-2026' }),
            T('Vigencia desde', { t: 'date' }), T('Vigencia hasta', { t: 'date' }),
            T('Sustento', { t: 'area', wide: 1 })
          ], 'Opcional')
        ] },
        { label: 'Observaciones', sections: [
          S('Notas', [T('Observaciones', { t: 'area', wide: 1, v: 'Vehículo con transferencia pendiente de inscripción registral.' })], 'Opcional')
        ] }
      ],
      actions: ['Nuevo', 'Modificar', 'Excel', 'Imprimir', 'Guardar']
    },

    vehicular_calculo: {
      mod: 'Rentas · Registro', title: 'Cálculo del impuesto vehicular',
      endpoint: 'POST /api/v1/rentas/vehicular/calculo',
      desc: 'Aplica el 1 % sobre la base imponible con un mínimo del 1.5 % de la UIT, por los tres ejercicios en que el vehículo permanece afecto.',
      filters: [T('Placa', { v: 'V1H-882' }), T('Cod. Contribuyente', { v: '00000003541' }), T('Ejercicio', { t: 'sel', v: '2026', opts: yrs })],
      table: {
        title: 'Determinación por ejercicio', count: '3 ejercicios afectos',
        cols: ['Ejercicio', 'Base imponible S/', 'Tasa', 'Impuesto S/', 'Cuotas', 'Estado'], num: [1, 3],
        rows: [
          ['2025', '112,800.00', '1.0 %', '1,128.00', '4', ['Cancelado', 'ok']],
          ['2026', '112,800.00', '1.0 %', '1,128.00', '4', ['Emitido', 'warn']],
          ['2027', '112,800.00', '1.0 %', '1,128.00', '4', ['Proyectado', 'warn']]
        ],
        note: 'La base imponible es el mayor valor entre el de adquisición y la tabla referencial del MEF vigente para el año de fabricación.'
      },
      totals: [
        { label: 'Base imponible', value: 'S/ 112,800.00' },
        { label: 'Impuesto anual', value: 'S/ 1,128.00' },
        { label: 'Cuota trimestral', value: 'S/ 282.00' },
        { label: 'Total tres ejercicios', value: 'S/ 3,384.00', strong: 1 }
      ],
      actions: ['Simular', 'Calcular', 'Emitir cuponera']
    },

    transferencia_vehiculo: {
      mod: 'Rentas · Registro', title: 'Transferencia de vehículo',
      endpoint: 'POST /api/v1/rentas/transferencias/vehiculo',
      desc: 'Registra el cambio de titular. El transferente responde por el impuesto hasta el 31 de diciembre del año en que se produce la venta.',
      sections: [
        S('Datos de la transferencia', [
          T('Placa', { v: 'T2G-418' }),
          T('Nro. de expediente', { v: '2026-0944' }),
          T('Fecha de transferencia', { t: 'date', v: '2026-06-20' }),
          T('Tipo de acto', { t: 'sel', v: 'COMPRA-VENTA', opts: ['COMPRA-VENTA', 'DONACIÓN', 'REMATE', 'HERENCIA', 'DACIÓN EN PAGO'] }),
          T('Documento sustentatorio', { t: 'sel', v: 'ACTA NOTARIAL DE TRANSFERENCIA', opts: ['ACTA NOTARIAL DE TRANSFERENCIA', 'CONTRATO CON FIRMA LEGALIZADA', 'PARTE REGISTRAL', 'RESOLUCIÓN JUDICIAL'] }),
          T('Nº del documento', { v: 'AN-1182-2026' })
        ]),
        S('Partes', [
          T('Transferente — documento', { v: '44218937' }),
          T('Transferente — nombre', { t: 'ro', v: 'CASTILLO PASCUALA, MARÍA ELENA' }),
          T('Afecto hasta', { t: 'ro', v: '31/12/2026' }),
          T('Adquirente — documento', { v: '03593174' }),
          T('Adquirente — nombre', { t: 'ro', v: 'SUC. RUFINA MEDINA MEDINA' }),
          T('Afecto desde', { t: 'ro', v: '01/01/2027' }),
          T('Deuda pendiente del transferente (S/)', { t: 'ro', v: '940.64' })
        ])
      ],
      actions: ['Validar deuda', 'Registrar transferencia']
    },

    espectaculos: {
      mod: 'Rentas · Registro', title: 'Espectáculos públicos no deportivos',
      endpoint: 'POST /api/v1/rentas/espectaculos',
      desc: 'Grava el monto que se abona por presenciar el espectáculo. La tasa depende del tipo de evento y el organizador actúa como agente perceptor.',
      filters: [T('Nº de expediente', { v: '' }), T('Organizador', { v: '' }), T('Desde', { t: 'date', v: '2026-01-01' }), T('Hasta', { t: 'date', v: '2026-08-13' })],
      table: {
        title: 'Espectáculos declarados', count: '3 de 84',
        cols: ['Expediente', 'Organizador', 'Espectáculo', 'Fecha', 'Aforo', 'Recaudación S/', 'Tasa', 'Impuesto S/'], num: [4, 5, 7],
        rows: [
          ['2026-0884', 'PRODUCCIONES DEL NORTE EIRL', 'Concierto de cumbia', '18/07/2026', '2,400', '84,000.00', '10 %', '8,400.00'],
          ['2026-0912', 'ASOC. TAURINA SULLANA', 'Corrida de toros', '02/08/2026', '1,800', '126,000.00', '10 %', '12,600.00'],
          ['2026-0918', 'CINE PLAZA SAC', 'Función de cine', '10/08/2026', '320', '4,800.00', '0 %', '0.00']
        ],
        note: 'El cine, el teatro, los conciertos de música clásica, la ópera, el ballet y el folclore nacional están inafectos por ley.'
      },
      sections: [
        S('Declaración del espectáculo', [
          T('Nº de expediente', { v: '2026-0884' }),
          T('Organizador', { v: 'PRODUCCIONES DEL NORTE EIRL' }),
          T('R.U.C.', { v: '20525118880' }),
          T('Tipo de espectáculo', { t: 'sel', v: 'CONCIERTO DE MÚSICA POPULAR', opts: ['CONCIERTO DE MÚSICA POPULAR', 'ESPECTÁCULO TAURINO', 'CARRERA DE CABALLOS', 'DISCOTECA', 'CINE', 'TEATRO', 'FOLCLORE NACIONAL'] }),
          T('Denominación del evento', { v: 'GRAN NOCHE DE CUMBIA', wide: 1 }),
          T('Local', { v: 'COLISEO MUNICIPAL' }),
          T('Fecha del evento', { t: 'date', v: '2026-07-18' }),
          T('Aforo autorizado', { v: '2400' }),
          T('Nº de entradas vendidas', { v: '2,240' }),
          T('Precio promedio (S/)', { v: '37.50' }),
          T('Recaudación declarada (S/)', { t: 'ro', v: '84,000.00' }),
          T('Tasa aplicable', { t: 'ro', v: '10 %' }),
          T('Impuesto a pagar (S/)', { t: 'ro', v: '8,400.00' }),
          T('Garantía depositada (S/)', { v: '8,400.00' })
        ])
      ],
      actions: ['Liquidar', 'Registrar', 'Imprimir liquidación']
    },

    alta_deuda: {
      mod: 'Rentas · Registro', title: 'Alta de deuda',
      endpoint: 'POST /api/v1/rentas/deuda/altas',
      desc: 'Incorpora manualmente una obligación a la cuenta corriente cuando no proviene de la emisión masiva: determinaciones de fiscalización, multas o deuda migrada.',
      sections: [
        S('Deuda a dar de alta', [
          T('Cod. Contribuyente', { v: '00000006550' }),
          T('Nombre', { t: 'ro', v: 'DÍAZ MADRID, JULIO CÉSAR' }),
          T('Concepto / tributo', { t: 'sel', v: 'IMPUESTO PREDIAL', opts: ['IMPUESTO PREDIAL', 'ARBITRIOS MUNICIPALES', 'PATRIMONIO VEHICULAR', 'ALCABALA', 'MULTA TRIBUTARIA', 'MULTA ADMINISTRATIVA', 'DERECHOS ADMINISTRATIVOS'] }),
          T('Unidad (predio / placa)', { v: '02-014-D-14-01' }),
          T('Año', { t: 'sel', v: '2024', opts: yrs }),
          T('Cuota desde', { v: '1' }), T('Cuota hasta', { v: '4' }),
          T('Insoluto (S/)', { v: '1,842.60' }),
          T('Reajuste (S/)', { v: '84.20' }),
          T('Interés (S/)', { v: '212.44' }),
          T('Gastos (S/)', { v: '0.00' }),
          T('Fecha de vencimiento', { t: 'date', v: '2024-11-30' }),
          T('Documento que sustenta', { t: 'sel', v: 'RESOLUCIÓN DE DETERMINACIÓN', opts: ['RESOLUCIÓN DE DETERMINACIÓN', 'RESOLUCIÓN DE MULTA', 'ACTA DE FISCALIZACIÓN', 'MIGRACIÓN DE SISTEMA ANTERIOR', 'RESOLUCIÓN GERENCIAL'] }),
          T('Nº del documento', { v: 'RD-2026-000418' }),
          T('Motivo del alta', { t: 'area', wide: 1, v: 'Deuda omitida detectada en fiscalización predial del programa PF-2026-014.' })
        ])
      ],
      totals: [
        { label: 'Insoluto', value: 'S/ 1,842.60' },
        { label: 'Reajuste', value: 'S/ 84.20' },
        { label: 'Interés', value: 'S/ 212.44' },
        { label: 'Total del alta', value: 'S/ 2,139.24', strong: 1 }
      ],
      actions: ['Validar', 'Dar de alta']
    },

    baja_deuda: {
      mod: 'Rentas · Registro', title: 'Baja de deuda',
      endpoint: 'POST /api/v1/rentas/deuda/bajas',
      desc: 'Extingue deuda de la cuenta corriente por prescripción, resolución que la deja sin efecto, error material o compensación. Requiere resolución y queda en la bitácora de auditoría.',
      filters: [T('Cod. Contribuyente', { v: '00000006550' }), T('Año', { t: 'sel', v: 'Todos', opts: ['Todos'].concat(yrs) }), T('Tributo', { t: 'sel', v: 'Todos', opts: ['Todos', 'IMPUESTO PREDIAL', 'ARBITRIOS', 'PATRIMONIO VEHICULAR', 'MULTAS'] })],
      table: {
        title: 'Deuda seleccionable para baja', count: '4 registros · 2 marcados',
        cols: ['', 'Año', 'Unidad', 'Cuota', 'Tributo', 'Insoluto S/', 'Interés S/', 'Total S/'], num: [5, 6, 7],
        rows: [
          ['✓', '2016', '02-014-D-14-01', '1-4', 'IMPUESTO PREDIAL', '482.40', '388.12', '870.52'],
          ['✓', '2016', '02-014-D-14-01', '1-12', 'ARBITRIOS', '412.00', '331.44', '743.44'],
          ['', '2024', '02-014-D-14-01', '1-4', 'IMPUESTO PREDIAL', '1,842.60', '212.44', '2,055.04'],
          ['', '2025', 'T2G-418', '1', 'PATRIMONIO VEHICULAR', '614.00', '182.44', '796.44']
        ]
      },
      sections: [
        S('Sustento de la baja', [
          T('Causal', { t: 'sel', v: 'PRESCRIPCIÓN DECLARADA', opts: ['PRESCRIPCIÓN DECLARADA', 'RESOLUCIÓN QUE DEJA SIN EFECTO', 'ERROR MATERIAL', 'COMPENSACIÓN', 'DEUDA DE COBRANZA DUDOSA', 'CONDONACIÓN POR ORDENANZA'] }),
          T('Nº de resolución', { v: 'RGAT-0244-2026-MPS' }),
          T('Fecha de resolución', { t: 'date', v: '2026-08-04' }),
          T('Autorizado por', { t: 'ro', v: 'Gerencia de Administración Tributaria' }),
          T('Monto total a extinguir (S/)', { t: 'ro', v: '1,613.96' }),
          T('Motivo', { t: 'area', wide: 1, v: 'Prescripción declarada de los ejercicios 2014 a 2016 conforme al artículo 43º del Código Tributario.' })
        ])
      ],
      actions: ['Previsualizar', 'Dar de baja']
    },

    beneficios: {
      mod: 'Rentas · Registro', title: 'Beneficios y exoneraciones',
      endpoint: 'GET /api/v1/rentas/beneficios',
      desc: 'Deducciones, inafectaciones y amnistías. La deducción de 50 UIT para pensionistas y adultos mayores exige predio único destinado a vivienda.',
      filters: [T('Contribuyente', { v: '' }), T('Tipo', { t: 'sel', v: 'Todos', opts: ['Todos', 'PENSIONISTA', 'ADULTO MAYOR', 'DISCAPACIDAD', 'INAFECTACIÓN', 'AMNISTÍA'] }), T('Estado', { t: 'sel', v: 'Todos', opts: ['Todos', 'VIGENTE', 'EN TRÁMITE', 'DENEGADO', 'VENCIDO'] })],
      table: {
        title: 'Beneficios registrados', count: '4 de 1,842',
        cols: ['Expediente', 'Contribuyente', 'Tipo', 'Resolución', 'Vigencia', 'Deducción', 'Estado'],
        rows: [
          ['2026-0281', 'CASTILLO PASCUALA, MARÍA E.', 'PENSIONISTA', 'RES-0412-2026-MPS', '2026 — indefinida', '50 UIT', ['Vigente', 'ok']],
          ['2026-0344', 'QUIROGA RAMOS, ELEODORO', 'ADULTO MAYOR', 'RES-0448-2026-MPS', '2026 — indefinida', '50 UIT', ['Vigente', 'ok']],
          ['2026-0388', 'NOBLECILLA ARISMENDIZ SAC', 'INAFECTACIÓN', '—', '—', '—', ['En trámite', 'warn']],
          ['2025-1102', 'DÍAZ MADRID, JULIO CÉSAR', 'AMNISTÍA 2025', 'ORD-018-2025-MPS', '2025', '100 % interés', ['Vencido', 'bad']]
        ]
      },
      sections: [
        S('Solicitud de beneficio', [
          T('Tipo de beneficio', { t: 'sel', v: 'PENSIONISTA — DEDUCCIÓN 50 UIT', opts: ['PENSIONISTA — DEDUCCIÓN 50 UIT', 'ADULTO MAYOR NO PENSIONISTA', 'PERSONA CON DISCAPACIDAD', 'INAFECTACIÓN', 'AMNISTÍA TRIBUTARIA'] }),
          T('Cod. Contribuyente', { v: '00000003541' }),
          T('Código predial', { v: '02-014-D-14-01' }),
          T('Nº de expediente', { v: '2026-0281' }),
          T('Fecha de solicitud', { t: 'date', v: '2026-03-04' }),
          T('Nº de resolución', { v: 'RES-0412-2026-MPS' }),
          T('Fecha de resolución', { t: 'date', v: '2026-03-22' }),
          T('Vigencia desde', { t: 'date', v: '2026-01-01' }),
          T('Predio único verificado', { t: 'chk', on: true, ph: 'Cumple el requisito de predio único' }),
          T('Destinado a vivienda', { t: 'chk', on: true, ph: 'Uso parcial comercial permitido' }),
          T('Sustento', { t: 'area', wide: 1, v: 'Resolución de pensión ONP y declaración jurada de predio único.' })
        ])
      ],
      actions: ['Registrar', 'Denegar', 'Aprobar']
    }

  });
})();
