import type { ClaveDeHoja } from '../arbol.ts';
import { EN_LA_RUTA, hayMasDe, paginasDe } from '../tablas.ts';
import type { DefinicionDePantalla as Pantalla } from '@kamayuk/ui';

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
          { etiqueta: 'Usuarios registrados', tipo: 'r' },
          { etiqueta: 'Activos', tipo: 'r' },
          { etiqueta: 'Con permiso total', tipo: 'r' },
          { etiqueta: 'Cuentas inactivas con permisos', tipo: 'r' },
          { etiqueta: 'Contraseñas caducadas', tipo: 'r' },
          { etiqueta: 'Última restauración verificada', tipo: 'r' },
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
          { etiqueta: 'Grupos a los que pertenece', tipo: 'r' },
          { etiqueta: 'Permisos propios', tipo: 'r' },
          { etiqueta: 'Heredados', tipo: 'r' },
          { etiqueta: 'Con nivel total', tipo: 'r' },
          { etiqueta: 'Antigüedad de la contraseña', tipo: 'r' },
        ],
        tabla: {
          titulo: 'Permisos efectivos',
          columnas: [
            { rotulo: 'Acceso', alineadoDerecha: false },
            { rotulo: 'Módulo', alineadoDerecha: false },
            { rotulo: 'Niveles', alineadoDerecha: false },
            { rotulo: 'Origen', alineadoDerecha: false },
            { rotulo: 'Sensible', alineadoDerecha: false },
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
          // Con `clave`, las celdas pueden decir que NO hay dato y por que —«Riesgo» no la publica
          // nadie— y la tabla puede llevar su total publicado (`kamayuk-lib`#87, #187).
          clave: 'movimientos',
          sinDato: {
            texto: '—',
            nota: 'Ninguna operacion del contrato publica este dato.',
          },
          // La bitacora son 84 182 movimientos. Sin mandos, la pantalla ensena veinte y no dice
          // que son una ventana (#187).
          paginacion: {
            en: 'servidor',
            enLaRuta: EN_LA_RUTA.pagina,
            tamano: 20,
            tamanos: [20, 50, 100],
            tamanoEnLaRuta: EN_LA_RUTA.tamano,
            hayMas: hayMasDe('movimientos'),
            paginas: paginasDe('movimientos'),
          },
          // La lista blanca es la de `SesionRepositoryJdbc.ORDEN_AUDITORIA`; el primero es el
          // `aPaginacion("fecha")` de `SesionController`. «Riesgo» y «Detalle» no estan, y no
          // pueden estar: el riesgo no existe como dato y el detalle son tres columnas juntas.
          orden: {
            campos: [
              { valor: 'fecha', rotulo: 'Fecha y hora' },
              { valor: 'usuarioId', rotulo: 'Usuario' },
              { valor: 'operacion', rotulo: 'Acto' },
              { valor: 'tabla', rotulo: 'Sobre qué' },
            ],
            enLaRuta: EN_LA_RUTA.ordenarPor,
            sentidoEnLaRuta: EN_LA_RUTA.direccion,
            ascendente: 'ASCENDENTE',
            descendente: 'DESCENDENTE',
          },
          titulo: 'Movimientos',
          columnas: [
            { rotulo: 'Fecha y hora', alineadoDerecha: false },
            { rotulo: 'Usuario', alineadoDerecha: false },
            { rotulo: 'Acto', alineadoDerecha: false },
            { rotulo: 'Detalle', alineadoDerecha: false },
            { rotulo: 'Riesgo', alineadoDerecha: false },
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
          columnas: [
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Tipo', alineadoDerecha: false },
            { rotulo: 'Tamaño', alineadoDerecha: false },
            { rotulo: 'Destino', alineadoDerecha: false },
            { rotulo: 'Restauración probada', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
          nota: 'Una copia que nadie ha probado a restaurar no protege nada: la columna que importa es la última restauración verificada.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
