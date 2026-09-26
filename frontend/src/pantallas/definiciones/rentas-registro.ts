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
          // Con `clave` desde #389: sus filas llegan por `DatosDeLaPantalla.tablas`, que es el unico
          // camino cuyas celdas pueden decir que no hay dato. La etapa que no mueve dinero llega con
          // `monto: ""`, y por la via de siempre esa celda salia EN BLANCO. Con `sinDato` dice la
          // raya del artboard y anuncia el motivo.
          clave: 'etapas-de-la-corrida',
          sinDato: {
            texto: '—',
            nota: 'Esta etapa no mueve dinero: no emite nada, y eso no es un monto cero.',
          },
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
      // **El bloque de la MEMORIA, y lo decide el propio artboard** (#245, y su gemelo en
      // `diseno/RentasV8.dc.html`).
      //
      // Hasta aqui `territorio` tenia **un solo campo de solo lectura en sus tres bloques**
      // —«Monto deducido»— y ni siquiera ese lo publica nadie, de modo que #237 conecto la hoja a
      // `GET /rentas/predial/determinaciones` —veinte campos— y no pinto ni una celda. El issue
      // preguntaba si le falta a la hoja un bloque de resultado o si es correcto que una pantalla
      // de EJECUTAR no ensene lo determinado. Lo contesta el artboard, dos veces:
      //
      //   · **Su instruccion ya lo prometia**: «fije el sujeto y el ejercicio, y **compruebe la
      //     memoria del calculo** antes de asentar la determinacion». No habia donde comprobarla.
      //   · **Y ya dibujaba lo determinado**, en el cronograma de abajo. Las cifras cuadran al
      //     centimo y no por casualidad: 151,36 + 3 x 146,86 = 591,94 = 587,44 + 4,50 —el derecho
      //     de emision de la hoja `valores`—, y 587,44 es la escala de esa misma hoja sobre una
      //     base de 151 406,75: 80 250,00 x 0,2 % = 160,50 mas 71 156,75 x 0,6 % = 426,94. La
      //     base es el autovaluo de los dos predios de `predios` con su % de propiedad. O sea que
      //     **la memoria ya estaba en el artboard, repartida en tres hojas**, y esta enseñaba su
      //     resultado sin enseñar de donde salia. «Una pantalla de ejecutar no muestra lo
      //     determinado» lo refuta el cronograma, que es lo determinado.
      //
      // Los diez campos son los diez que la operacion publica y que esta hoja puede afirmar; los
      // otros diez o son el mando «Contribuyente» (`sujeto`, `codContribuyente`), o son de otra
      // hoja (`predios[]` es de `predios`), o son la nota de la memoria (`reglasAplicadas[]`), o
      // son identificadores (`id`, `conjuntoId`) y el estado y el origen de la fila.
      //
      // **Y ninguno de los nueve importes necesita una fecha (regla 9)**: no son
      // `deudaActualizadaA(fecha)` —no corren intereses aqui—, son lo que quedo asentado para un
      // EJERCICIO bajo un CONJUNTO SELLADO, y los dos estan en la pantalla: el ejercicio en el
      // bloque 0 y el conjunto en el ultimo campo de este. Ese campo no es decoracion: es lo que
      // hace reproducible la memoria (ARQ-09 §3), porque `uit`, `tramos`, `minimoImponible` y
      // `derechoDeEmision` salen del conjunto que ESA determinacion fijo y no del vigente hoy.
      {
        titulo: 'Memoria del cálculo',
        nota: 'De qué valúo salió el impuesto, con qué tramos y bajo qué conjunto sellado.',
        campos: [
          { etiqueta: 'Valúo total', tipo: 'r' },
          { etiqueta: 'Valúo exonerado', tipo: 'r' },
          { etiqueta: 'Valúo afecto', tipo: 'r' },
          { etiqueta: 'Base imponible', tipo: 'r' },
          { etiqueta: 'UIT del ejercicio', tipo: 'r' },
          { etiqueta: 'Mínimo imponible', tipo: 'r' },
          { etiqueta: 'Impuesto insoluto', tipo: 'r' },
          { etiqueta: 'Derecho de emisión', tipo: 'r' },
          { etiqueta: 'Total a pagar', tipo: 'r' },
          { etiqueta: 'Conjunto normativo', tipo: 'r' },
        ],
        tabla: {
          // Con `clave`, las filas llegan por `DatosDeLaPantalla.tablas` (`kamayuk-lib`#87, #180).
          // Aqui hace falta de verdad: `limiteSuperior` es **nulo en el ultimo tramo**, y una
          // cadena no puede decir por que no hay dato. Con `sinDato`, esa celda dice «Sin tope»
          // —traducido— en vez de una raya muda que se leeria como un hueco del backend.
          clave: 'tramos-del-articulo-13',
          sinDato: {
            texto: 'Sin tope',
            nota: 'El último tramo del artículo 13 no tiene límite superior: se aplica a todo lo que exceda del anterior.',
          },
          titulo: 'Tramos del artículo 13',
          columnas: [
            { rotulo: 'Tramo', alineadoDerecha: false },
            { rotulo: 'Límite superior S/', alineadoDerecha: true },
            { rotulo: 'Alícuota', alineadoDerecha: true },
            { rotulo: 'Porción gravada S/', alineadoDerecha: true },
            { rotulo: 'Aporte S/', alineadoDerecha: true },
          ],
          nota: 'Los aportes corren sin redondear (ADR-0018): su suma puede diferir en un céntimo del impuesto insoluto, que es la cifra que manda.',
        },
      },
      {
        titulo: 'Cuotas del ejercicio',
        nota: '',
        campos: [],
        tabla: {
          // Con `clave`, las filas llegan por `DatosDeLaPantalla.tablas` (#252). Aqui hace falta
          // por lo mismo que en los tramos y por un motivo distinto: la cuarta columna
          // —«Situación»— **no la publica ninguna operación servida**, y una cadena no puede decir
          // por qué no hay dato. Con `sinDato`, esa celda dice la raya del artboard **y anuncia el
          // motivo**; con `''` sería un blanco, y con `'—'` escrito como celda sería una raya muda
          // indistinguible de un dato que se perdió por el camino.
          clave: 'cronograma',
          sinDato: {
            texto: '—',
            nota: 'La situación de una cuota es un hecho de cuenta corriente —si se pagó, cuándo y cuánto— y no del cálculo: ninguna operación servida la publica.',
          },
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
