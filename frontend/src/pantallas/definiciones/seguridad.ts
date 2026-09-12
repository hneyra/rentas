import type { ClaveDeHoja } from '../arbol.ts';
import type { Pantalla } from '../tipos.ts';

/**
 * Las cuatro pantallas de **Seguridad** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'seg-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const SEGURIDAD = {
  'seg-panel': {
    instruccion: 'revise cada hallazgo: un permiso total sobre un módulo tributario es la llave de la caja.',
    bloques: [
      {
        titulo: 'Lo que hay que revisar',
        nota: 'Hallazgos de seguridad, cada uno con su causa.',
        campos: [
          { etiqueta: 'Usuarios registrados', tipo: 'r', valor: '7' },
          { etiqueta: 'Activos', tipo: 'r', valor: '6' },
          { etiqueta: 'Con permiso total', tipo: 'r', valor: '3' },
          { etiqueta: 'Cuentas inactivas con permisos', tipo: 'r', valor: '1' },
          { etiqueta: 'Contraseñas caducadas', tipo: 'r', valor: '2' },
          { etiqueta: 'Última restauración verificada', tipo: 'r', valor: 'Hace 94 días' },
        ],
      },
    ],
  },
  'seg-acc': {
    instruccion: 'un permiso heredado del grupo no se quita aquí: se quita en el grupo.',
    bloques: [
      {
        titulo: 'Permisos del usuario o grupo',
        nota: 'Un permiso heredado del grupo no se quita aquí: se quita en el grupo.',
        campos: [
          { etiqueta: 'Usuario o grupo', tipo: '1' },
          { etiqueta: 'Tipo', tipo: 's', opciones: ['Usuario', 'Grupo'] },
          {
            etiqueta: 'Estado de la cuenta',
            tipo: 's',
            opciones: ['Activa', 'Inactiva', 'Bloqueada'],
          },
          { etiqueta: 'Grupos a los que pertenece', tipo: 'r', valor: 'CAJA, RENTAS' },
          { etiqueta: 'Permisos propios', tipo: 'r', valor: '2' },
          { etiqueta: 'Heredados', tipo: 'r', valor: '6' },
          { etiqueta: 'Con nivel total', tipo: 'r', valor: '0' },
          { etiqueta: 'Antigüedad de la contraseña', tipo: 'r', valor: '44 días' },
        ],
        tabla: {
          titulo: 'Permisos efectivos',
          conteo: '9 accesos',
          columnas: [
            { rotulo: 'Acceso', alineadoDerecha: false },
            { rotulo: 'Módulo', alineadoDerecha: false },
            { rotulo: 'Niveles', alineadoDerecha: false },
            { rotulo: 'Origen', alineadoDerecha: false },
            { rotulo: 'Sensible', alineadoDerecha: false },
          ],
          filas: [
            [
              'Caja tributaria',
              'Tesorería',
              'Ejecuta · Consulta · Ingresa · Imprime',
              'Heredado de CAJA',
              'Mueve dinero',
            ],
            ['Anulación de recibo', 'Tesorería', 'Ejecuta · Consulta', 'Propio', 'Mueve dinero'],
            ['Baja de deuda', 'Rentas', 'Consulta', 'Heredado de RENTAS', 'Mueve dinero'],
            ['Ficha urbana individual', 'Catastro', 'Consulta', 'Heredado de CATASTRO', 'No'],
          ],
          nota: 'Los siete niveles son Total, Ejecuta, Consulta, Ingresa, Modifica, Anula e Imprime. Total implica los otros seis.',
        },
      },
    ],
  },
  'seg-aud': {
    instruccion: 'filtre por usuario, módulo o riesgo. La bitácora no se edita ni se borra.',
    bloques: [
      {
        titulo: 'Bitácora de auditoría',
        nota: 'No se edita ni se borra: es lo que se presenta cuando alguien pregunta por una baja.',
        campos: [
          {
            etiqueta: 'Usuario',
            tipo: 's',
            opciones: ['Todos', 'jquispe', 'jcardenas', 'mrios', 'lpena', 'vreto'],
          },
          {
            etiqueta: 'Módulo',
            tipo: 's',
            opciones: ['Todos', 'Rentas', 'Tesorería', 'Catastro', 'Coactiva', 'Seguridad'],
          },
          { etiqueta: 'Desde', tipo: 'd' },
          { etiqueta: 'Hasta', tipo: 'd' },
          { etiqueta: 'Riesgo', tipo: 's', opciones: ['Todos', 'Alto', 'Medio', 'Bajo'] },
          { etiqueta: 'Buscar en el detalle', tipo: '1' },
        ],
        tabla: {
          titulo: 'Movimientos',
          conteo: '4 de 84,182',
          columnas: [
            { rotulo: 'Fecha y hora', alineadoDerecha: false },
            { rotulo: 'Usuario', alineadoDerecha: false },
            { rotulo: 'Acto', alineadoDerecha: false },
            { rotulo: 'Detalle', alineadoDerecha: false },
            { rotulo: 'Riesgo', alineadoDerecha: false },
          ],
          filas: [
            [
              '13/08/2026 09:41',
              'jcardenas',
              'Anulación de recibo',
              'Recibo 0003-0041184 · S/ 1,245.00',
              'Alto',
            ],
            [
              '13/08/2026 08:12',
              'jquispe',
              'Cambio de permisos',
              'aayca: Anulación → Total',
              'Alto',
            ],
            [
              '12/08/2026 17:04',
              'mrios',
              'Baja de deuda',
              '2 cuotas · S/ 1,613.96 · prescripción',
              'Alto',
            ],
            [
              '12/08/2026 11:20',
              'vreto',
              'Modificación de ficha',
              '01-1042-0004 · área 136 → 198',
              'Medio',
            ],
          ],
          columnaDeInsignia: 4,
        },
      },
    ],
  },
  'seg-sis': {
    instruccion: 'el ejercicio es global a la sesión: decide sobre qué año escriben todos los módulos.',
    bloques: [
      {
        titulo: 'Parámetros del sistema',
        nota: 'El ejercicio es global a la sesión: decide sobre qué año escriben todos los módulos.',
        campos: [
          { etiqueta: 'Ejercicio de trabajo', tipo: 's', opciones: ['2026', '2025', '2024'] },
          { etiqueta: 'UIT del ejercicio', tipo: '' },
          { etiqueta: 'Interés moratorio mensual', tipo: '' },
          { etiqueta: 'Interés de fraccionamiento', tipo: '' },
          { etiqueta: 'Tasa diaria de custodia', tipo: '' },
          { etiqueta: 'Derecho de emisión', tipo: '' },
          { etiqueta: 'Caducidad de contraseña (días)', tipo: '' },
          { etiqueta: 'Intentos antes de bloquear', tipo: '' },
          {
            etiqueta: 'Motivo del cambio',
            tipo: 'a1',
            ayuda: 'Queda en la auditoría con su usuario y la hora',
          },
        ],
        tabla: {
          titulo: 'Copias de seguridad',
          conteo: '4 copias',
          columnas: [
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Tipo', alineadoDerecha: false },
            { rotulo: 'Tamaño', alineadoDerecha: false },
            { rotulo: 'Destino', alineadoDerecha: false },
            { rotulo: 'Restauración probada', alineadoDerecha: false },
          ],
          filas: [
            ['13/08/2026 02:00', 'Completa', '18.4 GB', 'Nube — Lima', 'Pendiente'],
            ['12/08/2026 02:00', 'Completa', '18.4 GB', 'Nube — Lima', 'Pendiente'],
            ['11/08/2026 02:00', 'Completa', '18.3 GB', 'Nube — Lima', 'Pendiente'],
            ['11/05/2026 02:00', 'Completa', '17.1 GB', 'Nube — Lima', 'Conforme'],
          ],
          columnaDeInsignia: 4,
          nota: 'Una copia que nadie ha probado a restaurar no protege nada: la columna que importa es la última restauración verificada.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
