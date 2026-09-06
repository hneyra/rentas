import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';

import { desinstalarProxyDeDatos, instalarProxyDeDatos } from '../api/proxy.ts';
import { Panel } from './Panel.tsx';

/**
 * El panel del modulo, montado y **pidiendo los datos por HTTP** (AC1).
 *
 * El proxy de #4 esta instalado, asi que lo que se dibuja aqui llego por el mismo camino que va
 * a llegar el dia de la integracion: `solicitar()` compone la URL, el transporte contesta un
 * `Response` de verdad con su JSON y su codigo de estado, y la pantalla lo lee. Si el proxy
 * dejara de enrutar `/indicadores/recaudacion`, esto se pondria rojo igual — que es lo que
 * distingue una prueba de pantalla de una prueba de una constante importada.
 */

beforeAll(() => {
  instalarProxyDeDatos();
});

afterAll(() => {
  desinstalarProxyDeDatos();
});

/** Monta el panel y espera a que la primera cifra este en pantalla. */
async function montar() {
  const alIrAlPadron = vi.fn();
  const alAbrirContribuyente = vi.fn();
  render(<Panel alIrAlPadron={alIrAlPadron} alAbrirContribuyente={alAbrirContribuyente} />);
  await screen.findByText('Emitido del ejercicio');
  return { alIrAlPadron, alAbrirContribuyente };
}

/** La tarjeta que lleva ese titulo. */
const tarjeta = (titulo: string) => screen.getByRole('region', { name: titulo });

describe('AC1 — las cuatro cifras de cabecera', () => {
  it('llegan del indicador de recaudacion, con su valor y su nota', async () => {
    await montar();

    // «S/ 9.42 M» sale dos veces —tarjeta y barra del predial—, y las dos son del artboard.
    expect(screen.getAllByText('S/ 9.42 M')).toHaveLength(2);
    expect(screen.getByText('41.2 %')).toBeInTheDocument();
    const cifras = screen.getByRole('group', { name: 'Cifras del ejercicio' });
    expect(within(cifras).getByText('62,418')).toBeInTheDocument();
    expect(within(cifras).getByText('534')).toBeInTheDocument();
    expect(
      screen.getByText('Predial de 61,884 cuentas. Los 534 observados no están dentro.'),
    ).toBeInTheDocument();
  });

  it('la pastilla «+3.1» del artboard NO se dibuja', async () => {
    // No es un olvido: el KPI del contrato publica `label`, `value`, `note` y un importe con su
    // fecha, y ninguna variacion. Calcularla aqui seria publicar una cifra sin fecha —«+3.1»
    // respecto de que dia— que ningun backend sostiene. Lo comprueba tambien, contra el propio
    // archivo de formas, `verificaciones/secciones-del-artboard.test.ts`.
    await montar();

    expect(screen.queryByText('+3.1')).toBeNull();
  });
});

describe('AC1 — ninguna cifra sin su fecha (regla 9)', () => {
  it('el panel dice a que fecha esta, y es la de corte y no la de hoy', async () => {
    await montar();

    expect(screen.getByText(/Cifras actualizadas al/).textContent).toContain('31/08/2026');
  });

  it('la cabecera del avance lo repite como lo escribe el artboard: «al 31 de agosto»', async () => {
    await montar();

    expect(
      within(tarjeta('Recaudado sobre emitido, por tributo')).getByText('al 31 de agosto'),
    ).toBeInTheDocument();
  });

  it('la fecha sale de la RESPUESTA: no hay ninguna fecha escrita en la pantalla', async () => {
    await montar();

    // Si estuviera escrita a mano, seguiria diciendo «31 de agosto» el dia que el backend
    // conteste otra cosa. Se comprueba por el unico sitio donde se puede sin volver a pedirla:
    // el codigo fuente de la seccion no contiene la fecha que se acaba de ver.
    const { readFileSync } = await import('node:fs');
    // Sin los comentarios: el javadoc de la seccion SI nombra «al 31 de agosto», porque explica
    // de donde sale. Lo que no puede haber es un literal en el codigo que se dibuje.
    const fuente = readFileSync('src/secciones/Panel.tsx', 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');
    expect(fuente).not.toContain('31 de agosto');
    expect(fuente).not.toContain('2026-08-31');
  });
});

describe('AC1 — la cola de trabajo', () => {
  it('trae los tres frentes parados, con su cuenta y su motivo', async () => {
    await montar();
    const cola = tarjeta('Cola de trabajo');

    expect(within(cola).getByText('Contribuyentes sin emisión')).toBeInTheDocument();
    expect(within(cola).getByText('Predios que no generan deuda')).toBeInTheDocument();
    expect(within(cola).getByText('Beneficios por resolver')).toBeInTheDocument();
    expect(
      within(cola).getByText(
        'Tienen ficha catastral y no están en la cuenta corriente. Es la única cifra que se traduce en dinero que no entra.',
      ),
    ).toBeInTheDocument();
  });

  it('el total se DERIVA de los tres frentes y da los 1,134 del artboard', async () => {
    await montar();

    expect(within(tarjeta('Cola de trabajo')).getByText('1,134 pendientes')).toBeInTheDocument();
  });

  it('cada frente lleva al padron, y el de «Observado» con su chip puesto', async () => {
    const usuario = userEvent.setup();
    const { alIrAlPadron } = await montar();

    await usuario.click(
      within(tarjeta('Cola de trabajo')).getByRole('button', { name: /Contribuyentes sin emisión/ }),
    );

    expect(alIrAlPadron).toHaveBeenCalledWith('Observado');
  });
});

describe('AC1 — «Recaudado sobre emitido, por tributo»', () => {
  it('las cinco barras, con el porcentaje que el contrato publica', async () => {
    await montar();
    const avance = tarjeta('Recaudado sobre emitido, por tributo');

    expect(within(avance).getByText('Impuesto predial')).toBeInTheDocument();
    expect(within(avance).getByText('89 %')).toBeInTheDocument();
    expect(within(avance).getByText('87 %')).toBeInTheDocument();
    expect(within(avance).getByText('65 %')).toBeInTheDocument();
    expect(within(avance).getByText('100 %')).toBeInTheDocument();
    expect(within(avance).getByText('39 %')).toBeInTheDocument();
  });

  it('el pie del artboard va entero, con su «38 %» que no cuadra con la barra', async () => {
    // El artboard escribe 38 en la prosa y dibuja 39 en la barra (38.6 redondeado). Se copia
    // como esta: corregirlo seria inventar la correccion.
    await montar();

    expect(
      within(tarjeta('Recaudado sobre emitido, por tributo')).getByText(
        /Multas y papeletas al 38 % no es un problema de caja/,
      ),
    ).toBeInTheDocument();
  });
});

describe('AC1 — la actividad reciente y «Ver todo el padrón»', () => {
  it('trae los cuatro movimientos de la bitacora, con su contribuyente', async () => {
    await montar();
    const actividad = tarjeta('Actividad reciente');

    expect(within(actividad).getByText('00000003541')).toBeInTheDocument();
    expect(
      within(actividad).getByText('Predial 2026 determinado en S/ 591.94 · 4 cuotas'),
    ).toBeInTheDocument();
    expect(within(actividad).getByText('Determinado')).toBeInTheDocument();
    expect(within(actividad).getByText('Baja')).toBeInTheDocument();
  });

  it('el momento se ensena como fecha y hora, no como «hace 2 h»', async () => {
    // La bitacora publica un INSTANTE. Convertirlo en distancia contra el reloj del puesto
    // haria que la misma fila dijera «hace 2 h» a las nueve y «ayer» a medianoche, sin que
    // ningun dato hubiera cambiado.
    await montar();
    const actividad = tarjeta('Actividad reciente');

    expect(within(actividad).getByText('31/08/2026 21:59')).toBeInTheDocument();
    expect(within(actividad).queryByText('hace 2 h')).toBeNull();
  });

  it('una linea de actividad abre el expediente del contribuyente que tocó', async () => {
    const usuario = userEvent.setup();
    const { alAbrirContribuyente } = await montar();

    await usuario.click(
      within(tarjeta('Actividad reciente')).getByRole('button', { name: /00000152614/ }),
    );

    expect(alAbrirContribuyente).toHaveBeenCalledWith('00000152614');
  });

  it('«Ver todo el padrón» lleva al padron entero, sin chip', async () => {
    const usuario = userEvent.setup();
    const { alIrAlPadron } = await montar();

    await usuario.click(screen.getByRole('button', { name: 'Ver todo el padrón' }));

    expect(alIrAlPadron).toHaveBeenCalledWith();
  });
});

describe('cuando el backend no contesta, el panel lo dice', () => {
  it('un fallo de la operacion sale como aviso, y no como un panel vacio', async () => {
    desinstalarProxyDeDatos();
    const alIrAlPadron = vi.fn();
    render(<Panel alIrAlPadron={alIrAlPadron} alAbrirContribuyente={vi.fn()} />);

    expect(
      await screen.findByText('No se pudo leer el indicador de recaudación'),
    ).toBeInTheDocument();

    instalarProxyDeDatos();
  });
});
