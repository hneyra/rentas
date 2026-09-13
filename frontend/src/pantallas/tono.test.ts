import { describe, expect, it } from 'vitest';

import { tonoDe } from './tono.ts';

/**
 * **El reparto de tonos de ESTE sistema** (#153).
 *
 * Su unica prueba vivia en `Pantalla.test.tsx` —«la columna de situacion se pinta como insignia,
 * con el tono que el TEXTO pide»—, que se fue a `@kamayuk/ui` con el interprete. Alli sigue
 * probandose que la insignia lleva **el tono que el sistema dice**, con un reparto inventado; lo
 * que no puede probarse alli es **este** reparto, porque su vocabulario es de Rentas y la libreria
 * no lo nombra. Asi que se queda aqui, que es donde vive lo que prueba.
 */
describe('el tono de una insignia sale de lo que DICE la celda', () => {
  it('lo que ya ha ido mal pide accion hoy', () => {
    for (const texto of ['En coactiva', 'Observado', 'Vencida', 'Denegado']) {
      expect(tonoDe(texto), texto).toBe('mal');
    }
  });

  it('lo que va a ir mal tiene plazo, pero corre', () => {
    for (const texto of ['Con deuda', 'Por vencer', 'En tramite', 'En trámite']) {
      expect(tonoDe(texto), texto).toBe('atencion');
    }
  });

  it('lo demas esta conforme: no se enumeran los estados buenos', () => {
    // Una lista de buenos incompleta pintaria de rojo lo que simplemente no esta en ella.
    for (const texto of ['Conforme', 'Vigente', 'Pagado', 'Emitida']) {
      expect(tonoDe(texto), texto).toBe('ok');
    }
  });

  it('no distingue mayusculas: «VENCIDA» sigue siendo mal', () => {
    expect(tonoDe('VENCIDA')).toBe('mal');
  });

  it('`info` no sale de aqui: es de los avisos de la pantalla, no de una fila', () => {
    const textos = ['En coactiva', 'Con deuda', 'Conforme', '', 'cualquier cosa'];
    expect(textos.map(tonoDe)).not.toContain('info');
  });
});
