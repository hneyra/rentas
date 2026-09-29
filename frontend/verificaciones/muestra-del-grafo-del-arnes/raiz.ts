// Viola: `verificaciones/el-arnes-solo-importa-tipos-de-la-libreria.test.ts`. A PROPOSITO.
//
// Hace de spec del arnes: la guarda la toma como raiz y recorre lo que alcanza. Compila y pasa el
// lint —las formas malas son TypeScript valido, y la de #635 tambien lo era—; lo que no pasaria es
// la carga en Node con la libreria clonada sin dependencias, que es como esta en la CI.
//
// Nada de esto se ejecuta: Playwright no la ve, porque no esta en `e2e/`, y `vitest` tampoco,
// porque no es `*.test.ts`. No vive en `muestras/`, que es de las prohibiciones de ESLint y exige
// que cada archivo tenga una que lo reclame; es el motivo por el que existen `muestra-del-arnes/` y
// `muestra-de-rutas/`.

// ── Las que la guarda DEJA PASAR: `import type` y `export type`, que borra cualquier cargador ──

// (a) El `import` entero de tipos.
import type { DefinicionDePantalla } from '@kamayuk/ui';
// (b) El espacio de nombres, de tipos.
import type * as Ui from '@kamayuk/ui';
// (c) Un relativo de `import type` no se sigue: lo de detras no lo carga nadie.
import type { SoloPorTipo } from './solo-por-tipo.ts';

// Y un relativo de VALOR, que si se sigue: las formas malas de alli cuentan.
import { ALCANZADO } from './alcanzado.ts';

// ── Y las que la guarda SENALA aqui ──

// (1) La de #635, tal como la escribio #172 en `transito.ts`: un valor entre los tipos.
import { EL_SUJETO, type DefinicionDePantalla as Pantalla } from '@kamayuk/ui';
// (2) TODOS los especificadores con `type`, pero sin `import type`. El Babel de Playwright 1.63 la
//     borra entera; con `verbatimModuleSyntax` —lo que declara `tsconfig.base.json`— se queda en
//     `import {} from` y carga. Medido con el cargador de Node 24.21: carga.
import { type DatoConNombre, type TonoDeInsignia } from '@kamayuk/ui';
// Y lo mismo con un relativo: por eso mismo SE SIGUE, y la forma (17) esta detras de este.
import { type PorTiposEntreLlaves } from './por-tipos-entre-llaves.ts';

// (d) Re-exportar tipos con `export type` tampoco carga nada.
export type { NombreDeIcono } from '@kamayuk/ui';
// (3) Pero `export { type X } from` es la (2) por la otra puerta: se queda en `export {} from`.
export { type Catalogo } from '@kamayuk/shell';

// (e) `typeof import(…)` en posicion de tipo, que es el remedio de #635: no es una llamada.
export const SUJETO = 'sujeto' satisfies typeof import('@kamayuk/ui').EL_SUJETO;

export type DeLaMuestra = [
  DefinicionDePantalla,
  DatoConNombre,
  TonoDeInsignia,
  typeof Ui.EL_SUJETO,
  SoloPorTipo,
  Pantalla,
  PorTiposEntreLlaves,
];

export const DE_VALOR = [ALCANZADO, EL_SUJETO];
