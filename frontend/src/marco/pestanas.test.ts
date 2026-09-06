import { describe, expect, it } from 'vitest';

import type { EstadoDePestanas } from './pestanas.ts';
import { estadoInicial, reducir } from './pestanas.ts';

/**
 * AC3, AC5 y AC6 sobre la mitad del marco que no necesita un navegador.
 *
 * Cerrar la primera de tres pestanas y comprobar cual queda activa es una
 * afirmacion sobre una lista, no sobre el DOM. Probarla montando React costaria
 * un `render` por caso y diria menos: lo que hay que ver es la lista de antes y
 * la de despues.
 */

/** Abre en orden, partiendo del estado inicial. */
function con(...destinos: readonly string[]): EstadoDePestanas {
  return destinos.reduce(
    (estado, destino) => reducir(estado, { tipo: 'abrir', destino }),
    estadoInicial(null),
  );
}

describe('el estado de partida', () => {
  it('sin hash, arranca con el panel abierto y activo', () => {
    expect(estadoInicial(null)).toEqual({
      abiertas: ['panel'],
      activa: 'panel',
      sucias: {},
      porCerrar: null,
    });
  });

  it('con hash, la seccion pedida ADEMAS tiene su pestana', () => {
    // El artboard fijaba `dest` desde el hash sin tocar `abiertas`: recargar
    // sobre `#determinacion` dejaba activa una seccion sin pestana en la barra
    // —no se veia y no se podia cerrar—. Aqui la pestana existe.
    expect(estadoInicial('territorio')).toEqual({
      abiertas: ['panel', 'territorio'],
      activa: 'territorio',
      sucias: {},
      porCerrar: null,
    });
  });

  it('si el hash pide el panel, no se abre dos veces', () => {
    expect(estadoInicial('panel').abiertas).toEqual(['panel']);
  });
});

describe('AC3 — abrir anade si no estaba, y si estaba solo activa', () => {
  it('un submodulo nuevo se anade al final', () => {
    const estado = con('fis-actas', 'coa-exp');

    expect(estado.abiertas).toEqual(['panel', 'fis-actas', 'coa-exp']);
    expect(estado.activa).toBe('coa-exp');
  });

  it('uno que ya estaba no se duplica: solo pasa a ser el activo', () => {
    const estado = reducir(con('fis-actas', 'coa-exp'), { tipo: 'abrir', destino: 'fis-actas' });

    expect(estado.abiertas).toEqual(['panel', 'fis-actas', 'coa-exp']);
    expect(estado.activa).toBe('fis-actas');
  });

  it('abrir el que ya esta activo devuelve el MISMO estado, no uno igual', () => {
    // No es una optimizacion: el oyente de `hashchange` despacha un `abrir` por
    // cada cambio de hash y el hash lo escribe el efecto que sigue a la pestana
    // activa. Con un objeto nuevo cada vez, los dos se llamarian en circulo.
    const antes = con('fis-actas');

    expect(reducir(antes, { tipo: 'abrir', destino: 'fis-actas' })).toBe(antes);
  });
});

describe('AC5 — el estado sin guardar', () => {
  it('editar marca la pestana ACTIVA, y solo esa', () => {
    const estado = reducir(con('fis-actas'), { tipo: 'ensuciar' });

    expect(estado.sucias).toEqual({ 'fis-actas': true });
  });

  it('sin ninguna pestana activa no se ensucia nada', () => {
    const vacio = reducir(reducir(estadoInicial(null), { tipo: 'cerrar', destino: 'panel' }), {
      tipo: 'ensuciar',
    });

    expect(vacio.activa).toBeNull();
    expect(vacio.sucias).toEqual({});
  });

  it('cerrar una pestana SUCIA pregunta en vez de cerrar', () => {
    const sucia = reducir(con('fis-actas'), { tipo: 'ensuciar' });
    const estado = reducir(sucia, { tipo: 'pedir-cierre', destino: 'fis-actas' });

    expect(estado.porCerrar).toBe('fis-actas');
    expect(estado.abiertas, 'preguntar no cierra').toEqual(['panel', 'fis-actas']);
  });

  it('cerrar una pestana LIMPIA no pregunta: cierra', () => {
    const estado = reducir(con('fis-actas'), { tipo: 'pedir-cierre', destino: 'fis-actas' });

    expect(estado.porCerrar).toBeNull();
    expect(estado.abiertas).toEqual(['panel']);
  });

  it('seguir editando deja todo como estaba, la marca incluida', () => {
    const sucia = reducir(con('fis-actas'), { tipo: 'ensuciar' });
    const preguntando = reducir(sucia, { tipo: 'pedir-cierre', destino: 'fis-actas' });
    const estado = reducir(preguntando, { tipo: 'cancelar-cierre' });

    expect(estado.porCerrar).toBeNull();
    expect(estado.abiertas).toEqual(['panel', 'fis-actas']);
    expect(estado.sucias).toEqual({ 'fis-actas': true });
  });

  it('al cerrarla de verdad, su marca se va con ella', () => {
    const sucia = reducir(con('fis-actas'), { tipo: 'ensuciar' });
    const estado = reducir(sucia, { tipo: 'cerrar', destino: 'fis-actas' });

    expect(estado.sucias).toEqual({});
  });
});

describe('AC6 — cerrar la activa activa la vecina', () => {
  it('la de la IZQUIERDA cuando la hay', () => {
    const tres = con('fis-actas', 'coa-exp');
    const estado = reducir(tres, { tipo: 'cerrar', destino: 'coa-exp' });

    expect(estado.abiertas).toEqual(['panel', 'fis-actas']);
    expect(estado.activa).toBe('fis-actas');
  });

  it('la de la DERECHA cuando se cierra la primera', () => {
    const tres = reducir(con('fis-actas', 'coa-exp'), { tipo: 'abrir', destino: 'panel' });
    const estado = reducir(tres, { tipo: 'cerrar', destino: 'panel' });

    expect(estado.abiertas).toEqual(['fis-actas', 'coa-exp']);
    expect(estado.activa).toBe('fis-actas');
  });

  it('cerrar una que NO es la activa no mueve la activa', () => {
    const tres = con('fis-actas', 'coa-exp');
    const estado = reducir(tres, { tipo: 'cerrar', destino: 'fis-actas' });

    expect(estado.abiertas).toEqual(['panel', 'coa-exp']);
    expect(estado.activa).toBe('coa-exp');
  });

  it('cerrar la ULTIMA deja el espacio vacio, y eso es lo correcto', () => {
    const estado = reducir(estadoInicial(null), { tipo: 'cerrar', destino: 'panel' });

    expect(estado.abiertas).toEqual([]);
    expect(estado.activa, 'no hay nada abierto: fingir una activa seria mentir').toBeNull();
  });

  it('cerrar algo que no esta abierto no hace nada', () => {
    const antes = con('fis-actas');

    expect(reducir(antes, { tipo: 'cerrar', destino: 'coa-exp' })).toBe(antes);
  });
});
