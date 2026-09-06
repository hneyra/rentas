import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';

import { Aplicacion } from './aplicacion.tsx';
import { arrancar } from './arranque.ts';

const raiz = document.getElementById('raiz');
if (raiz === null) {
  // Revienta al principio y con su nombre. Un `raiz!` dejaria la pagina en blanco sin una
  // sola linea en la consola, que es el fallo mas caro de diagnosticar que hay.
  throw new Error('Falta el elemento #raiz en index.html: la aplicacion no tiene donde montarse.');
}

// El montaje va DENTRO de `arrancar`, no despues: con el proxy de datos encendido, una
// pantalla no debe poder pedir datos antes de que haya quien conteste. Ver `arranque.ts`.
void arrancar(() => {
  createRoot(raiz).render(
    <StrictMode>
      <Aplicacion />
    </StrictMode>,
  );
});
