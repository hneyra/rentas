/* Genera docs/10-negocio/catalogo-de-opciones.md a partir del catalogo de
   pantallas del prototipo de interfaz (design/sgtm-data-*.js).

   Son 134 opciones: escribir la tabla a mano garantizaria que se desincronizara
   con el prototipo. La clasificacion en bloques es la del manual, calculada por
   el titulo de la pantalla; **no es la que agrupa la navegacion de la interfaz**,
   que desde ADR-0014 §4 agrupa por tarea con la tabla que declara modulo a
   modulo `frontend/scripts/grupos-por-tarea.mjs`.

   Uso: node docs/10-negocio/generar-catalogo.mjs
*/

import { createContext, runInContext } from 'node:vm';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const raiz = new URL('../../', import.meta.url);
const origen = new URL('design/', raiz);
const destino = new URL('docs/10-negocio/catalogo-de-opciones.md', raiz);

const ventana = {};
const contexto = createContext({ window: ventana, Object, Array, JSON, Math, String, Number });
for (let i = 1; i <= 5; i++) {
  runInContext(readFileSync(fileURLToPath(new URL(`sgtm-data-${i}.js`, origen)), 'utf8'), contexto);
}

const NAV = ventana.SGTM_NAV;
const PANTALLAS = ventana.SGTM_SCREENS;

/** Clasificacion del manual, por titulo de pantalla (FRO-03 §4). */
function clasificar(pantalla, etiqueta) {
  const t = ((pantalla && pantalla.title) || etiqueta || '').toLowerCase();
  if ((pantalla && pantalla.kind === 'report')
      || /reporte|padr[oó]n|resumen|record|constancia|resoluci[oó]n|certificado/.test(t)) {
    return 'Documentos';
  }
  if (/consulta|b[uú]squeda|estado de cuenta|hist[oó]rico|auditor[ií]a|panel|portal/.test(t)) {
    return 'Consultas';
  }
  if (/c[aá]lculo|generaci[oó]n|proceso|transferencia|pase|importaci[oó]n|emisi[oó]n|notificaci[oó]n|anulaci[oó]n|alta de|baja de|cambio|cambiar|actualizaci[oó]n|fraccionamiento|liquidaci[oó]n|declaraci[oó]n|duplicado|prescripci[oó]n|copias de seguridad|acto/.test(t)) {
    return 'Procesos';
  }
  return 'Registro';
}

/** Contexto acotado que sirve cada modulo del menu (ARQ-01 §3). */
const CONTEXTO = {
  'Inicio': 'transversal',
  'Catastro': 'catastro',
  'Rentas · Registro': 'rentas',
  'Fiscalización': 'fiscalizacion',
  'Tránsito': 'sanciones',
  'Infracciones administrativas': 'sanciones',
  'Tesorería': 'tesoreria',
  'Consultas': 'cuentacorriente',
  'Valores': 'valores',
  'Coactiva': 'coactiva',
  'Autorizaciones y licencias': 'licencias',
  'Seguridad': 'seguridad',
};

/* Lo que el backend ya publica de un modulo, cuando no se deduce de la tabla.

   El `endpoint` de cada fila sale del prototipo de interfaz: dice que la
   pantalla existe y que operacion declara, no si el backend la sirve ni si
   ademas se puede escribir en ella. Cuando lo publicado se aparta de lo que el
   prototipo declara —una pantalla de mantenimiento cuyo endpoint es un GET, un
   PUT que cubre mas tipos de los que su ruta sugiere—, la nota lo dice bajo el
   titulo del modulo. Sin nota, no se emite nada. */
const NOTAS = {
  'Catastro': [
    '**Lo que el backend ya publica (#290).** `calles` y `sectores` dan de alta y editan, y la',
    'baja es lógica: no se borra ninguna fila (RNF-051). `sectores` además da de alta manzanas, y',
    'solo eso —el código de una manzana es un tramo del código catastral de sus predios, así que',
    'cambiarlo los desalinearía a todos—. Las cuatro fichas se inscriben, y el alta',
    '**crea el predio en el mismo acto** si todavía no existe —`ficha_catastral.predio_id` es',
    '`NOT NULL`—, con su titularidad inicial si ya se conoce. `actualizacion_catastro` versiona',
    '**los cuatro tipos de ficha**, no solo el urbano, aunque su endpoint declare la ruta del',
    'urbano. Toda escritura exige la observación del usuario (RNF-052) y deja auditoría.',
    '',
    '`aranceles`, `valores_unitarios` y `depreciacion` siguen **de solo lectura**, y no por olvido:',
    'el arancel se carga por lote contra un conjunto de parámetros que alguien abre y sella',
    '(`AdministrarParametros.abrirVersion` + `ImportarArancel`), no fila a fila desde una pantalla;',
    'y las otras dos son catálogos nacionales desde ADR-0017, que además dice quién las escribe:',
    'el proceso batch de publicación, nunca una pantalla.',
  ],
};

/** Donde lo describe el manual. */
const CAPITULO = {
  'Inicio': '—',
  'Catastro': 'cap. 2',
  'Rentas · Registro': 'cap. 3 §Registro',
  'Fiscalización': 'cap. 3 §Fiscalización',
  'Tránsito': 'cap. 3 §Tránsito',
  'Infracciones administrativas': 'cap. 3 §Infracciones administrativas',
  'Tesorería': 'cap. 3 §Tesorería',
  'Consultas': 'cap. 3 §Consultas',
  'Valores': 'cap. 3 §Valores',
  'Coactiva': 'cap. 3 §Coactivas',
  'Autorizaciones y licencias': 'cap. 3 §Autorizaciones y §Licencias',
  'Seguridad': 'cap. 4',
};

const lineas = [];
lineas.push('# NEG-03 — Catálogo de opciones');
lineas.push('');
lineas.push('Las **134 opciones** de los **12 módulos** del sistema, con el `endpoint` que cada una');
lineas.push('declara en el prototipo de interfaz y el contexto acotado que la sirve.');
lineas.push('');
lineas.push('**Este archivo se genera.** Regenerarlo con `node docs/10-negocio/generar-catalogo.mjs`');
lineas.push('cuando cambie el catálogo del prototipo; no editarlo a mano.');
lineas.push('');
lineas.push('Leyenda de bloque: `Registro` = registro y mantenimiento · `Procesos` · `Consultas` ·');
lineas.push('`Documentos` = documentos y reportes. Es la **taxonomía del manual**, y la calcula el');
lineas.push('título de la pantalla ([FRO-03 §4](../60-frontend/mapa-de-pantallas.md)).');
lineas.push('');
lineas.push('> **No es la clasificación que agrupa la navegación de la interfaz.** Desde');
lineas.push('> [`ADR-0014`](../30-arquitectura/adr/ADR-0014-navegacion-centrada-en-la-atencion.md) §4 el');
lineas.push('> menú agrupa **por tarea**, con los grupos que declara módulo a módulo la tabla del');
lineas.push('> portador (`frontend/scripts/grupos-por-tarea.mjs`); los cuatro bloques quedan ahí como');
lineas.push('> respaldo de un módulo que la tabla no cubra. Las dos clasificaciones conviven sin');
lineas.push('> estorbarse porque **nada funcional depende de esta columna**: el backend siembra los');
lineas.push('> accesos leyendo de cada fila solo el `id` y el nombre de la opción');
lineas.push('> (`CatalogoDeOpciones`), y el identificador de la opción sigue siendo la clave del');
lineas.push('> permiso, agrupe quien agrupe.');
lineas.push('');
lineas.push('El `endpoint` es el que **declara el prototipo**: dice qué operación pide la pantalla, no');
lineas.push('si el backend la sirve ni si además se puede escribir en ella. Cuando lo publicado se');
lineas.push('aparta de eso, el módulo lleva una **nota** bajo su título.');
lineas.push('');
lineas.push('| Módulo | Manual | Contexto | Opciones |');
lineas.push('|---|---|---|---|');
for (const grupo of NAV) {
  lineas.push(`| ${grupo.label} | ${CAPITULO[grupo.label]} | \`${CONTEXTO[grupo.label]}\` | ${grupo.items.length} |`);
}
lineas.push(`| **Total** | | | **${NAV.reduce((n, g) => n + g.items.length, 0)}** |`);

for (const grupo of NAV) {
  lineas.push('');
  lineas.push(`## ${grupo.label}`);
  lineas.push('');
  lineas.push(`Manual: ${CAPITULO[grupo.label]} · contexto acotado: \`${CONTEXTO[grupo.label]}\``);
  lineas.push('');
  if (NOTAS[grupo.label]) {
    lineas.push(...NOTAS[grupo.label]);
    lineas.push('');
  }
  lineas.push('| id | Opción | Bloque | Endpoint |');
  lineas.push('|---|---|---|---|');
  for (const [id, etiqueta] of grupo.items) {
    const pantalla = PANTALLAS[id] || {};
    lineas.push(
      `| \`${id}\` | ${pantalla.title || etiqueta} | ${clasificar(pantalla, etiqueta)} |`
        + ` \`${pantalla.endpoint || '—'}\` |`,
    );
  }
}
lineas.push('');

writeFileSync(fileURLToPath(destino), lineas.join('\n'), 'utf8');
console.log(`catalogo generado: ${NAV.length} modulos, ${NAV.reduce((n, g) => n + g.items.length, 0)} opciones`);
