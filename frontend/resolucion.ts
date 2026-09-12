import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

import { enlacesDeclarados } from './verificaciones/enlace.ts';

/**
 * **Lo que los paquetes enlazados dan por puesto, y que por tanto resuelve ESTE frontend.**
 *
 * <h2>El problema, que solo aparece con `link:`</h2>
 *
 * `@kamayuk/ui` viaja como FUENTE —`main: index.ts`, sin compilar— y llega por un enlace
 * simbolico al clon hermano. Asi que cuando algo de dentro hace `import 'react'`, la resolucion
 * arranca en `/…/kamayuk-lib/paquetes/ui/` y sube por **su** arbol, no por el de aqui. Dos cosas
 * pasan, y las dos son malas:
 *
 *   1. **En local**, donde el hermano SI tiene sus dependencias instaladas, se resuelven —y salen
 *      DOS copias de React en la misma pagina—. El rojo es `Cannot read properties of null
 *      (reading 'useId')`, en la primera pieza que use un gancho, y no menciona nada de esto.
 *   2. **En CI y en la imagen**, donde el hermano se clona pero no se instala, **no se resuelven**.
 *      Medido: `Cannot find module 'react' or its corresponding type declarations`, veintitantas
 *      veces, sobre archivos de la libreria.
 *
 * <h2>Por que la lista se DERIVA y no se escribe</h2>
 *
 * Porque ya existe y es justamente esa: las `peerDependencies` de cada paquete enlazado. Eso es
 * literalmente lo que significan —«esto lo pone quien me consume»—, asi que son exactamente las
 * que hay que resolver desde aqui. Escritas a mano, la lista se queda vieja **en silencio** el dia
 * que la libreria anada una: lo comprobado fue que anadio SIETE en tres PR sin que nadie lo notara
 * (#88). Derivada, no puede.
 *
 * `verificaciones/las-peerdependencies-estan.test.ts` comprueba la otra mitad: que este frontend
 * las declare todas.
 *
 * <h2>Y por que `dedupe` y no `preserveSymlinks`</h2>
 *
 * `preserveSymlinks` seria mas directo —hace que la libreria resuelva como si estuviera dentro de
 * este frontend— y es lo que se usa para **TypeScript**, en `tsconfig.base.json`. Pero en Vite
 * rompe la transformacion: probado, `loadAndTransform` revienta y no se recoge ni un archivo.
 */
export const LO_QUE_PONE_EL_CONSUMIDOR: readonly string[] = (() => {
  const requerir = createRequire(import.meta.url);
  const enlaces = enlacesDeclarados(readFileSync(new URL('./package.json', import.meta.url), 'utf8'));
  const nombres = new Set<string>();
  for (const enlace of enlaces) {
    const manifiesto = JSON.parse(
      readFileSync(join(dirname(requerir.resolve(enlace.paquete)), 'package.json'), 'utf8'),
    ) as { peerDependencies?: Record<string, string> };
    for (const nombre of Object.keys(manifiesto.peerDependencies ?? {})) nombres.add(nombre);
  }
  if (nombres.size === 0) {
    // Sin esto, un enlace roto dejaria la lista vacia y el sintoma seria el de arriba: dos React,
    // o ninguno. Mejor no arrancar que arrancar resolviendo mal.
    throw new Error(
      'Ningun paquete de `@kamayuk/*` declaro `peerDependencies`. O el enlace esta roto —falta el ' +
        'clon hermano: `git clone https://github.com/hneyra/kamayuk-lib ../../kamayuk-lib`— o la ' +
        'libreria dejo de declararlas, y entonces esta resolucion ya no vale.',
    );
  }
  return [...nombres];
})();
