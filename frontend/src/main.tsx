import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { Aplicacion } from './aplicacion.tsx';
// El UNICO sitio donde se importa una hoja de estilos. `estilos.css` encadena los cinco
// archivos de tokens y las clases de los componentes, en ese orden; si cada componente
// trajera la suya, el orden de la cascada lo decidiria el orden en que Vite resuelve los
// modulos — que cambia con un `import` movido de sitio.
import './estilos/estilos.css';

const raiz = document.getElementById('raiz');
if (raiz === null) {
  // Revienta al principio y con su nombre. Un `raiz!` dejaria la pagina en blanco sin una
  // sola linea en la consola, que es el fallo mas caro de diagnosticar que hay.
  throw new Error('Falta el elemento #raiz en index.html: la aplicacion no tiene donde montarse.');
}

createRoot(raiz).render(
  <StrictMode>
    <Aplicacion />
  </StrictMode>,
);
