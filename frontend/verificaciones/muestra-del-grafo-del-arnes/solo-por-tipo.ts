// NO la alcanza el recorrido, y la guarda NO puede senalar lo de aqui.
//
// `raiz.ts` la importa SOLO por un tipo, y la transpilacion borra ese `import`: Node no la carga.
// Es lo que pasa hoy en el arbol, medido: `e2e/el-403-del-catalogo.spec.ts` importa un tipo de
// `src/datos/useCatalogoPermitido.ts`, que llega por `src/catalogo.ts` a un
// `import { ICONOS } from '@kamayuk/ui'`, y el arnes carga sus caminos igual. Una guarda que
// siguiera tambien los `import type` daria ese rojo, y seria falso.

import { EL_SUJETO } from '@kamayuk/ui';

export interface SoloPorTipo {
  readonly sujeto: typeof EL_SUJETO;
}

export const DE_VALOR_PERO_NO_CARGADO = EL_SUJETO;
