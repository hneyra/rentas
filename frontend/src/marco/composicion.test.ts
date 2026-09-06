import { describe, expect, it } from 'vitest';

import { ARBOL, CATALOGO_POR_CODIGO } from './arbol.ts';
import {
  DE_OTRO_SISTEMA,
  componerArbol,
  destinosOfrecidos,
  puedeCambiarElEjercicio,
} from './composicion.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
  sinElPrivilegioEspecial,
  sinLosAccesosDe,
} from './seguridadMedida.ts';

/**
 * AC1, AC2 y AC5 — el arbol se compone del backend, y lo que la cuenta no puede abrir no sale.
 *
 * Se prueba aqui y sin React porque **lo que decide no es una pantalla**: es una funcion de tres
 * respuestas HTTP a una lista de modulos. Montar el marco para comprobar que una cuenta sin
 * `coactiva_expedientes` no ve Coactiva costaria un `render` y diria menos — y sobre todo no
 * dejaria mirar los casos que la instalacion no tiene.
 */

/** El arbol tal como sale con la matriz entera de la instalacion. */
const completo = componerArbol(MODULOS_MEDIDOS, ACCESOS_MEDIDOS, PERMISOS_MEDIDOS);
const rotulos = (compuesto: ReturnType<typeof componerArbol>) =>
  compuesto.modulos.map((modulo) => modulo.rotulo);

describe('la captura sigue diciendo lo que estas pruebas creen que dice', () => {
  it('la instalacion publica DOCE modulos y 134 accesos, y la matriz trae 134 llaves', () => {
    // Sin esto, una captura que cambiara de forma daria listas vacias y todas las
    // comparaciones de abajo pasarian comparando nada con nada.
    expect(MODULOS_MEDIDOS).toHaveLength(12);
    expect(ACCESOS_MEDIDOS).toHaveLength(134);
    expect(Object.keys(PERMISOS_MEDIDOS)).toHaveLength(134);
  });

  it('cada acceso pertenece a un modulo que existe: `moduloId` es la llave del AC2', () => {
    const ids = new Set(MODULOS_MEDIDOS.map((modulo) => modulo.id));
    const huerfanos = ACCESOS_MEDIDOS.filter((acceso) => !ids.has(acceso.moduloId));

    // Un acceso huerfano seria un permiso que no cuelga de ninguna rama, y su modulo se
    // esconderia por no tener ninguno que la cuenta pueda leer.
    expect(huerfanos).toEqual([]);
  });

  it('y NINGUNO de los 40 destinos del artboard es un codigo de acceso del backend', () => {
    // Es la medida de por que el AC2 se aplica al MODULO y no al destino: `fis-actas`,
    // `coa-exp` y `tra-veh` son agrupaciones de diseno que solo existen en este repositorio, y
    // no hay ninguna operacion que diga con que acceso se abre cada una. Mapearlos a mano seria
    // inventar cuarenta decisiones de autorizacion y ponerlas en la interfaz, que es el sitio
    // donde no valen nada. El dia que el backend publique un menu por sesion, esta prueba se
    // pone roja y dice donde mirar.
    const codigos = new Set(ACCESOS_MEDIDOS.map((acceso) => acceso.codigo));
    const destinos = ARBOL.flatMap((modulo) => modulo.submodulos.map((hoja) => hoja.clave));

    expect(destinos.filter((destino) => codigos.has(destino))).toEqual([]);
  });
});

describe('AC1 — los codigos del catalogo son los que publica el backend', () => {
  it.each([...CATALOGO_POR_CODIGO.keys()])('«%s» es un modulo de la instalacion', (codigo) => {
    expect(MODULOS_MEDIDOS.map((modulo) => modulo.codigo)).toContain(codigo);
  });

  it.each([...CATALOGO_POR_CODIGO.entries()])(
    '«%s»: el nombre del backend es el rotulo que el artboard dibuja',
    (codigo, delCatalogo) => {
      // **Los diez coinciden byte a byte, y esa coincidencia es lo que hace invisible el
      // cambio**: el panel dice lo mismo que decia antes de I-3 aunque ahora lo diga el
      // backend. Se comprueba para que el dia que dejen de coincidir se vea aqui —un rotulo
      // distinto— y no como un modulo que desaparece.
      const delBackend = MODULOS_MEDIDOS.find((modulo) => modulo.codigo === codigo);

      expect(delBackend?.nombre).toBe(delCatalogo.rotulo);
    },
  );

  it('los dos que salen son CATASTRO y TESORERIA, y el backend los publica', () => {
    // Que estos dos ESTEN es la premisa de la resta: el dia que el backend deje de traerlos,
    // restarlos deja de significar algo.
    expect([...DE_OTRO_SISTEMA.keys()]).toEqual(['CATASTRO', 'TESORERIA']);
    for (const codigo of DE_OTRO_SISTEMA.keys()) {
      expect(MODULOS_MEDIDOS.map((modulo) => modulo.codigo)).toContain(codigo);
    }
  });
});

describe('AC1 — el arbol se compone de GET /seguridad/modulos', () => {
  it('salen DIEZ: los doce del backend menos los dos de otro sistema', () => {
    expect(completo.modulos).toHaveLength(10);
    expect(rotulos(completo)).not.toContain('Catastro');
    expect(rotulos(completo)).not.toContain('Tesorería');
    expect(completo.sinCatalogo).toEqual([]);
    expect(completo.sinPermiso).toEqual([]);
  });

  it('y en el orden del BACKEND, que no es el del artboard', () => {
    // El artboard pone Valores el ultimo de los diez; `GET /seguridad/modulos` publica VALORES
    // (id 95) antes que COACTIVA (id 101). **Esta linea es donde se nota que el orden ya no es
    // de este repositorio**, y es la razon por la que la paleta de `Marco.test.tsx` cambio de
    // primer resultado.
    expect(rotulos(completo)).toEqual([
      'Inicio',
      'Rentas · Registro',
      'Fiscalización',
      'Tránsito',
      'Infracciones administrativas',
      'Consultas',
      'Valores',
      'Coactiva',
      'Autorizaciones y licencias',
      'Seguridad',
    ]);
    expect(rotulos(completo)).not.toEqual(ARBOL.map((modulo) => modulo.rotulo));
  });

  it('si el backend deja de publicar uno de los diez, el arbol LO PIERDE', () => {
    // Es literalmente lo que el AC1 pide demostrar. Y con el se van sus cuatro destinos: no
    // queda una rama vacia bajo un titulo que ya no existe.
    const sinCoactiva = MODULOS_MEDIDOS.filter((modulo) => modulo.codigo !== 'COACTIVA');

    const compuesto = componerArbol(sinCoactiva, ACCESOS_MEDIDOS, PERMISOS_MEDIDOS);

    expect(compuesto.modulos).toHaveLength(9);
    expect(rotulos(compuesto)).not.toContain('Coactiva');
    expect(destinosOfrecidos(compuesto.modulos).has('coa-exp')).toBe(false);
  });

  it('un modulo marcado inactivo tampoco sale', () => {
    const conUnoDeBaja = MODULOS_MEDIDOS.map((modulo) =>
      modulo.codigo === 'CONSULTAS' ? { ...modulo, activo: false } : modulo,
    );

    expect(rotulos(componerArbol(conUnoDeBaja, ACCESOS_MEDIDOS, PERMISOS_MEDIDOS))).not.toContain(
      'Consultas',
    );
  });

  it('y uno que el catalogo no sabe dibujar se NOMBRA, en vez de desaparecer', () => {
    // Sin icono y sin destinos no se puede pintar, pero tragarselo en silencio perderia un
    // modulo nuevo sin que nada lo dijera. Se devuelve aparte para que la pantalla pueda
    // decirlo el dia que ocurra; hoy la lista esta vacia, y eso tambien se afirma arriba.
    const conUnoNuevo = [
      ...MODULOS_MEDIDOS,
      { id: 900, codigo: 'PARTICIPACION_VECINAL', nombre: 'Participación vecinal', orden: 0, activo: true },
    ];

    const compuesto = componerArbol(conUnoNuevo, ACCESOS_MEDIDOS, PERMISOS_MEDIDOS);

    expect(compuesto.sinCatalogo).toEqual(['PARTICIPACION_VECINAL']);
    expect(compuesto.modulos).toHaveLength(10);
  });

  it('el rotulo sale del backend: renombrar alli renombra el panel, sin tocar el catalogo', () => {
    const renombrado = MODULOS_MEDIDOS.map((modulo) =>
      modulo.codigo === 'TRANSITO' ? { ...modulo, nombre: 'Tránsito y transporte' } : modulo,
    );

    const compuesto = componerArbol(renombrado, ACCESOS_MEDIDOS, PERMISOS_MEDIDOS);

    // Y sigue estando: se empalma por `codigo`, no por nombre. Empalmar por nombre habria
    // hecho que un renombrado quitara el modulo del arbol **en silencio**.
    expect(rotulos(compuesto)).toContain('Tránsito y transporte');
    expect(compuesto.modulos).toHaveLength(10);
    expect(
      compuesto.modulos.find((modulo) => modulo.codigo === 'TRANSITO')?.submodulos,
    ).toHaveLength(4);
  });
});

describe('AC2 — lo que la cuenta no puede abrir no se ofrece, y lo que si, si', () => {
  it('sin ningun acceso de Coactiva, Coactiva no sale — y se dice por que no salio', () => {
    const compuesto = componerArbol(
      MODULOS_MEDIDOS,
      ACCESOS_MEDIDOS,
      sinLosAccesosDe('COACTIVA'),
    );

    expect(rotulos(compuesto)).not.toContain('Coactiva');
    expect(compuesto.sinPermiso).toEqual(['COACTIVA']);
    expect(compuesto.modulos).toHaveLength(9);
  });

  it('**la otra direccion**: los otros nueve siguen enteros, con sus 36 destinos', () => {
    // Es la mitad que el AC2 pide explicitamente y la que una implementacion demasiado celosa
    // rompe. Un arbol vacio pasaria la prueba de arriba y seria inservible.
    const compuesto = componerArbol(
      MODULOS_MEDIDOS,
      ACCESOS_MEDIDOS,
      sinLosAccesosDe('COACTIVA'),
    );

    expect(rotulos(compuesto)).toContain('Rentas · Registro');
    expect(destinosOfrecidos(compuesto.modulos).size).toBe(36);
    expect(compuesto.modulos.every((modulo) => modulo.submodulos.length === 4)).toBe(true);
  });

  it('con UN solo acceso legible de un modulo, el modulo sale ENTERO', () => {
    // La granularidad que el backend permite es el modulo. Ofrecer «los submodulos cuyo acceso
    // tiene» seria repartir cuarenta destinos entre 134 codigos a ojo, que es la invencion que
    // este archivo se niega a hacer.
    const soloUno = { contribuyentes: ['lectura'] };

    const compuesto = componerArbol(MODULOS_MEDIDOS, ACCESOS_MEDIDOS, soloUno);

    expect(rotulos(compuesto)).toEqual(['Rentas · Registro']);
    expect(compuesto.modulos[0]?.submodulos).toHaveLength(4);
  });

  it('tener la llave SIN `lectura` no basta: abrir una pantalla es leerla', () => {
    // La matriz publica la llave en cuanto hay CUALQUIER privilegio (ADR-0013), asi que una
    // cuenta que solo puede imprimir aparece en el objeto igual que una que puede entrar.
    // Ofrecerle la rama seria mandarla a una puerta que contesta 403.
    const soloImprime = { contribuyentes: ['impresion'] };

    expect(componerArbol(MODULOS_MEDIDOS, ACCESOS_MEDIDOS, soloImprime).modulos).toEqual([]);
  });

  it('sin permisos —la matriz vacia— no se ofrece nada: negacion por omision (ADR-0013)', () => {
    const compuesto = componerArbol(MODULOS_MEDIDOS, ACCESOS_MEDIDOS, {});

    expect(compuesto.modulos).toEqual([]);
    expect(compuesto.sinPermiso).toHaveLength(10);
  });

  it('y una matriz con una forma que no se entiende tampoco abre nada, en vez de reventar', () => {
    // Esto llega por la red y el contrato declara la operacion como `"objeto"` y nada mas. Con
    // un `.includes` a pelo, un valor que no fuera una lista lanzaba `TypeError` DENTRO de un
    // `useMemo`: no un aviso, la aplicacion entera en blanco. Medido al correr las pruebas.
    const rara = { contribuyentes: 'lectura' } as unknown as Record<string, readonly string[]>;

    expect(() => componerArbol(MODULOS_MEDIDOS, ACCESOS_MEDIDOS, rara)).not.toThrow();
    expect(componerArbol(MODULOS_MEDIDOS, ACCESOS_MEDIDOS, rara).modulos).toEqual([]);
  });
});

describe('AC5 — el privilegio del acto de cambiar el ejercicio', () => {
  it('la cuenta de la instalacion tiene `especial` sobre `cambiar_anio`', () => {
    expect(puedeCambiarElEjercicio(PERMISOS_MEDIDOS)).toBe(true);
  });

  it('sin `especial` no puede, aunque tenga la llave con los otros seis privilegios', () => {
    // **Es la distincion entera de esta funcion.** `PUT /seguridad/sesion/ejercicio` declara
    // `privilegio = Privilegio.ESPECIAL`, y mirar solo si la llave `cambiar_anio` existe
    // ofreceria el mando a una cuenta que recibira un 403: la matriz publica la llave en cuanto
    // hay cualquier privilegio.
    const sinEspecial = sinElPrivilegioEspecial();

    expect(sinEspecial['cambiar_anio']).toHaveLength(6);
    expect(puedeCambiarElEjercicio(sinEspecial)).toBe(false);
  });

  it('y sin la llave, tampoco', () => {
    expect(puedeCambiarElEjercicio(sinLosAccesosDe('SEGURIDAD'))).toBe(false);
    expect(puedeCambiarElEjercicio({})).toBe(false);
  });
});
