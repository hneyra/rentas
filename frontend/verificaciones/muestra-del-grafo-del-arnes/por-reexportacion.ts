// Viola: `verificaciones/el-arnes-solo-importa-tipos-de-la-libreria.test.ts`. A PROPOSITO.
//
// Solo la alcanza `export * from './por-reexportacion.ts'` de `alcanzado.ts`: un `export … from`
// carga el modulo igual que un `import`, y el recorrido lo sigue.

// (18) Un valor, dos saltos por debajo de la raiz.
import { mismosCentimos } from '@kamayuk/formato';

export const POR_REEXPORTACION = mismosCentimos;
