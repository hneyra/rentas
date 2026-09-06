import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Aplicacion } from './aplicacion.tsx';

/**
 * La prueba vive JUNTO al codigo (AC5). No comprueba una pantalla —no hay ninguna—: prueba
 * que el andamiaje esta enchufado de verdad, jsdom incluido. Sin ella, `vitest run` en un
 * proyecto sin ninguna prueba sale en verde y `yarn verificar` no verificaria nada.
 */
describe('el casco de rentas-web', () => {
  it('monta y anuncia el sistema por su encabezado', () => {
    render(<Aplicacion />);

    expect(screen.getByRole('heading', { level: 1, name: 'Rentas' })).toBeInTheDocument();
  });
});
