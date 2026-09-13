import type { ClaveDeHoja } from '../arbol.ts';
import type { DefinicionDePantalla as Pantalla } from '@kamayuk/ui';

/**
 * Las cuatro pantallas de **Infracciones administrativas** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'inf-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 */
export const INFRACCIONES_ADMINISTRATIVAS = {
  'inf-panel': {
    instruccion: 'atienda las que vencen esta semana: pasado el plazo, la sanción ya no es exigible.',
    bloques: [
      {
        titulo: 'Expedientes administrativos',
        nota: 'Por etapa, con los que vencen esta semana.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          { etiqueta: 'Actas levantadas', tipo: 'r' },
          { etiqueta: 'Resoluciones dictadas', tipo: 'r' },
          { etiqueta: 'Notificadas', tipo: 'r' },
          { etiqueta: 'Vencen esta semana', tipo: 'r' },
          { etiqueta: 'En coactiva', tipo: 'r' },
        ],
      },
    ],
  },
  'inf-exp': {
    instruccion: 'levante el acta, dicte la resolución y notifíquela. El orden es legal, no una preferencia.',
    bloques: [
      {
        titulo: 'Acta de constatación',
        nota: 'El acta constata el hecho; la resolución lo sanciona; la notificación lo hace exigible.',
        campos: [
          { etiqueta: 'Nº de expediente', tipo: '' },
          { etiqueta: 'Nº de acta', tipo: '' },
          { etiqueta: 'Fecha de constatación', tipo: 'd' },
          {
            etiqueta: 'Código CUIS',
            tipo: 's',
            opciones: [
              'A-042 — Anuncio sin autorización',
              'B-118 — Local sin licencia',
              'C-101 — Funcionar con giro distinto',
              'D-204 — Residuos en vía pública',
            ],
          },
          { etiqueta: 'Infractor', tipo: '1' },
          { etiqueta: 'Documento', tipo: '' },
          { etiqueta: 'Dirección del hecho', tipo: '1' },
          {
            etiqueta: 'Inspector',
            tipo: 's',
            opciones: ['Peña Sandoval, Luis', 'Vílchez Rojas, Andrés'],
          },
          {
            etiqueta: 'Reincidencia',
            tipo: 's',
            opciones: ['Primera vez', 'Segunda vez', 'Tercera o más'],
          },
          {
            etiqueta: 'Medida complementaria',
            tipo: 's',
            opciones: ['Ninguna', 'Clausura temporal', 'Clausura definitiva', 'Retiro', 'Decomiso'],
          },
          { etiqueta: 'Descripción del hecho', tipo: 'a1' },
        ],
        tabla: {
          titulo: 'Actos del expediente',
          columnas: [
            { rotulo: 'Nº', alineadoDerecha: false },
            { rotulo: 'Acto', alineadoDerecha: false },
            { rotulo: 'Fecha', alineadoDerecha: false },
            { rotulo: 'Documento', alineadoDerecha: false },
            { rotulo: 'Estado', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
          nota: 'El orden es legal, no una preferencia: sin acta no hay resolución, y sin notificación la sanción no es exigible.',
        },
      },
    ],
  },
  'inf-cuis': {
    instruccion: 'consulte el código y su escala. La reincidencia multiplica la multa.',
    bloques: [
      {
        titulo: 'Cuadro único de infracciones y sanciones',
        nota: 'La reincidencia multiplica la multa.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          {
            etiqueta: 'Materia',
            tipo: 's',
            opciones: ['Todas', 'Comercialización', 'Anuncios', 'Salubridad', 'Construcción'],
          },
          { etiqueta: 'UIT vigente', tipo: 'r' },
          { etiqueta: 'Buscar código', tipo: '1' },
        ],
        tabla: {
          titulo: 'Códigos CUIS',
          columnas: [
            { rotulo: 'Código', alineadoDerecha: false },
            { rotulo: 'Infracción', alineadoDerecha: false },
            { rotulo: '% UIT', alineadoDerecha: true },
            { rotulo: 'Multa S/', alineadoDerecha: true },
            { rotulo: '2ª vez', alineadoDerecha: true },
            { rotulo: 'Medida', alineadoDerecha: false },
          ],
        },
      },
    ],
  },
  'inf-esc': {
    instruccion: 'revise lo que está por notificar. Lo vencido sin notificar ya no se puede cobrar.',
    bloques: [
      {
        titulo: 'Escalas y plazos',
        nota: 'Lo vencido no se puede cobrar: el plazo de notificación es el que manda.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] },
          { etiqueta: 'Desde', tipo: 'd' },
          { etiqueta: 'Hasta', tipo: 'd' },
          {
            etiqueta: 'Estado',
            tipo: 's',
            opciones: ['Todos', 'Por notificar', 'Notificadas', 'Vencidas'],
          },
        ],
        tabla: {
          titulo: 'Notificaciones por estado',
          columnas: [
            { rotulo: 'Estado', alineadoDerecha: false },
            { rotulo: 'Expedientes', alineadoDerecha: true },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Plazo', alineadoDerecha: false },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          columnaDeInsignia: 4,
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla>>;
