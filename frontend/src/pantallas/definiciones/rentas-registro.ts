import type { ClaveDeHoja } from '../arbol.ts';
import type { Pantalla } from '../tipos.ts';

/**
 * Las cuatro pantallas de **Rentas · Registro** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const RENTAS_REGISTRO = {
  'panel': {
    instruccion: 'revise los observados antes de emitir: sin corregir la inconsistencia no se les puede cobrar el ejercicio.',
    bloques: [
      {
        titulo: 'Estado de la emisión',
        nota: 'La última corrida del padrón y lo que dejó fuera.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          { etiqueta: 'Última corrida', tipo: 'r', valor: '28/01/2026 02:14' },
          { etiqueta: 'Cuentas emitidas', tipo: 'r', valor: '61,350' },
          { etiqueta: 'Observados', tipo: 'r', valor: '534' },
          { etiqueta: 'Monto determinado', tipo: 'r', valor: 'S/ 9,418,204.60' },
          { etiqueta: 'Derecho de emisión', tipo: 'r', valor: 'S/ 4.50' },
        ],
        tabla: {
          titulo: 'Etapas de la corrida',
          columnas: [
            { rotulo: 'Etapa', alineadoDerecha: false },
            { rotulo: 'Registros', alineadoDerecha: true },
            { rotulo: 'Monto S/', alineadoDerecha: true },
            { rotulo: 'Observados', alineadoDerecha: true },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          filas: [
            ['Lectura del padrón', '62,418', '—', '0', 'Conforme'],
            ['Valuación de predios', '78,204', '1,842,116,420.00', '412', 'Conforme'],
            ['Determinación del impuesto', '61,884', '9,418,204.60', '534', 'Conforme'],
            ['Determinación de arbitrios', '61,884', '5,884,110.20', '188', 'Conforme'],
            ['Generación de cuponeras', '61,350', '—', '534', 'Observado'],
          ],
          columnaDeInsignia: 4,
          nota: 'Los observados quedan sin emisión hasta que se corrija la inconsistencia: predio sin arancel, ficha no conciliada o titularidad incompleta.',
        },
      },
    ],
  },
  'predios': {
    instruccion: 'complete la identificación y el domicilio. El documento tiene que ser único en el padrón.',
    bloques: [
      {
        titulo: 'Identificación del contribuyente',
        nota: 'El código lo asigna el sistema; lo que tiene que ser único es el documento.',
        campos: [
          { etiqueta: 'Código', tipo: 'r', valor: '00000025673' },
          {
            etiqueta: 'Tipo de persona',
            tipo: 's',
            opciones: ['Natural', 'Jurídica', 'Sucesión indivisa', 'Sociedad conyugal'],
          },
          {
            etiqueta: 'Tipo de documento',
            tipo: 's',
            opciones: ['DNI', 'RUC', 'Carnet de extranjería'],
          },
          { etiqueta: 'Número de documento', tipo: '' },
          { etiqueta: 'Apellido paterno', tipo: '' },
          { etiqueta: 'Apellido materno', tipo: '' },
          { etiqueta: 'Nombres', tipo: '' },
          { etiqueta: 'Razón social', tipo: '1', ayuda: 'Sólo si es persona jurídica' },
          { etiqueta: 'Fecha de nacimiento', tipo: 'd' },
          { etiqueta: 'Sexo', tipo: 's', opciones: ['Masculino', 'Femenino'] },
          {
            etiqueta: 'Estado civil',
            tipo: 's',
            opciones: ['Soltero(a)', 'Casado(a)', 'Viudo(a)', 'Divorciado(a)', 'Conviviente'],
          },
          {
            etiqueta: 'Calificación',
            tipo: 's',
            opciones: [
              '001 — Principal contribuyente',
              '002 — Mediano contribuyente',
              '003 — Pequeño contribuyente',
            ],
          },
          {
            etiqueta: 'Estado',
            tipo: 's',
            opciones: ['Activo', 'Inactivo', 'Baja', 'Fallecido', 'No habido'],
          },
        ],
      },
      {
        titulo: 'Domicilio fiscal',
        nota: 'A dónde se notifica. La vía sale del catálogo vial de Catastro.',
        campos: [
          {
            etiqueta: 'Tipo de vía',
            tipo: 's',
            opciones: ['AV — Avenida', 'CA — Calle', 'JR — Jirón', 'PS — Pasaje', 'CR — Carretera'],
          },
          { etiqueta: 'Nombre de la vía', tipo: '1' },
          { etiqueta: 'Número', tipo: '' },
          { etiqueta: 'Número adicional', tipo: '' },
          { etiqueta: 'Habilitación urbana', tipo: '1' },
          { etiqueta: 'Departamento', tipo: 'r', valor: 'Piura' },
          { etiqueta: 'Provincia', tipo: 'r', valor: 'Sullana' },
          { etiqueta: 'Distrito', tipo: 'r', valor: 'Sullana' },
          { etiqueta: 'Manzana', tipo: '' },
          { etiqueta: 'Lote', tipo: '' },
          { etiqueta: 'Teléfonos', tipo: '' },
          { etiqueta: 'Correo electrónico', tipo: '' },
          {
            etiqueta: 'Notificación electrónica',
            tipo: 'c',
            casilla: 'Autoriza notificar al correo declarado',
          },
        ],
      },
      {
        titulo: 'Unidades afectas',
        nota: 'Las unidades de las que sale el impuesto.',
        campos: [],
        tabla: {
          titulo: 'Predios del contribuyente',
          conteo: '2 predios · autovalúo S/ 170,616.75',
          accion: 'Añadir predio',
          columnas: [
            { rotulo: 'Código predial', alineadoDerecha: false },
            { rotulo: 'Ubicación', alineadoDerecha: false },
            { rotulo: 'Uso', alineadoDerecha: false },
            { rotulo: 'Terreno m²', alineadoDerecha: true },
            { rotulo: '% prop.', alineadoDerecha: true },
            { rotulo: 'Autovalúo S/', alineadoDerecha: true },
          ],
          filas: [
            [
              '02-014-D-14-01',
              'Calle Santa Rosa 116',
              'Casa habitación',
              '210.00',
              '100.00',
              '132,196.75',
            ],
            [
              '04-021-B-07-00',
              'Mz. B Lt. 7 — Bellavista',
              'Terreno sin construir',
              '184.00',
              '50.00',
              '38,420.00',
            ],
          ],
          nota: 'El autovalúo del conjunto es la base imponible del predial: la escala se aplica a la suma, no a cada predio.',
        },
      },
    ],
  },
  'territorio': {
    instruccion: 'fije el sujeto y el ejercicio, y compruebe la memoria del cálculo antes de asentar la determinación.',
    bloques: [
      {
        titulo: 'Sujeto y ejercicio',
        nota: 'Sobre quién y sobre qué año se determina.',
        campos: [
          { etiqueta: 'Contribuyente', tipo: '1' },
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          { etiqueta: 'Declaración jurada Nº', tipo: '' },
          {
            etiqueta: 'Tipo de declaración',
            tipo: 's',
            opciones: ['Inscripción', 'Descargo', 'Rectificatoria', 'Anual mecanizada'],
          },
          { etiqueta: 'Fecha de declaración', tipo: 'd' },
          {
            etiqueta: 'Modalidad de pago',
            tipo: 's',
            opciones: ['Al contado', 'Fraccionado en 4 cuotas'],
          },
        ],
      },
      {
        titulo: 'Beneficios aplicados',
        nota: 'Una deducción reduce la base imponible del ejercicio en curso.',
        campos: [
          {
            etiqueta: 'Deducción',
            tipo: 's',
            opciones: ['No aplica', 'Pensionista — 50 UIT', 'Adulto mayor no pensionista — 50 UIT'],
          },
          { etiqueta: 'Nº de resolución', tipo: '', ayuda: 'Se exige si hay deducción' },
          {
            etiqueta: 'Inafectación',
            tipo: 's',
            opciones: [
              'Ninguna',
              'Gobierno central',
              'Entidad religiosa',
              'Cuerpo de bomberos',
              'Beneficencia',
            ],
          },
          { etiqueta: 'Monto deducido', tipo: 'r', valor: 'S/ 0.00' },
        ],
      },
      {
        titulo: 'Cuotas del ejercicio',
        nota: '',
        campos: [],
        tabla: {
          titulo: 'Cronograma',
          conteo: '4 cuotas trimestrales',
          columnas: [
            { rotulo: 'Cuota', alineadoDerecha: false },
            { rotulo: 'Vencimiento', alineadoDerecha: false },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          filas: [
            ['1 de 4', '28/02/2026', '151.36', 'Cancelada'],
            ['2 de 4', '31/05/2026', '146.86', 'Cancelada'],
            ['3 de 4', '31/08/2026', '146.86', 'Vencida'],
            ['4 de 4', '30/11/2026', '146.86', 'Por vencer'],
          ],
          columnaDeInsignia: 3,
          nota: 'El derecho de emisión se cobra entero en la primera cuponera, no prorrateado: por eso la cuota 1 es mayor.',
        },
      },
    ],
  },
  'valores': {
    instruccion: 'consulte los parámetros del ejercicio. Se aprueban por ordenanza: aquí no se cambian.',
    bloques: [
      {
        titulo: 'Parámetros del ejercicio',
        nota: 'Se aprueban una vez al año; aquí sólo se consultan.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          { etiqueta: 'UIT', tipo: 'r', valor: 'S/ 5,350.00' },
          { etiqueta: 'Interés moratorio mensual', tipo: 'r', valor: '0.90 %' },
          { etiqueta: 'Interés de fraccionamiento', tipo: 'r', valor: '0.80 %' },
          { etiqueta: 'Derecho de emisión', tipo: 'r', valor: 'S/ 4.50' },
          { etiqueta: 'IPM para alcabala', tipo: 'r', valor: '1.0206' },
        ],
        tabla: {
          titulo: 'UIT y escala progresiva',
          conteo: 'Ejercicio 2026',
          columnas: [
            { rotulo: 'Concepto', alineadoDerecha: false },
            { rotulo: 'Base', alineadoDerecha: false },
            { rotulo: 'Tasa o valor', alineadoDerecha: false },
            { rotulo: 'Equivalente S/', alineadoDerecha: true },
          ],
          filas: [
            ['UIT 2026', 'Aprobada por el MEF', 'S/ 5,350.00', '5,350.00'],
            ['Tramo 1 del predial', 'Hasta 15 UIT', '0.2 %', '80,250.00'],
            ['Tramo 2 del predial', 'De 15 a 60 UIT', '0.6 %', '321,000.00'],
            ['Tramo 3 del predial', 'Más de 60 UIT', '1.0 %', 'sin tope'],
            ['Mínimo imponible predial', '0.6 % de la UIT', '—', '32.10'],
            ['Deducción de pensionista', '50 UIT', '—', '267,500.00'],
          ],
          nota: 'La escala es acumulativa: cada tramo se aplica sólo a la porción del autovalúo que le corresponde.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
