import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Marco } from './Marco.tsx';

/**
 * El marco, montado. AC2 a AC9.
 *
 * Lo que se prueba aqui es lo que solo se ve con el arbol de React puesto: que
 * abrir no cierre el panel, que el hash lo escriba `replaceState`, que el
 * asterisco aparezca al escribir, que el dialogo ofrezca sus TRES salidas y que
 * un submodulo ajeno abra su ficha. La aritmetica de las pestanas se prueba sin
 * navegador en `pestanas.test.ts`, y el filtro en `filtro.test.ts`: montar el
 * marco para comprobar que cerrar la primera de tres activa la segunda diria
 * menos y costaria un `render`.
 */

const limpiarElHash = () => {
  window.history.replaceState(null, '', '/');
};

beforeEach(limpiarElHash);
afterEach(() => {
  vi.restoreAllMocks();
  limpiarElHash();
});

/** El panel de la izquierda. */
const arbol = () => screen.getByRole('complementary', { name: 'Módulos y submódulos' });

/** La cabecera de un modulo del arbol: es la unica con `aria-expanded`. */
const modulo = (rotulo: string, abierto = false) =>
  within(arbol()).getByRole('button', { name: new RegExp(`^${rotulo}`), expanded: abierto });

/**
 * Un submodulo del arbol.
 *
 * No basta con el rotulo: «Valores» es a la vez una seccion de Rentas y un
 * MODULO entero, y «Panel» es el rotulo de un submodulo en los diez. Lo que los
 * separa es el atributo: una hoja lleva `aria-current` —esta o no esta activa— y
 * una cabecera de modulo lleva `aria-expanded`.
 */
function submodulo(rotulo: string): HTMLElement {
  const hojas = within(arbol())
    .getAllByRole('button', { name: new RegExp(`^${rotulo}`) })
    .filter((boton) => boton.hasAttribute('aria-current'));
  const primera = hojas[0];
  if (hojas.length !== 1 || primera === undefined) {
    throw new Error(`Se esperaba un submodulo «${rotulo}» visible, y hay ${hojas.length}.`);
  }
  return primera;
}

const barraDePestanas = () => screen.getByRole('group', { name: 'Pestañas abiertas' });

/** Los rotulos de las pestanas abiertas, en su orden, con su asterisco. */
function pestanas(): string[] {
  return within(barraDePestanas())
    .queryAllByRole('button')
    .filter((boton) => boton.getAttribute('aria-label') === null)
    .map((boton) => boton.textContent ?? '');
}

/** El rotulo de la pestana activa. */
function activa(): string | null {
  const boton = within(barraDePestanas())
    .queryAllByRole('button')
    .find((candidato) => candidato.getAttribute('aria-current') === 'true');
  return boton?.textContent ?? null;
}

const titulo = () => screen.getByRole('heading', { level: 1 }).textContent;

describe('AC2 — la variante A, y solo esa', () => {
  it('el panel esta, y no hay ningun conmutador A/B/C', () => {
    render(<Marco />);

    expect(arbol()).toBeInTheDocument();
    expect(within(arbol()).queryByRole('button', { name: /^A$/ })).toBeNull();
    expect(within(arbol()).queryByRole('button', { name: /^B$/ })).toBeNull();
    expect(within(arbol()).queryByRole('button', { name: /^C$/ })).toBeNull();
    expect(
      within(arbol())
        .getAllByRole('button')
        .filter((boton) => boton.hasAttribute('aria-pressed')),
      'El conmutador era lo unico del panel con `aria-pressed`.',
    ).toEqual([]);
  });

  it('la cola de trabajo se muestra, y con sus tres filas', () => {
    render(<Marco />);

    expect(within(arbol()).getByText('Cola de trabajo')).toBeInTheDocument();
    expect(within(arbol()).getByRole('button', { name: /^Observados/ })).toBeInTheDocument();
    expect(within(arbol()).getByRole('button', { name: /^Sin conciliar/ })).toBeInTheDocument();
    expect(
      within(arbol()).getByRole('button', { name: /^Beneficios en trámite/ }),
    ).toBeInTheDocument();
  });

  it('los diez modulos estan, y Catastro y Tesorería no', () => {
    render(<Marco />);

    expect(within(arbol()).getAllByRole('button', { expanded: false })).toHaveLength(9);
    expect(within(arbol()).queryByRole('button', { name: /^Catastro/ })).toBeNull();
    expect(within(arbol()).queryByRole('button', { name: /^Tesorería/ })).toBeNull();
    expect(modulo('Valores')).toBeInTheDocument();
  });
});

describe('AC3 — abrir un submodulo', () => {
  it('lo anade a las pestanas, y no cierra el panel', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await usuario.click(modulo('Coactiva'));
    await usuario.click(submodulo('Expedientes'));

    expect(pestanas()).toEqual(['Panel', 'Expedientes']);
    expect(activa()).toBe('Expedientes');
    expect(arbol(), 'el panel es persistente: no se cierra al elegir').toBeInTheDocument();
  });

  it('si ya estaba abierto, solo lo activa', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await usuario.click(modulo('Coactiva'));
    await usuario.click(submodulo('Expedientes'));
    await usuario.click(within(barraDePestanas()).getByRole('button', { name: 'Panel' }));
    await usuario.click(submodulo('Expedientes'));

    expect(pestanas()).toEqual(['Panel', 'Expedientes']);
    expect(activa()).toBe('Expedientes');
  });
});

describe('AC4 — el enrutado por hash', () => {
  it('abrir una seccion escribe su slug con replaceState, nunca con pushState', async () => {
    const apilar = vi.spyOn(window.history, 'pushState');
    const usuario = userEvent.setup();
    render(<Marco />);

    expect(window.location.hash, 'al montar, el hash ya dice donde se esta').toBe('#panel');

    await usuario.click(submodulo('Determinación'));

    expect(window.location.hash).toBe('#determinacion');
    expect(
      apilar,
      'Cambiar de pestana no es navegar: con `pushState` haria falta un «atras» por cada\n' +
        'pestana abierta para salir de la aplicacion.',
    ).not.toHaveBeenCalled();
  });

  it('recargar sobre «#determinacion» reabre esa seccion, CON su pestana', () => {
    window.history.replaceState(null, '', '#determinacion');

    render(<Marco />);

    expect(titulo()).toBe('Determinación');
    expect(pestanas()).toEqual(['Panel', 'Determinación']);
    expect(activa()).toBe('Determinación');
  });

  it('un hash de una hoja ajena tambien la reabre', () => {
    window.history.replaceState(null, '', '#coa-exp');

    render(<Marco />);

    expect(titulo()).toBe('Expedientes');
    expect(activa()).toBe('Expedientes');
  });

  it('«hashchange» navega', () => {
    render(<Marco />);

    act(() => {
      window.history.replaceState(null, '', '#contribuyentes');
      window.dispatchEvent(new Event('hashchange'));
    });

    expect(titulo()).toBe('Contribuyentes');
    expect(activa()).toBe('Contribuyentes');
  });

  it('un hash que no abre nada no cambia nada', () => {
    render(<Marco />);

    act(() => {
      window.history.replaceState(null, '', '#lo-que-sea');
      window.dispatchEvent(new Event('hashchange'));
    });

    expect(titulo()).toBe('Panel de Rentas');
  });
});

describe('AC5 — el estado sin guardar', () => {
  const ensuciar = async (usuario: ReturnType<typeof userEvent.setup>) => {
    await usuario.type(screen.getByLabelText('Observación'), 'Se corrige el domicilio fiscal');
  };

  it('editar un campo marca la pestana activa con un asterisco', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    expect(pestanas()).toEqual(['Panel']);
    await ensuciar(usuario);

    expect(pestanas()).toEqual(['Panel *']);
  });

  it('cerrarla pregunta, y ofrece las TRES salidas', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await ensuciar(usuario);

    await usuario.click(
      screen.getByRole('button', { name: 'Cerrar Panel — tiene cambios sin guardar' }),
    );

    const dialogo = screen.getByRole('dialog', { name: 'Cerrar con cambios sin guardar' });
    expect(within(dialogo).getByRole('button', { name: 'Guardar y cerrar' })).toBeInTheDocument();
    expect(within(dialogo).getByRole('button', { name: 'Descartar y cerrar' })).toBeInTheDocument();
    expect(within(dialogo).getByRole('button', { name: 'Seguir editando' })).toBeInTheDocument();
    expect(pestanas(), 'preguntar no cierra').toEqual(['Panel *']);
  });

  it('«Seguir editando» deja la pestana, y sigue sucia', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await ensuciar(usuario);
    await usuario.click(
      screen.getByRole('button', { name: 'Cerrar Panel — tiene cambios sin guardar' }),
    );

    await usuario.click(screen.getByRole('button', { name: 'Seguir editando' }));

    expect(screen.queryByRole('dialog', { name: 'Cerrar con cambios sin guardar' })).toBeNull();
    expect(pestanas()).toEqual(['Panel *']);
  });

  it('«Descartar y cerrar» cierra, y no dice que guardo nada', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.click(modulo('Coactiva'));
    await usuario.click(submodulo('Costas y plazos'));
    await usuario.click(within(barraDePestanas()).getByRole('button', { name: 'Panel' }));
    await ensuciar(usuario);
    await usuario.click(
      screen.getByRole('button', { name: 'Cerrar Panel — tiene cambios sin guardar' }),
    );

    await usuario.click(screen.getByRole('button', { name: 'Descartar y cerrar' }));

    expect(pestanas()).toEqual(['Costas y plazos']);
    expect(screen.queryByText(/Cambios guardados/)).toBeNull();
  });

  it('«Guardar y cerrar» cierra y lo dice', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.click(modulo('Coactiva'));
    await usuario.click(submodulo('Costas y plazos'));
    await usuario.click(within(barraDePestanas()).getByRole('button', { name: 'Panel' }));
    await ensuciar(usuario);
    await usuario.click(
      screen.getByRole('button', { name: 'Cerrar Panel — tiene cambios sin guardar' }),
    );

    await usuario.click(screen.getByRole('button', { name: 'Guardar y cerrar' }));

    expect(pestanas()).toEqual(['Costas y plazos']);
    expect(screen.getByRole('status').textContent).toContain('Cambios guardados en Panel.');
  });

  it('cerrar una pestana LIMPIA no pregunta', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.click(modulo('Coactiva'));
    await usuario.click(submodulo('Costas y plazos'));

    await usuario.click(screen.getByRole('button', { name: 'Cerrar Costas y plazos' }));

    expect(screen.queryByRole('dialog', { name: 'Cerrar con cambios sin guardar' })).toBeNull();
    expect(pestanas()).toEqual(['Panel']);
  });

  it('el asterisco tambien sale en el arbol, donde se elige la seccion', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await ensuciar(usuario);

    expect(submodulo('Panel').textContent).toContain('*');
  });
});

describe('AC6 — cerrar la activa activa la vecina', () => {
  const abrirTres = async (usuario: ReturnType<typeof userEvent.setup>) => {
    await usuario.click(modulo('Coactiva'));
    await usuario.click(submodulo('Expedientes'));
    await usuario.click(submodulo('Costas y plazos'));
  };

  it('la de la izquierda cuando la hay', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await abrirTres(usuario);

    await usuario.click(screen.getByRole('button', { name: 'Cerrar Costas y plazos' }));

    expect(pestanas()).toEqual(['Panel', 'Expedientes']);
    expect(activa()).toBe('Expedientes');
  });

  it('la de la derecha cuando se cierra la primera', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await abrirTres(usuario);
    await usuario.click(within(barraDePestanas()).getByRole('button', { name: 'Panel' }));

    await usuario.click(screen.getByRole('button', { name: 'Cerrar Panel' }));

    expect(pestanas()).toEqual(['Expedientes', 'Costas y plazos']);
    expect(activa()).toBe('Expedientes');
  });

  it('cerrar la ultima deja el espacio vacio, y lo dice', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await usuario.click(screen.getByRole('button', { name: 'Cerrar Panel' }));

    expect(pestanas()).toEqual([]);
    expect(screen.queryByRole('heading', { level: 1 })).toBeNull();
    expect(screen.getByText('No hay ningún submódulo abierto')).toBeInTheDocument();
  });
});

describe('AC7 — el filtro del arbol', () => {
  const filtrar = async (usuario: ReturnType<typeof userEvent.setup>, texto: string) => {
    await usuario.type(screen.getByLabelText('Filtrar módulos y submódulos'), texto);
  };

  it('filtra modulos y submodulos, y el conteo dice cuantos casan', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await filtrar(usuario, 'papeleta');

    expect(within(arbol()).getByText('1 módulo · 1 submódulo')).toBeInTheDocument();
    expect(within(arbol()).getByRole('button', { name: /^Tránsito/ })).toBeInTheDocument();
    expect(within(arbol()).queryByRole('button', { name: /^Coactiva/ })).toBeNull();
  });

  it('un modulo que casa por su nombre ensena sus cuatro submodulos', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await filtrar(usuario, 'coactiva');

    expect(within(arbol()).getByText('1 módulo · 4 submódulos')).toBeInTheDocument();
    expect(within(arbol()).getByRole('button', { name: /^Cartera y medidas/ })).toBeInTheDocument();
  });

  it('sin coincidencias sale su mensaje', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await filtrar(usuario, 'zzz');

    expect(within(arbol()).getByText('Sin coincidencias')).toBeInTheDocument();
    expect(within(arbol()).getByText(/Ningún módulo ni submódulo se llama así/)).toBeInTheDocument();
  });

  it('quitar el filtro devuelve el arbol entero', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await filtrar(usuario, 'zzz');

    await usuario.click(screen.getByRole('button', { name: 'Quitar el filtro' }));

    expect(within(arbol()).queryByText('Sin coincidencias')).toBeNull();
    expect(within(arbol()).getAllByRole('button', { expanded: false })).toHaveLength(9);
  });
});

describe('AC8 — el teclado', () => {
  it('Ctrl+K abre la paleta y Ctrl+K la cierra', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await usuario.keyboard('{Control>}k{/Control}');
    expect(screen.getByRole('dialog', { name: 'Buscar' })).toBeInTheDocument();

    await usuario.keyboard('{Control>}k{/Control}');
    expect(screen.queryByRole('dialog', { name: 'Buscar' })).toBeNull();
  });

  it('Cmd+K hace lo mismo, que es el atajo del mismo gesto en otro teclado', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await usuario.keyboard('{Meta>}k{/Meta}');

    expect(screen.getByRole('dialog', { name: 'Buscar' })).toBeInTheDocument();
  });

  it('se opera con flechas y Enter', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.keyboard('{Control>}k{/Control}');

    await usuario.type(screen.getByLabelText('Buscar un destino'), 'cartera');
    const paleta = screen.getByRole('dialog', { name: 'Buscar' });
    expect(within(paleta).getByText('2 resultados')).toBeInTheDocument();

    // El primero es «Cartera y medidas», de Coactiva; el segundo, «Cartera y
    // lotes», de Valores. Una flecha abajo elige el segundo.
    await usuario.keyboard('{ArrowDown}');
    await usuario.keyboard('{Enter}');

    expect(screen.queryByRole('dialog', { name: 'Buscar' })).toBeNull();
    expect(activa()).toBe('Cartera y lotes');
  });

  // Sobre CUATRO resultados y acabando en el tercero, no en el primero. Con dos
  // resultados y acabando arriba, esta prueba pasaba tambien con un `Enter` que
  // abriera siempre el primero —o sea, con las flechas rotas—: se midio, y por
  // eso los casos son estos. `plazos` casa en Tránsito, Infracciones, Coactiva y
  // Autorizaciones, en ese orden, que es el del arbol.
  it('la flecha arriba retrocede, y no vuelve al primero', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.keyboard('{Control>}k{/Control}');
    await usuario.type(screen.getByLabelText('Buscar un destino'), 'plazos');
    expect(within(screen.getByRole('dialog', { name: 'Buscar' })).getByText('4 resultados'));

    await usuario.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{ArrowUp}');
    await usuario.keyboard('{Enter}');

    expect(activa()).toBe('Costas y plazos');
  });

  it('el indice se acota: ni por arriba ni por abajo se sale de la lista', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.keyboard('{Control>}k{/Control}');
    await usuario.type(screen.getByLabelText('Buscar un destino'), 'plazos');

    // Nueve abajo sobre cuatro resultados: se queda en el ultimo, no se sale ni
    // da la vuelta. Y tres arriba desde el primero, en el primero.
    await usuario.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}');
    await usuario.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}');
    await usuario.keyboard('{Enter}');
    expect(activa()).toBe('Trámites y plazos');

    await usuario.keyboard('{Control>}k{/Control}');
    await usuario.type(screen.getByLabelText('Buscar un destino'), 'plazos');
    await usuario.keyboard('{ArrowUp}{ArrowUp}{ArrowUp}');
    await usuario.keyboard('{Enter}');
    expect(activa()).toBe('Cuadros y plazos');
  });

  it('Escape cierra la paleta', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.keyboard('{Control>}k{/Control}');

    await usuario.keyboard('{Escape}');

    expect(screen.queryByRole('dialog', { name: 'Buscar' })).toBeNull();
  });

  it('Escape cierra el lanzador de modulos', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.click(screen.getByRole('button', { name: 'Ver todos los módulos' }));
    expect(screen.getByRole('dialog', { name: 'Módulos del sistema' })).toBeInTheDocument();

    await usuario.keyboard('{Escape}');

    expect(screen.queryByRole('dialog', { name: 'Módulos del sistema' })).toBeNull();
  });

  it('Escape cierra el menu de sesion', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.click(screen.getByRole('button', { name: 'Sesión de J. Cárdenas Vega' }));
    expect(screen.getByRole('menu', { name: 'Sesión' })).toBeInTheDocument();

    await usuario.keyboard('{Escape}');

    expect(screen.queryByRole('menu', { name: 'Sesión' })).toBeNull();
  });

  it('Escape cierra el dialogo de confirmacion, y no cierra la pestana', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.type(screen.getByLabelText('Observación'), 'Se corrige');
    await usuario.click(
      screen.getByRole('button', { name: 'Cerrar Panel — tiene cambios sin guardar' }),
    );

    await usuario.keyboard('{Escape}');

    expect(screen.queryByRole('dialog', { name: 'Cerrar con cambios sin guardar' })).toBeNull();
    expect(pestanas(), 'Escape es «seguir editando», no «descartar»').toEqual(['Panel *']);
  });
});

describe('AC9 — la pestana ajena', () => {
  it('un submodulo de otro modulo abre su ficha, y dice donde se disena', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await usuario.click(modulo('Valores'));
    await usuario.click(submodulo('Cartera y lotes'));

    expect(titulo()).toBe('Cartera y lotes');
    expect(
      screen.getByText(/está diseñada en el archivo de Valores/),
      'Sin ningun modulo ajeno en el arbol este caso no existe: es lo que el AC1 sostiene.',
    ).toBeInTheDocument();
    expect(screen.getByText('Valores · Emisión y notificación')).toBeInTheDocument();
  });

  it('se puede tener abierta a la vez que una propia, y volver a ella', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.click(modulo('Valores'));
    await usuario.click(submodulo('Cartera y lotes'));

    await usuario.click(within(barraDePestanas()).getByRole('button', { name: 'Panel' }));
    expect(titulo()).toBe('Panel de Rentas');

    await usuario.click(within(barraDePestanas()).getByRole('button', { name: 'Cartera y lotes' }));

    expect(titulo()).toBe('Cartera y lotes');
    expect(pestanas()).toEqual(['Panel', 'Cartera y lotes']);
  });

  it('una ficha ajena no ofrece el campo de observacion: aqui no se edita nada', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await usuario.click(modulo('Valores'));
    await usuario.click(submodulo('Cartera y lotes'));

    expect(screen.queryByLabelText('Observación')).toBeNull();
  });

  it('«Cerrar la pestaña» de la ficha la cierra', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.click(modulo('Valores'));
    await usuario.click(submodulo('Cartera y lotes'));

    await usuario.click(screen.getByRole('button', { name: 'Cerrar la pestaña' }));

    expect(pestanas()).toEqual(['Panel']);
  });
});

describe('el resto del marco que se porta', () => {
  it('el lanzador lista los diez modulos y abre el panel del que se elige', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);

    await usuario.click(screen.getByRole('button', { name: 'Ver todos los módulos' }));
    const lanzador = screen.getByRole('dialog', { name: 'Módulos del sistema' });
    expect(within(lanzador).getAllByRole('button')).toHaveLength(10);

    await usuario.click(within(lanzador).getByRole('button', { name: /Seguridad/ }));

    expect(activa()).toBe('Panel');
    expect(pestanas()).toEqual(['Panel', 'Panel']);
    expect(titulo(), 'la de Seguridad, no la de Rentas').toBe('Panel');
  });

  it('cambiar de ejercicio lo dice, y el titulo de Valores lo lleva dentro', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.click(submodulo('Valores'));
    expect(titulo()).toBe('Valores del ejercicio 2026');

    await usuario.selectOptions(screen.getByLabelText('Ejercicio de trabajo'), '2024');

    expect(titulo()).toBe('Valores del ejercicio 2024');
    expect(screen.getByRole('status').textContent).toContain('Ejercicio 2024');
  });

  it('el menu de sesion avisa de las pestanas con cambios sin guardar', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    await usuario.type(screen.getByLabelText('Observación'), 'Se corrige');

    await usuario.click(screen.getByRole('button', { name: 'Sesión de J. Cárdenas Vega' }));

    expect(
      within(screen.getByRole('menu', { name: 'Sesión' })).getByText(
        'Hay 1 pestaña con cambios sin guardar. Al cerrar sesión se pierden.',
      ),
    ).toBeInTheDocument();
  });

  it('el panel se puede ocultar y volver a mostrar', async () => {
    const usuario = userEvent.setup();
    render(<Marco />);
    const alternar = screen.getByRole('button', {
      name: 'Mostrar u ocultar las secciones de Rentas',
    });

    await usuario.click(alternar);
    expect(screen.queryByRole('complementary', { name: 'Módulos y submódulos' })).toBeNull();

    await usuario.click(alternar);
    expect(arbol()).toBeInTheDocument();
  });
});
