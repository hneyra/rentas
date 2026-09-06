import { Marco } from './marco/Marco.tsx';

/**
 * El casco de `rentas-web`.
 *
 * Desde F-3 monta **el marco V6**: la barra global, el arbol de los diez modulos,
 * las pestanas, el enrutado por hash y el estado sin guardar. Y desde F-5 el
 * marco tiene contenido que ensenar en **dos** de las cuatro secciones de Rentas
 * —el panel y el padron de contribuyentes—; las otras dos siguen declarando su
 * hueco hasta que lleguen.
 *
 * Sigue sin tocarse esta linea, que es lo que el reparto pretendia: una seccion
 * entra por debajo del marco, no por encima del casco.
 */
export function Aplicacion() {
  return <Marco />;
}
