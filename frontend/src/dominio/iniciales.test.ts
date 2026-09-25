import { describe, expect, it } from 'vitest';

import { inicialesDe } from './iniciales.ts';

describe('las iniciales de la barra salen del nombre de la sesion (#356)', () => {
  it.each([
    // La cuenta que contesta la instalacion (`sesionMedida.ts`): la particula no cuenta.
    ['Administrador del Sistema', 'AS'],
    // La regla del artboard: la inicial y el PRIMER apellido, no el ultimo.
    ['J. Cardenas Vega', 'JC'],
    ['Juan Carlos Pérez Díaz', 'JC'],
    // Las tildes se quedan: quitarlas escribiria mal el nombre de alguien.
    ['María de los Ángeles Ruiz', 'MÁ'],
    ['óscar núñez', 'ÓN'],
    // Una sola palabra da una sola letra: no se inventa la segunda.
    ['administrador', 'A'],
    // Los signos no son letras: ni las comillas ni el parentesis van al circulo.
    ['«Rosa» (Tesorería) Lozada', 'RT'],
    ['  Rosa   Lozada  ', 'RL'],
    // Si todo son particulas, se usan tal cual antes que dejar el circulo vacio.
    ['De la', 'DL'],
  ])('«%s» -> «%s»', (nombre, iniciales) => {
    expect(inicialesDe(nombre)).toBe(iniciales);
  });

  it.each(['', '   ', '— · —', '123'])(
    'un nombre sin ninguna letra («%s») da la cadena vacia, y no una letra inventada',
    (nombre) => {
      expect(inicialesDe(nombre)).toBe('');
    },
  );
});
