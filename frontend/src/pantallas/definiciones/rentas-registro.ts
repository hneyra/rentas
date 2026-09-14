import type { ClaveDeHoja } from '../arbol.ts';
import type { DefinicionDePantalla as Pantalla } from '@kamayuk/ui';

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
          { etiqueta: 'Última corrida', tipo: 'r' },
          { etiqueta: 'Cuentas emitidas', tipo: 'r' },
          { etiqueta: 'Observados', tipo: 'r' },
          { etiqueta: 'Monto determinado', tipo: 'r' },
          { etiqueta: 'Derecho de emisión', tipo: 'r' },
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
          { etiqueta: 'Código', tipo: 'r' },
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
          { etiqueta: 'Departamento', tipo: 'r' },
          { etiqueta: 'Provincia', tipo: 'r' },
          { etiqueta: 'Distrito', tipo: 'r' },
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
          accion: 'Añadir predio',
          columnas: [
            { rotulo: 'Código predial', alineadoDerecha: false },
            { rotulo: 'Ubicación', alineadoDerecha: false },
            { rotulo: 'Uso', alineadoDerecha: false },
            { rotulo: 'Terreno m²', alineadoDerecha: true },
            { rotulo: '% prop.', alineadoDerecha: true },
            { rotulo: 'Autovalúo S/', alineadoDerecha: true },
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
          { etiqueta: 'Monto deducido', tipo: 'r' },
        ],
      },
      {
        titulo: 'Cuotas del ejercicio',
        nota: '',
        campos: [],
        tabla: {
          titulo: 'Cronograma',
          columnas: [
            { rotulo: 'Cuota', alineadoDerecha: false },
            { rotulo: 'Vencimiento', alineadoDerecha: false },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Situación', alineadoDerecha: false },
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
          { etiqueta: 'UIT', tipo: 'r' },
          { etiqueta: 'Interés moratorio mensual', tipo: 'r' },
          { etiqueta: 'Interés de fraccionamiento', tipo: 'r' },
          { etiqueta: 'Derecho de emisión', tipo: 'r' },
          { etiqueta: 'IPM para alcabala', tipo: 'r' },
        ],
        tabla: {
          titulo: 'UIT y escala progresiva',
          columnas: [
            { rotulo: 'Concepto', alineadoDerecha: false },
            { rotulo: 'Base', alineadoDerecha: false },
            { rotulo: 'Tasa o valor', alineadoDerecha: false },
            { rotulo: 'Equivalente S/', alineadoDerecha: true },
          ],
          nota: 'La escala es acumulativa: cada tramo se aplica sólo a la porción del autovalúo que le corresponde.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
