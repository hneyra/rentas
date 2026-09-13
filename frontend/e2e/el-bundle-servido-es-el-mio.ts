import { comprobarQueElBundleServidoEsElMio } from '../puerto-del-arnes.mjs';

/**
 * **Antes del primer camino: que lo que se sirve sea lo que este arbol construyo** (#148).
 *
 * <h2>Por que no basta con elegir bien el puerto</h2>
 *
 * Porque el puerto puede estar libre al empezar y dejar de estarlo durante el `yarn build`,
 * que dura lo que dura. Medido en el codigo de Playwright (`runner/index.js`): el aviso de
 * «ya esta usado» se da UNA vez, antes de lanzar el comando; luego se carrera la muerte del
 * proceso contra la disponibilidad de la URL. Si un intruso ocupa el puerto en ese hueco, el
 * `vite preview` de esta rama muere por `--strictPort` **y la URL contesta igual**, porque la
 * contesta el intruso. Los caminos corren igual, y miden lo que ese intruso sirva.
 *
 * Eso es lo que #148 llama «lo que de verdad asusta»: no el rojo ajeno, sino el **verde**
 * ajeno.
 *
 * <h2>Por que aqui y no en un camino mas</h2>
 *
 * Un `globalSetup` que falla detiene la corrida entera; un camino que falla es uno solo y
 * deja correr los demas contra un servidor que no es este. Y ademas corre DESPUES de que
 * el `webServer` este arriba —el orden esta en `createGlobalSetupTasks`, que pone los
 * complementos delante—, que es justo cuando se puede preguntar.
 */
export default async function elBundleServidoEsElMio(): Promise<void> {
  await comprobarQueElBundleServidoEsElMio();
}
