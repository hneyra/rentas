import { Marco } from './marco/Marco.tsx';

/**
 * El casco de `rentas-web`.
 *
 * Desde F-3 monta **el marco V6** y nada mas: la barra global, el arbol de los
 * diez modulos, las pestanas, el enrutado por hash y el estado sin guardar.
 *
 * **Sigue sin haber una sola pantalla**, y es correcto que se vea asi: lo que
 * este casco tiene que demostrar es que el marco existe y navega. El contenido
 * de las cuatro secciones de Rentas y sus datos entran despues, por debajo de
 * el, sin tocar esta linea.
 */
export function Aplicacion() {
  return <Marco />;
}
