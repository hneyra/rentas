import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fijarToken } from '../api/identidad.ts';
import { desinstalarProxyDeDatos, instalarProxyDeDatos } from '../api/proxy.ts';
import { CORRIDA_MEDIDA, contestaLaInstalacion } from '../datos/backendMedido.ts';
import { sumarImportes } from '../dominio/aritmetica.ts';
import { Determinacion } from './Determinacion.tsx';

/**
 * La seccion «Determinación», montada y **pidiendo por HTTP**.
 *
 * El proxy de #4 esta instalado, asi que cada memoria llega por el mismo camino que va a llegar
 * el dia de la integracion: `solicitar()` compone la URL, el transporte contesta un `Response`
 * de verdad y la pantalla lo lee. Si el proxy dejara de enrutar
 * `POST /rentas/predial/calculo-individual`, esto se pondria rojo igual — que es lo que
 * distingue una prueba de pantalla de una prueba de una constante importada (AC8).
 *
 * <h2>Y desde I-4 una de las suyas la contesta el backend, no el proxy</h2>
 *
 * `GET /rentas/predial/corridas/ultima` entro en `YA_SERVIDAS`, y esta seccion la pide: encender
 * una ruta la enciende **para todas las pantallas que la usen**, no solo para la del issue que
 * la encendio. Asi que el «Predial — masivo» de aqui pasa a ensenar la corrida de la
 * instalacion, y las cifras del artboard —«62,418 cuentas», las cinco etapas, los 534
 * observados— dejan de ser lo que se ve. Lo que se ve esta abajo, y es lo que contesta la
 * municipalidad 9: **dos etapas, cero registros y `simulacion: true`**.
 */

/** El doble: contesta lo medido a lo que sale por `YA_SERVIDAS`, y 404 a lo demas. */
function backendMedido() {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const cuerpo = contestaLaInstalacion(String(entrada));
      return Promise.resolve(
        cuerpo === null ? new Response('{}', { status: 404 }) : Response.json(cuerpo),
      );
    }),
  );
  instalarProxyDeDatos();
}

beforeEach(() => {
  fijarToken('un-token-de-prueba');
  backendMedido();
});

afterEach(() => {
  desinstalarProxyDeDatos();
  vi.unstubAllGlobals();
  fijarToken(null);
});

/** Monta la seccion y espera a la primera cifra de la memoria del predial. */
async function montar() {
  const usuario = userEvent.setup();
  render(<Determinacion />);
  await screen.findByText('S/ 170,616.75');
  return usuario;
}

/** El boton de un tipo en la lista de la izquierda. */
const tipo = (titulo: string) =>
  screen.getByRole('button', { name: new RegExp(`^${titulo.replace(/[—.]/g, '.')}`) });

/** La fila cuya celda de texto dice eso. */
function fila(texto: string): HTMLElement {
  const celda = screen.getByText(texto);
  const tr = celda.closest('tr');
  if (tr === null) {
    throw new Error(`«${texto}» no esta dentro de ninguna fila.`);
  }
  return tr;
}

/**
 * La celda de DINERO de esa fila, ya formateada.
 *
 * Se busca por la clase que pinta `Importe` y no por el texto: la columna «Detalle» del tramo
 * escribe «S/ 80,250.00 del afecto», que tambien empieza por «S/» — un `getByText(/^S\//)`
 * casaba con las dos y elegia mal la mitad de las veces.
 */
function cifraDe(texto: string): string {
  const dinero = fila(texto).querySelector('.kr-importe__valor');
  if (dinero === null) {
    throw new Error(`La fila de «${texto}» no tiene ninguna celda de dinero.`);
  }
  return dinero.textContent ?? '';
}

/** `'S/ 587.44'` → `'587.44'`, para poder sumarlas como las suma el backend. */
const comoDecimal = (formateado: string) => formateado.replace('S/ ', '').replace(/,/g, '');

describe('AC1 — los seis tipos, con su conteo', () => {
  it('estan los seis, y el conteo sale de la respuesta y no del artboard', async () => {
    await montar();

    expect(tipo('Predial — individual').textContent).toContain('1 contribuyente');
    // De `etapas[0].registros` de la corrida. El artboard escribe «62,418 cuentas»; la
    // instalacion contesta **0**, porque su ultima corrida leyo cero registros. La cifra que se
    // ensena es la servida, y por eso cambio al encender la ruta.
    expect(CORRIDA_MEDIDA.etapas[0]?.registros).toBe(0);
    expect(await screen.findByText('0 cuentas')).toBeInTheDocument();
    expect(screen.queryByText('62,418 cuentas')).toBeNull();
    expect(await screen.findByText('4 servicios')).toBeInTheDocument();
  });

  it('elegir un tipo cambia el cuadro Y su nota explicativa', async () => {
    const usuario = await montar();
    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('Predial — individual');

    await usuario.click(tipo('Alcabala'));

    expect(screen.getByRole('heading', { level: 2 }).textContent).toBe('Alcabala');
    expect(
      screen.getByText(/El 3 % sobre el exceso de las primeras 10 UIT/),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Escala progresiva acumulativa/)).toBeNull();
  });

  it('el tipo elegido se marca, y solo uno', async () => {
    const usuario = await montar();
    await usuario.click(tipo('Patrimonio vehicular'));

    const marcados = screen
      .getAllByRole('button')
      .filter((boton) => boton.getAttribute('aria-current') === 'true');
    expect(marcados).toHaveLength(1);
    expect(marcados[0]?.textContent).toContain('Patrimonio vehicular');
  });

  it('el conteo de un tipo que no se ha abierto esta VACIO, y no trae la cifra del artboard', async () => {
    await montar();

    // «3 ejercicios» es la cifra del artboard para el vehicular, y su memoria todavia no se ha
    // pedido: escribirla aqui seria una cifra en la interfaz sin nada que la respalde.
    expect(screen.queryByText('3 ejercicios')).toBeNull();
    expect(tipo('Patrimonio vehicular').textContent).toBe('Patrimonio vehicular');
  });

  it('al abrirlo, su conteo aparece y sale de sus determinaciones', async () => {
    const usuario = await montar();
    await usuario.click(tipo('Patrimonio vehicular'));

    expect(await screen.findByText('3 ejercicios')).toBeInTheDocument();
  });
});

describe('AC2 — cada tipo pinta su tabla, con la alineacion que declara', () => {
  it('las columnas del predial individual son las cuatro de la memoria', async () => {
    await montar();

    expect(screen.getAllByRole('columnheader').map((th) => th.textContent)).toEqual([
      '',
      'Concepto',
      'Detalle',
      'S/',
    ]);
  });

  it('la columna numerica va marcada y las de texto no', async () => {
    await montar();

    const cabeceras = screen.getAllByRole('columnheader');
    expect(cabeceras[3]?.className).toContain('kr-tabla__th--cifra');
    expect(cabeceras[1]?.className).not.toContain('kr-tabla__th--cifra');
    // Y la celda de dinero tambien, que es la que lleva `tabular-nums` en el CSS.
    expect(within(fila('Impuesto insoluto anual')).getByText(/^S\//).closest('td')?.className).toBe(
      'kr-tabla__td--cifra',
    );
  });

  it('el masivo pinta SUS cinco columnas, que no son las de la memoria', async () => {
    const usuario = await montar();
    await usuario.click(tipo('Predial — masivo'));

    expect(screen.getAllByRole('columnheader').map((th) => th.textContent)).toEqual([
      'Etapa',
      'Registros',
      'Monto S/',
      'Observados',
      'Estado',
    ]);
  });
});

describe('AC3 — el predial individual ensena la escala progresiva completa', () => {
  it('valuo total, exonerado y afecto, con lo que la respuesta dice de cada uno', async () => {
    await montar();

    expect(cifraDe('Valuo total del conjunto')).toBe('S/ 170,616.75');
    expect(within(fila('Valuo total del conjunto')).getByText('2 predios, al 100 % y al 50 %'))
      .toBeInTheDocument();
    expect(cifraDe('Valuo exonerado')).toBe('S/ 0.00');
    expect(cifraDe('Valuo afecto')).toBe('S/ 151,406.75');
  });

  it('los TRES tramos, con su alicuota y su porcion gravada', async () => {
    await montar();

    expect(cifraDe('Tramo 1 — hasta 15 UIT · 0.2 %')).toBe('S/ 160.50');
    expect(cifraDe('Tramo 2 — de 15 a 60 UIT · 0.6 %')).toBe('S/ 426.94');
    expect(cifraDe('Tramo 3 — más de 60 UIT · 1.0 %')).toBe('S/ 0.00');
    expect(screen.getByText('S/ 80,250.00 del afecto')).toBeInTheDocument();
    expect(screen.getByText('S/ 71,156.75 del afecto')).toBeInTheDocument();
  });

  it('el insoluto, el derecho de emision y el total', async () => {
    await montar();

    expect(cifraDe('Impuesto insoluto anual')).toBe('S/ 587.44');
    expect(cifraDe('Derecho de emisión')).toBe('S/ 4.50');
    expect(cifraDe('Total a pagar')).toBe('S/ 591.94');
  });
});

describe('AC4 — el total cuadra con lo que se esta mostrando', () => {
  it('la suma de los tres tramos DIBUJADOS es el insoluto DIBUJADO', async () => {
    await montar();

    // No se compara contra 587.44: se suman las tres cifras que la pantalla acaba de pintar. Si
    // manana el proxy sirviera otros tramos, esta prueba seguiria comprobando lo mismo.
    const tramos = [
      cifraDe('Tramo 1 — hasta 15 UIT · 0.2 %'),
      cifraDe('Tramo 2 — de 15 a 60 UIT · 0.6 %'),
      cifraDe('Tramo 3 — más de 60 UIT · 1.0 %'),
    ].map(comoDecimal);

    expect(sumarImportes(tramos)).toBe(comoDecimal(cifraDe('Impuesto insoluto anual')));
  });

  it('el insoluto mas el derecho de emision, los dos DIBUJADOS, son el total DIBUJADO', async () => {
    await montar();

    expect(
      sumarImportes([
        comoDecimal(cifraDe('Impuesto insoluto anual')),
        comoDecimal(cifraDe('Derecho de emisión')),
      ]),
    ).toBe(comoDecimal(cifraDe('Total a pagar')));
  });

  it('mientras cuadre, la pantalla no avisa de nada', async () => {
    await montar();

    expect(screen.queryByText('El total no cuadra con las filas que se muestran')).toBeNull();
  });
});

describe('AC5 — el predial masivo ensena las etapas que la corrida trae', () => {
  it('las que contesto la instalacion, y no las cinco del artboard', async () => {
    const usuario = await montar();
    await usuario.click(tipo('Predial — masivo'));

    const etapas = await screen.findByRole('table', { name: 'Memoria de Predial — masivo' });
    // Dos filas de datos mas la de cabecera. El artboard dibujaba cinco.
    expect(within(etapas).getAllByRole('row')).toHaveLength(CORRIDA_MEDIDA.etapas.length + 1);
    expect(within(etapas).getByText('Padrón leído')).toBeInTheDocument();
    expect(within(etapas).getByText('Simulados')).toBeInTheDocument();
    expect(within(etapas).queryByText('Generación de cuponeras')).toBeNull();
  });

  it('una corrida SIMULADA lo dice: no emitio, asi que nadie debe nada por ella', async () => {
    // El contrato publica `simulacion` y hasta I-4 nadie lo leia. Un ensayo y una emision se
    // dibujaban igual, y la diferencia entre los dos es si esos contribuyentes tienen deuda.
    const usuario = await montar();
    await usuario.click(tipo('Predial — masivo'));

    expect(CORRIDA_MEDIDA.simulacion).toBe(true);
    expect(await screen.findByText('La última corrida fue una simulación')).toBeInTheDocument();
  });

  it('la etapa que no emite dinero deja el guion, y no un cero', async () => {
    const usuario = await montar();
    await usuario.click(tipo('Predial — masivo'));

    // «Padrón leído» publica `monto: ""`: cadena vacia y no «0.00». Leer un predio no mueve
    // dinero, y un cero diria que emitio cero soles.
    const lectura = fila('Padrón leído');
    expect(within(lectura).getByText('—')).toBeInTheDocument();
    expect(within(lectura).queryByText('S/ 0.00')).toBeNull();
  });
});

describe('AC7 — ninguna cifra sin su fecha, y la fecha es de la respuesta', () => {
  it('el cuadro dice a que fecha esta', async () => {
    await montar();

    expect(screen.getByText(/Cifras actualizadas al/).textContent).toContain('12/08/2026');
  });

  it('no hay ninguna fecha escrita a mano en la pantalla', async () => {
    await montar();

    // Si estuviera escrita, seguiria diciendo agosto el dia que el backend conteste septiembre.
    const { readFileSync } = await import('node:fs');
    const sinComentarios = (fuente: string) =>
      fuente.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    for (const archivo of ['src/secciones/Determinacion.tsx', 'src/secciones/determinacion.ts']) {
      const fuente = sinComentarios(readFileSync(archivo, 'utf8'));
      expect(fuente, archivo).not.toContain('2026-08-12');
      expect(fuente, archivo).not.toContain('12/08/2026');
    }
  });
});

describe('las dos determinaciones que llegan sin fecha no dibujan sus importes', () => {
  it('la alcabala lo dice, y nombra el campo que falta', async () => {
    const usuario = await montar();
    await usuario.click(tipo('Alcabala'));

    const aviso = await screen.findByText(
      'Esta determinación llega sin la fecha a la que está calculada',
    );
    expect(aviso).toBeInTheDocument();
    expect(screen.getByText(/no publica «fechaCalculo»/)).toBeInTheDocument();
    expect(screen.getByText(/publica baseImponible y montoDeterminado/)).toBeInTheDocument();
  });

  it('y su cuadro no pinta ni un importe, aunque la respuesta traiga dos', async () => {
    const usuario = await montar();
    await usuario.click(tipo('Alcabala'));

    const cuadro = await screen.findByRole('table', { name: 'Memoria de Alcabala' });
    expect(cuadro.querySelectorAll('.kr-importe__valor')).toHaveLength(0);
    expect(within(cuadro).getByText('Base imponible')).toBeInTheDocument();
    // Y no es que el cuadro este vacio: sus siete conceptos estan.
    expect(within(cuadro).getAllByRole('row')).toHaveLength(8);
  });

  it('las otras cuatro SI la publican, y por eso el aviso no sale en ellas', async () => {
    const usuario = await montar();
    expect(
      screen.queryByText('Esta determinación llega sin la fecha a la que está calculada'),
    ).toBeNull();

    await usuario.click(tipo('Arbitrios municipales'));
    expect(
      screen.queryByText('Esta determinación llega sin la fecha a la que está calculada'),
    ).toBeNull();
  });
});

describe('lo que el contrato no publica sale como guion, y no en blanco', () => {
  it('los arbitrios ensenan su servicio y su mensual, y no el criterio ni el anual', async () => {
    const usuario = await montar();
    await usuario.click(tipo('Arbitrios municipales'));

    const barrido = fila('Limpieza pública — barrido');
    expect(within(barrido).getByText('S/ 8.40')).toBeInTheDocument();
    expect(within(barrido).getAllByText('—')).toHaveLength(3);
  });

  it('el vehicular deriva su comprobacion del minimo comparando las dos cifras servidas', async () => {
    const usuario = await montar();
    await usuario.click(tipo('Patrimonio vehicular'));

    await screen.findByText('Comprobación: el impuesto lo supera');
    expect(cifraDe('Mínimo imponible')).toBe('S/ 80.25');
    expect(cifraDe('Base imponible')).toBe('S/ 112,800.00');
  });
});

describe('AC8 — los datos llegan por el proxy y no se importan', () => {
  it('ni la seccion ni su composicion importan la captura del artboard', async () => {
    const { readFileSync } = await import('node:fs');
    for (const archivo of [
      'src/secciones/Determinacion.tsx',
      'src/secciones/determinacion.ts',
      'src/secciones/Cuadro.tsx',
    ]) {
      expect(readFileSync(archivo, 'utf8'), archivo).not.toContain("from '../datos/prototipo.ts'");
    }
  });

  it('sin proxy la seccion no inventa cifras: dice que no pudo leerlas', async () => {
    desinstalarProxyDeDatos();
    try {
      render(<Determinacion />);
      expect(await screen.findByText(/No se pudo leer/)).toBeInTheDocument();
      expect(screen.queryByText('S/ 170,616.75')).toBeNull();
    } finally {
      instalarProxyDeDatos();
    }
  });
});
