// Viola: `verificaciones/el-arnes-solo-importa-tipos-de-la-libreria.test.ts`. A PROPOSITO.
//
// Solo la alcanza `import { type PorTiposEntreLlaves } from './por-tipos-entre-llaves.ts'` de
// `raiz.ts`: todos sus especificadores llevan `type`, pero la declaracion no es `import type`, y
// con `verbatimModuleSyntax` se queda en `import {} from` y carga este modulo. El recorrido la
// sigue, y lo de aqui cuenta. Es lo contrario de `solo-por-tipo.ts`.

// (17) Un valor detras de un relativo con todos sus especificadores `type`.
import { sumarImportes } from '@kamayuk/formato';

export interface PorTiposEntreLlaves {
  readonly sumar: typeof sumarImportes;
}

export const POR_TIPOS_ENTRE_LLAVES = sumarImportes;
