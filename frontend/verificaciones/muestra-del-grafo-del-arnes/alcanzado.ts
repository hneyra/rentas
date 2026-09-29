// Viola: `verificaciones/el-arnes-solo-importa-tipos-de-la-libreria.test.ts`. A PROPOSITO.
//
// La alcanza `raiz.ts` por un `import` de VALOR, asi que todo lo de aqui lo cargaria Node. Cada
// forma lleva su numero; `raiz.ts` tiene la (1) y `por-reexportacion.ts` la ultima.

// (2) El espacio de nombres entero.
import * as ui from '@kamayuk/ui';
// (3) De efecto: no trae nombres, y cargar es lo unico que hace.
import '@kamayuk/shell';
// (4) Las llaves vacias. Medido en el arnes: se quedan como un `import` de efecto y cargan.
import {} from '@kamayuk/ui';
// (5) Por omision. Ningun `@kamayuk/*` exporta por omision; lo que se vigila es la forma.
// @ts-expect-error -- `@kamayuk/formato` no tiene export por omision, y la muestra es la forma
import formato from '@kamayuk/formato';
// (6) Un paquete SIN dependencias. Medido: hoy cargaria en la CI, y la regla lo senala igual.
import { sumarImportes } from '@kamayuk/formato';
// (7) Un relativo que no resuelve: el recorrido no puede decir que cargaria.
import './no-existe.ts';
// (8) Un relativo que sale de `frontend/`. La forma de verdad seria el clon hermano por su ruta
//     —`../../../../kamayuk-lib/paquetes/ui/index.ts`—, que resuelve sus dependencias contra un
//     arbol que en la CI no las tiene; aqui apunta a un archivo que existe seguro fuera.
import '../../../.nvmrc';

// (9) Re-exportar un valor lo carga igual que importarlo.
export { EL_SUJETO } from '@kamayuk/ui';
// (10) Y todo, con nombre o sin el.
export * from '@kamayuk/formato';
export * as api from '@kamayuk/api';
// (11) `export {} from`. Medido en el arnes: tambien carga.
export {} from '@kamayuk/ui';

// Y la guarda sigue tambien un `export … from` relativo: la ultima forma esta detras de este.
export * from './por-reexportacion.ts';

/** (12) El `import()` dinamico: no carga al declarar los caminos, sino al recorrerlos. */
export async function cargarLaLibreria(): Promise<unknown> {
  return import('@kamayuk/ui');
}

export const ALCANZADO = [ui.EL_SUJETO, formato, sumarImportes];
