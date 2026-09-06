import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Aplicacion } from './aplicacion.tsx';

/**
 * La prueba vive JUNTO al codigo (F-1, AC5). No comprueba una pantalla —sigue sin
 * haber ninguna—: prueba que el andamiaje esta enchufado de verdad, jsdom
 * incluido. Sin ella, `vitest run` en un proyecto sin ninguna prueba sale en
 * verde y `yarn verificar` no verificaria nada.
 *
 * Hasta F-3 miraba el `<h1>Rentas</h1>` del casco de relleno. Ese encabezado ya
 * no existe: el casco monta el marco, y el `<h1>` del marco es el titulo de la
 * pestana activa. Lo que se comprueba aqui es que el casco monta EL MARCO —su
 * barra, su arbol y sus pestanas—, no una copia del contenido del marco, que se
 * prueba entero en `marco/Marco.test.tsx`.
 */
describe('el casco de rentas-web', () => {
  it('monta el marco V6: su barra, su arbol y su barra de pestanas', () => {
    render(<Aplicacion />);

    expect(screen.getByRole('banner')).toBeInTheDocument();
    expect(
      screen.getByRole('complementary', { name: 'Módulos y submódulos' }),
    ).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Pestañas abiertas' })).toBeInTheDocument();
  });
});
