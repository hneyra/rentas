import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import { desinstalarProxyDeDatos, instalarProxyDeDatos } from '../api/proxy.ts';
import { Marco } from '../marco/Marco.tsx';
import { MUNICIPALIDAD_MEDIDA, conEjercicio } from '../marco/sesionMedida.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
} from '../marco/seguridadMedida.ts';
import { componerArbol } from '../marco/composicion.ts';
import { Contribuyentes } from './Contribuyentes.tsx';
import { PADRON_AL_EMPEZAR, type EstadoDelPadron } from './estadoDelPadron.ts';

/**
 * El padron y el expediente, montados y **pidiendo los datos por HTTP** (AC2 a AC9).
 *
 * El proxy de #4 esta instalado: la lista que se ve aqui llego por `GET /rentas/contribuyentes`,
 * el estado de coactiva por `GET /coactiva/deudas` y los observados por la ultima corrida. Si el
 * proxy dejara de enrutar cualquiera de las tres, esto se pondria rojo — que es lo que separa
 * esta prueba de una que leyera `PREDIOS` de un `import`.
 *
 * El estado de la seccion vive **fuera** de ella (lo guarda el marco, para que sobreviva a
 * cambiar de pestana), asi que aqui se monta con un contenedor minimo que hace lo mismo.
 */

beforeAll(() => {
  instalarProxyDeDatos();
});

afterAll(() => {
  desinstalarProxyDeDatos();
});

afterEach(() => {
  window.history.replaceState(null, '', '/');
});

/** Ver `marco/sesionMedida.ts`: el marco no se monta sin decir quien esta dentro (I-1). */
const IDENTIDAD = {
  sesion: conEjercicio(2026),
  municipalidad: MUNICIPALIDAD_MEDIDA,
  // El arbol compuesto de la captura de la instalacion: diez modulos, los mismos que estas
  // pruebas daban por hecho cuando `ARBOL` era la navegacion entera (I-3).
  arbol: componerArbol(MODULOS_MEDIDOS, ACCESOS_MEDIDOS, PERMISOS_MEDIDOS).modulos,
  permisos: PERMISOS_MEDIDOS,
  alCambiarEjercicio: () => Promise.resolve(2026),
  alSalir: vi.fn(),
};

const ensuciada = vi.fn();
const avisada = vi.fn();

/** El contenedor que hace lo que hace el marco: guardar el estado de la seccion. */
function Contenedor({ inicial }: { readonly inicial?: Partial<EstadoDelPadron> }) {
  const [estado, fijar] = useState<EstadoDelPadron>({ ...PADRON_AL_EMPEZAR, ...inicial });
  return (
    <Contribuyentes
      estado={estado}
      alCambiar={(cambio) => {
        fijar((actual) => ({ ...actual, ...cambio }));
      }}
      alEnsuciar={ensuciada}
      alAvisar={avisada}
    />
  );
}

/** Monta el padron y espera a que la lista este servida. */
async function montar(inicial?: Partial<EstadoDelPadron>) {
  const usuario = userEvent.setup();
  render(<Contenedor inicial={inicial} />);
  await screen.findByText('Suc. Rufina Medina Medina');
  return usuario;
}

/** Los nombres de los contribuyentes que se ven, en su orden. */
function listados(): string[] {
  return screen
    .getAllByRole('button')
    .filter((boton) => boton.className.includes('kr-padron__fila'))
    .map((boton) => boton.querySelector('.kr-padron__nombre')?.textContent ?? '');
}

const buscador = () => screen.getByRole('textbox', { name: 'Buscar en el padrón' });
const chip = (rotulo: string) => screen.getByRole('button', { name: rotulo, pressed: false });

describe('AC2 — el padron llega del proxy y responde al buscador', () => {
  it('los CINCO contribuyentes del artboard, y su conteo', async () => {
    await montar();

    expect(listados()).toEqual([
      'Suc. Rufina Medina Medina',
      'Castillo Pascuala, María Elena',
      'Díaz Madrid, Julio César',
      'Noblecilla Arismendiz S.A.C.',
      'Valdez Ríos, Oliver Fabián',
    ]);
    expect(screen.getByText('5 de 5')).toBeInTheDocument();
  });

  it('cada fila lleva su documento y su tipo de persona, como el artboard', async () => {
    await montar();

    expect(screen.getByText('DNI 03593174 · Sucesión indivisa')).toBeInTheDocument();
    expect(screen.getByText('RUC 20525118447 · Persona jurídica')).toBeInTheDocument();
  });

  it('escribir en el buscador recorta la lista', async () => {
    const usuario = await montar();

    await usuario.type(buscador(), 'díaz');

    expect(listados()).toEqual(['Díaz Madrid, Julio César']);
    expect(screen.getByText('1 de 5')).toBeInTheDocument();
  });

  it('tambien busca por documento y por codigo', async () => {
    const usuario = await montar();

    await usuario.type(buscador(), '20525118447');
    expect(listados()).toEqual(['Noblecilla Arismendiz S.A.C.']);

    await usuario.clear(buscador());
    await usuario.type(buscador(), '152614');
    expect(listados()).toEqual(['Valdez Ríos, Oliver Fabián']);
  });

  it('el aspa limpia la busqueda y devuelve la lista entera', async () => {
    const usuario = await montar();
    await usuario.type(buscador(), 'díaz');

    await usuario.click(screen.getByRole('button', { name: 'Limpiar la búsqueda' }));

    expect(listados()).toHaveLength(5);
  });
});

describe('AC2 — los chips filtran por lo que el contrato SI publica', () => {
  it('«En coactiva» sale de `GET /coactiva/deudas`, con su importe y su fecha', async () => {
    const usuario = await montar();

    await usuario.click(chip('En coactiva'));

    expect(listados()).toEqual(['Díaz Madrid, Julio César']);
    // Es el unico importe de la lista, y viaja con su fecha (regla 9): el contrato lo publica
    // con `aLaFecha`, no suelto.
    expect(screen.getByText('S/ 9,412.15')).toBeInTheDocument();
  });

  it('«Observado» sale de los observados de la ultima corrida', async () => {
    const usuario = await montar();

    await usuario.click(chip('Observado'));

    expect(listados()).toEqual(['Noblecilla Arismendiz S.A.C.']);
  });

  it('«Con deuda» sale VACIO, y la pantalla dice por que', async () => {
    // **Es el hallazgo de este trabajo.** `GET /rentas/contribuyentes` publica identidad y nada
    // mas: ni el estado de cobranza ni la deuda, que son las dos cosas sobre las que el artboard
    // construye la fila. Recorridas las 181 operaciones, ninguna las publica por contribuyente
    // en una lista. El chip existe, filtra, y sale vacio DICIENDOLO — un chip que devolviera
    // resultados a ojo diria a la ventanilla quien debe sin que ningun sistema lo sostenga.
    const usuario = await montar();

    await usuario.click(chip('Con deuda'));

    expect(listados()).toEqual([]);
    expect(screen.getByText('Ningún contribuyente coincide')).toBeInTheDocument();
    expect(
      screen.getByText(/ninguna operación de este backend publica la deuda del padrón/),
    ).toBeInTheDocument();
  });

  it('las filas que nadie clasifica se ensenan con lo que el padron publica de ellas', async () => {
    await montar();
    // El estado de coactiva y el de los observados llegan en otras dos peticiones —y la de los
    // observados, encadenada a la de la ultima corrida—: hasta que las tres estan, la lista es
    // correcta pero incompleta. Se espera a la ultima en llegar, **y por su insignia**: un
    // `findByText('Observado')` a secas casa tambien con el CHIP, que esta desde el primer
    // fotograma, asi que unas veces resolveria antes de tiempo y otras se quejaria de que hay
    // dos. Esta prueba fallo asi una vez antes de acotarla.
    await screen.findByText('Observado', { selector: '.kr-insignia' });

    // «Activo» sale de `activo`, que es lo unico que la operacion del padron publica del estado
    // de un contribuyente. **No es «Al día»**: nadie ha dicho que lo esten, y el artboard SI lo
    // decia de tres de los cinco. Son los tres que no estan ni en coactiva ni observados.
    const insignias = screen
      .getAllByText(/./, { selector: '.kr-insignia' })
      .map((marca) => marca.textContent);
    expect(insignias.filter((estado) => estado === 'Activo')).toHaveLength(3);
    // Los dos estados de cobranza que el artboard SI dibujaba no aparecen en ninguna insignia:
    // «Con deuda» sigue estando como CHIP, que es otra cosa.
    expect(insignias).not.toContain('Al día');
    expect(insignias).not.toContain('Con deuda');
  });
});

describe('AC2 — el orden', () => {
  const selector = () => screen.getByRole('combobox', { name: 'Ordenar la lista' });

  it('«Nombre» ordena en castellano', async () => {
    const usuario = await montar();

    await usuario.selectOptions(selector(), 'Nombre');

    expect(listados()[0]).toBe('Castillo Pascuala, María Elena');
    expect(listados()[4]).toBe('Valdez Ríos, Oliver Fabián');
  });

  it('«Deuda» pone delante al unico del que se publica una', async () => {
    const usuario = await montar();

    await usuario.selectOptions(selector(), 'Deuda');

    expect(listados()[0]).toBe('Díaz Madrid, Julio César');
  });

  it('«Código» devuelve el orden en que llego el padron', async () => {
    const usuario = await montar();
    await usuario.selectOptions(selector(), 'Nombre');

    await usuario.selectOptions(selector(), 'Código');

    expect(listados()[0]).toBe('Suc. Rufina Medina Medina');
  });
});

describe('AC3 — sin coincidencias sale su vacio, y no es mudo', () => {
  it('el texto del artboard, entero', async () => {
    const usuario = await montar();

    await usuario.type(buscador(), 'Zzzz');

    expect(screen.getByText('Ningún contribuyente coincide')).toBeInTheDocument();
    expect(
      screen.getByText(
        'Puede estar con el código antiguo o con otro documento. Si viene a declarar por primera vez, créelo aquí mismo.',
      ),
    ).toBeInTheDocument();
  });

  it('y con el boton que resuelve el caso que lo provoca', async () => {
    const usuario = await montar();
    await usuario.type(buscador(), 'Zzzz');

    // Hay dos botones con ese rotulo —el del vacio de la lista y el del vacio de la derecha—, y
    // los dos hacen lo mismo. El de este AC es el que sale DENTRO del vacio de la busqueda.
    const vacio = screen.getByText('Ningún contribuyente coincide').closest('.kr-aviso');
    await usuario.click(within(vacio as HTMLElement).getByRole('button', { name: 'Nuevo contribuyente' }));

    expect(screen.getByText('Contribuyente nuevo')).toBeInTheDocument();
  });
});

describe('AC5 — el estado vacio de la derecha', () => {
  it('sin nadie elegido, dice que elija y explica que el expediente se abre al lado', async () => {
    await montar();

    expect(screen.getByText('Elija un contribuyente de la lista')).toBeInTheDocument();
    expect(
      screen.getByText(
        'El expediente se abre aquí al lado, sin salir de la lista. También puede crear un contribuyente nuevo.',
      ),
    ).toBeInTheDocument();
  });
});

describe('AC4 — el expediente se abre al lado, sin salir de la lista', () => {
  it('elegir un contribuyente abre su expediente con las SEIS secciones', async () => {
    const usuario = await montar();

    await usuario.click(screen.getByRole('button', { name: /Suc. Rufina Medina Medina/ }));

    expect(
      screen.getAllByRole('tab').map((pestana) => pestana.textContent),
    ).toEqual([
      'Identificación',
      'Domicilio fiscal',
      'Predios y vehículos',
      'Beneficios',
      'Cuenta corriente',
      'Observaciones',
    ]);
    // Y la lista sigue ahi: no se navego a ninguna parte.
    expect(listados()).toHaveLength(5);
  });

  it('lo que la ficha publica llega lleno, y lo que publica compuesto queda vacio', async () => {
    const usuario = await montar();

    await usuario.click(screen.getByRole('button', { name: /Suc. Rufina Medina Medina/ }));
    await screen.findByDisplayValue('Sucesión indivisa');

    // Publicado: tipo de persona, fecha de nacimiento, estado civil y calificacion.
    expect(screen.getByLabelText('Fecha de nacimiento')).toHaveValue('1948-08-30');
    expect(screen.getByLabelText('Estado civil')).toHaveValue('Viudo(a)');
    // Compuesto y no partible: `nombreRazonSocial` es «Suc. Rufina Medina Medina», y de ahi no
    // sale cual de las dos «Medina» es el apellido paterno. Adivinarlo escribiria en el
    // expediente un apellido que nadie declaro.
    expect(screen.getByLabelText('Apellido paterno')).toHaveValue('');
  });

  it('elegir OTRO contribuyente cambia el expediente sin navegar fuera', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: /Suc. Rufina Medina Medina/ }));
    await screen.findByText('00000025673', { selector: '.kr-ficha__codigo' });

    await usuario.click(screen.getByRole('button', { name: /Noblecilla Arismendiz/ }));

    // La cabecera del expediente cambia entera: codigo, nombre, documento y estado.
    expect(
      await screen.findByText('00000006551', { selector: '.kr-ficha__codigo' }),
    ).toBeInTheDocument();
    expect(
      screen.getByText('RUC 20525118447 · Persona jurídica', {
        selector: '.kr-ficha__contexto',
      }),
    ).toBeInTheDocument();
    // Y la lista sigue ahi, con los cinco: no se navego a ninguna parte.
    expect(listados()).toHaveLength(5);
    // **Lo que NO cambia son los campos**, y es del proxy y no de la pantalla: el proxy de #4
    // no filtra por el parametro de la ruta a proposito (su AC8), asi que la ficha que contesta
    // es siempre la del expediente capturado. El dia que el backend conteste de verdad, esta
    // pantalla ya pide `/rentas/contribuyentes/{id}/ficha` con el id de la fila elegida.
    expect(screen.getByLabelText('Tipo de persona')).toHaveValue('Sucesión indivisa');
  });

  it('«Cuenta corriente» trae la deuda por concepto, y cada cifra con su fecha', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: /Suc. Rufina Medina Medina/ }));
    await screen.findByDisplayValue('Sucesión indivisa');

    await usuario.click(screen.getByRole('tab', { name: 'Cuenta corriente' }));

    const tabla = await screen.findByRole('table');
    // Dos filas de predial: la de 2026 y la de 2024, como el artboard.
    expect(within(tabla).getAllByText('Impuesto predial')).toHaveLength(2);
    expect(within(tabla).getByText('S/ 2,055.04')).toBeInTheDocument();
    // «3 y 4» y «1 a 8» se derivan de `periodoDesde`/`periodoHasta`, como el artboard.
    expect(within(tabla).getByText('3 y 4')).toBeInTheDocument();
    expect(within(tabla).getByText('1 a 8')).toBeInTheDocument();
  });

  it('«Predios y vehículos» trae los predios, y el autovaluo NO lo publica catastro', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: /Suc. Rufina Medina Medina/ }));
    await screen.findByDisplayValue('Sucesión indivisa');

    await usuario.click(screen.getByRole('tab', { name: 'Predios y vehículos' }));

    const tabla = await screen.findByRole('table');
    expect(within(tabla).getByText('02-014-D-14-01')).toBeInTheDocument();
    expect(within(tabla).getByText('Calle Santa Rosa 116')).toBeInTheDocument();
    // El autovaluo del predio lo calcula catastro y llega sellado (ADR-0024): `GET
    // /rentas/predios` no lo publica, y la celda lo dice con un guion en vez de suponerlo.
    expect(within(tabla).getAllByText('—')).toHaveLength(2);
  });
});

describe('AC6 — el alta guiada y sus secciones', () => {
  it('empieza en la primera, y «Anterior» esta deshabilitado', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    // El nombre accesible lleva la cuenta de obligatorios pendientes al lado del rotulo.
    expect(screen.getByRole('tab', { name: /^Identificación/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    // `aria-disabled` y no `disabled`: sigue en el recorrido del tabulador, y pulsarlo no
    // retrocede de la primera.
    expect(screen.getByRole('button', { name: 'Anterior' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  it('«Continuar» avanza y «Anterior» retrocede', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.click(screen.getByRole('button', { name: 'Continuar' }));
    expect(screen.getByRole('tab', { name: /^Domicilio fiscal/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );

    await usuario.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(screen.getByRole('tab', { name: /^Identificación/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );

    // Y pulsarlo otra vez en la primera no retrocede a ninguna parte.
    await usuario.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(screen.getByRole('tab', { name: /^Identificación/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );
  });

  it('la ULTIMA seccion es «Lo que se va a registrar», ANTES de confirmar', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    // Antes de la ultima no esta: el resumen es lo que se lee justo antes de crear.
    expect(screen.queryByText('Lo que se va a registrar')).toBeNull();

    await usuario.click(screen.getByRole('tab', { name: /^Observaciones/ }));

    expect(screen.getByText('Lo que se va a registrar')).toBeInTheDocument();
    expect(
      screen.getByText(
        'Una ficha registrada entra en el padrón y desde ese momento el predio genera obligación predial.',
      ),
    ).toBeInTheDocument();
    expect(screen.getByText('El alta queda en la bitácora')).toBeInTheDocument();
    // Y el boton de confirmar esta DESPUES del resumen, no antes.
    expect(screen.getByRole('button', { name: 'Crear el contribuyente' })).toBeInTheDocument();
  });
});

describe('AC7 — la longitud del documento decide, y bloquea el avance', () => {
  const numero = () => screen.getByRole('textbox', { name: 'Número' });

  it('un DNI incompleto se cuenta en pantalla y no deja crear', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(numero(), '0359317');

    expect(screen.getByText('7 de 8 dígitos')).toBeInTheDocument();
    expect(screen.getByText(/El DNI tiene 8 dígitos/)).toBeInTheDocument();

    await usuario.click(screen.getByRole('tab', { name: /^Observaciones/ }));
    expect(screen.getByRole('button', { name: 'Crear el contribuyente' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    expect(screen.getByText(/No se puede crear todavía/)).toBeInTheDocument();
  });

  it('el DNI completo vale, y el mismo numero como RUC no', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    // Un documento que no esta en el padron: «03593174» es el de Rufina y saldria por duplicado.
    await usuario.type(numero(), '99999999');
    expect(screen.getByText('Documento válido')).toBeInTheDocument();

    await usuario.selectOptions(screen.getByRole('combobox', { name: 'Tipo' }), 'RUC');
    // Cambiar de tipo vacia el numero: ocho digitos tecleados como DNI no son los ocho
    // primeros de un RUC, son otro documento a medio escribir.
    expect(screen.getByText('0 de 11 dígitos')).toBeInTheDocument();

    await usuario.type(numero(), '20525118440');
    expect(screen.getByText('Documento válido')).toBeInTheDocument();
  });

  it('el carnet de extranjeria son DOCE, y once no bastan', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.selectOptions(
      screen.getByRole('combobox', { name: 'Tipo' }),
      'Carnet de extranjería',
    );
    await usuario.type(numero(), '00123456789');

    expect(screen.getByText('11 de 12 dígitos')).toBeInTheDocument();
  });

  it('las letras no entran, y el numero no pasa de su longitud', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(numero(), '03a59b31c74999');

    expect(numero()).toHaveValue('03593174');
  });
});

describe('AC8 — el documento repetido se rechaza, y dice de quien es', () => {
  it('el aviso nombra al contribuyente que ya lo tiene, con su codigo', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(screen.getByRole('textbox', { name: 'Número' }), '44218937');

    expect(screen.getByText('Documento ya registrado')).toBeInTheDocument();
    expect(
      screen.getByText(
        'Ese documento ya está en el padrón, a nombre de Castillo Pascuala, María Elena (00000003541). Dos códigos para la misma persona parten su deuda en dos cuentas que nadie cruza: abra el contribuyente que ya existe.',
      ),
    ).toBeInTheDocument();
  });

  it('el nombre sale del PADRON servido, no de una constante', async () => {
    // Es la diferencia que importa: la comprobacion no es contra `DOC_EN_USO`, es contra el
    // padron que el proxy contesto. El dia que ese documento sea de otra persona, el aviso dice
    // la otra — sin tocar esta pantalla.
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(screen.getByRole('textbox', { name: 'Número' }), '02718844');

    expect(screen.getByText(/a nombre de Díaz Madrid, Julio César \(00000006550\)/)).toBeInTheDocument();
  });

  it('un documento repetido bloquea el alta, aunque este completo', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));
    await usuario.type(screen.getByRole('textbox', { name: 'Número' }), '44218937');

    await usuario.click(screen.getByRole('tab', { name: /^Observaciones/ }));

    expect(screen.getByRole('button', { name: 'Crear el contribuyente' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
    // El motivo sale dos veces, y las dos hacen falta: en el veredicto del resumen y en la nota
    // del pie, que es la que se lee sin desplazarse hasta el resumen.
    expect(
      screen.getAllByText(/Ese documento ya está registrado a nombre de otro contribuyente\./),
    ).toHaveLength(2);
  });

  it('un documento libre NO bloquea por duplicado', async () => {
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(screen.getByRole('textbox', { name: 'Número' }), '99999999');

    expect(screen.queryByText('Documento ya registrado')).toBeNull();
    expect(screen.getByText('Documento válido')).toBeInTheDocument();
  });
});

describe('AC9 — escribir en el alta marca sucia la pestana, y cerrar pregunta', () => {
  it('teclear un campo del alta avisa al marco', async () => {
    ensuciada.mockClear();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(screen.getByLabelText('Nombres'), 'Rosa');

    expect(ensuciada).toHaveBeenCalled();
  });

  it('montado en el marco, pone el asterisco y cerrar pregunta', async () => {
    const usuario = userEvent.setup();
    render(<Marco {...IDENTIDAD} />);
    await usuario.click(
      within(screen.getByRole('complementary', { name: 'Módulos y submódulos' })).getByRole(
        'button',
        { name: /^Contribuyentes/ },
      ),
    );
    await screen.findByText('Suc. Rufina Medina Medina');

    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));
    await usuario.type(screen.getByLabelText('Nombres'), 'Rosa');

    const pestanas = screen.getByRole('group', { name: 'Pestañas abiertas' });
    expect(within(pestanas).getByRole('button', { name: /Contribuyentes \*/ })).toBeInTheDocument();

    await usuario.click(
      screen.getByRole('button', { name: 'Cerrar Contribuyentes — tiene cambios sin guardar' }),
    );

    expect(
      screen.getByRole('dialog', { name: 'Cerrar con cambios sin guardar' }),
    ).toBeInTheDocument();
  });

  it('lo tecleado sobrevive a irse al panel y volver', async () => {
    // El estado de la seccion lo guarda el marco a proposito: si viviera dentro, volver
    // encontraria el formulario en blanco **con el asterisco puesto**, y el dialogo preguntaria
    // por unos cambios que ya no existen.
    const usuario = userEvent.setup();
    render(<Marco {...IDENTIDAD} />);
    const arbol = screen.getByRole('complementary', { name: 'Módulos y submódulos' });
    await usuario.click(within(arbol).getByRole('button', { name: /^Contribuyentes/ }));
    await screen.findByText('Suc. Rufina Medina Medina');
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));
    await usuario.type(screen.getByLabelText('Nombres'), 'Rosa');

    const pestanas = screen.getByRole('group', { name: 'Pestañas abiertas' });
    await usuario.click(within(pestanas).getByRole('button', { name: /^Panel/ }));
    await usuario.click(within(pestanas).getByRole('button', { name: /^Contribuyentes/ }));

    expect(await screen.findByLabelText('Nombres')).toHaveValue('Rosa');
  });
});
