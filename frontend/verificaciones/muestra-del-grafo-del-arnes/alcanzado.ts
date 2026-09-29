// Viola: `verificaciones/el-arnes-solo-importa-tipos-de-la-libreria.test.ts`. A PROPOSITO.
//
// La alcanza `raiz.ts` por un `import` de VALOR, asi que todo lo de aqui lo cargaria Node. Cada
// forma lleva su numero, en el orden en que la guarda las senala: `raiz.ts` tiene de la (1) a la
// (3), `por-tipos-entre-llaves.ts` la (17) y `por-reexportacion.ts` la ultima.

// (4) El espacio de nombres entero.
import * as ui from '@kamayuk/ui';
// (5) De efecto: no trae nombres, y cargar es lo unico que hace.
import '@kamayuk/shell';
// (6) Las llaves vacias. Medido en el arnes: se quedan como un `import` de efecto y cargan.
import {} from '@kamayuk/ui';
// (7) Por omision. Ningun `@kamayuk/*` exporta por omision; lo que se vigila es la forma.
// @ts-expect-error -- `@kamayuk/formato` no tiene export por omision, y la muestra es la forma
import formato from '@kamayuk/formato';
// (8) Un paquete SIN dependencias. Medido: hoy cargaria en la CI, y la regla lo senala igual.
import { sumarImportes } from '@kamayuk/formato';
// (9) Un relativo que no resuelve: el recorrido no puede decir que cargaria.
import './no-existe.ts';
// (10) Un relativo que sale de `frontend/`. La forma de verdad seria el clon hermano por su ruta
//      —`../../../../kamayuk-lib/paquetes/ui/index.ts`—, que resuelve sus dependencias contra un
//      arbol que en la CI no las tiene; aqui apunta a un archivo que existe seguro fuera.
import '../../../.nvmrc';
// (11) El clon por su ruta ABSOLUTA, que Node carga igual que un relativo —medido con Node 24.21—.
//      La de verdad seria la del puesto, `/home/…/kamayuk-lib/paquetes/ui/index.ts`, y saldria
//      como la (10); una ruta de un puesto no se escribe aqui, asi que esta no existe en ninguno.
//      Lo que se vigila es que no se salte en silencio, que es lo que hacia la guarda.
import '/no-existe-en-ningun-puesto/kamayuk-lib/paquetes/ui/index.ts';
// (12) Y por una URL `file:`, que Node toma entera y sin base: absoluta siempre.
import 'file:///no-existe-en-ningun-puesto/kamayuk-lib/paquetes/ui/index.ts';

// (13) Re-exportar un valor lo carga igual que importarlo.
export { EL_SUJETO } from '@kamayuk/ui';
// (14) Y todo, con nombre o sin el.
export * from '@kamayuk/formato';
export * as api from '@kamayuk/api';
// (15) `export {} from`. Medido en el arnes: tambien carga.
export {} from '@kamayuk/ui';

// Y la guarda sigue tambien un `export … from` relativo: la ultima forma esta detras de este.
export * from './por-reexportacion.ts';

/** (16) El `import()` dinamico: no carga al declarar los caminos, sino al recorrerlos. */
export async function cargarLaLibreria(): Promise<unknown> {
  return import('@kamayuk/ui');
}

export const ALCANZADO = [ui.EL_SUJETO, formato, sumarImportes];
