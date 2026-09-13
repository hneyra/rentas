import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { I18nextProvider } from 'react-i18next';

import { Aplicacion } from './aplicacion.tsx';
import { arrancar } from './arranque.ts';
import i18n from './i18n/i18n.ts';
// El UNICO sitio donde se importa una hoja de estilos. `src/estilos.css` no define ni un color:
// importa la de `@kamayuk/ui` —que publica el `@theme` del artboard— y le dice a Tailwind donde
// mirar, porque por omision omite `node_modules` y la libreria vive ahi por el `link:`. El motivo
// entero, con lo que costo descubrirlo, esta dentro de ese archivo.
import './estilos.css';

const raiz = document.getElementById('raiz');
if (raiz === null) {
  // Revienta al principio y con su nombre. Un `raiz!` dejaria la pagina en blanco sin una
  // sola linea en la consola, que es el fallo mas caro de diagnosticar que hay.
  throw new Error('Falta el elemento #raiz en index.html: la aplicacion no tiene donde montarse.');
}

// El montaje va DENTRO de `arrancar`, no despues: el canje del codigo de autorizacion tiene que
// ocurrir antes de que la primera pantalla pida nada. Ver `arranque.ts`.
void arrancar(() => {
  createRoot(raiz).render(
    <StrictMode>
      <I18nextProvider i18n={i18n}>
        <Aplicacion />
      </I18nextProvider>
    </StrictMode>,
  );
});
