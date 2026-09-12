import { seEscribe, tipoDe } from '@kamayuk/ui';
import { useState } from 'react';

import { AccionesAlPie, type Avisos } from './piezas/AccionesAlPie.tsx';
import { BloqueDeLaPantalla } from './piezas/BloqueDeLaPantalla.tsx';
import { CabeceraDePantalla } from './piezas/CabeceraDePantalla.tsx';
import type { Pantalla as Definicion } from './tipos.ts';

/**
 * **El interprete**: una definicion, dibujada.
 *
 * <h2>Uno, y no cuarenta componentes</h2>
 *
 * El artboard no dibuja cuarenta pantallas: dibuja una que interpreta una tabla, en un solo
 * `bloques(clave)`. Cuarenta componentes escritos a mano divergen a la tercera semana y nadie
 * puede decir cuales; cuarenta definiciones sobre un interprete no pueden, y es lo que hace
 * posible la guarda anti-deriva que #85 dejo puesta.
 *
 * <h2>Este archivo NO sabe que existe Rentas</h2>
 *
 * Ni un rotulo, ni un modulo, ni una ruta de su API: todo entra como dato. Lo vigila
 * `verificaciones/el-interprete-no-nombra-un-sistema.test.ts`, y no es celo: la forma
 * `[titulo, nota, campos, tabla]` es del PRODUCTO —`catastro` la usara igual— y este archivo esta
 * destinado a `@kamayuk/ui`. Se escribe aqui porque **las costuras de una abstraccion no se
 * conocen con un solo consumidor**: mover un archivo el dia que llegue el segundo cuesta una
 * tarde, y deshacer una abstraccion disenada contra un solo caso cuesta el doble.
 *
 * <h2>Que la pantalla se guarde o no lo decide el DATO</h2>
 *
 * Si algun campo se escribe, la pantalla ofrece limpiar y guardar; si no, exportar e imprimir. No
 * es un parametro: un parametro deja la puerta abierta a una pantalla de solo lectura con un
 * boton de guardar que no guarda nada.
 *
 * <h2>Lo que todavia NO hace</h2>
 *
 * No llama a la API —dibuja lo que la definicion dice, que son las cifras del artboard— y no esta
 * dentro del armazon. Las dos cosas van en sus propios issues, y hasta entonces la interfaz que
 * se sirve sigue siendo la V6.
 */

export interface PantallaProps {
  readonly definicion: Definicion;
  /** El modulo al que pertenece: va en la miga y delante de la instruccion. */
  readonly modulo: string;
  /** El rotulo de la hoja, que es el titulo de la pantalla. */
  readonly titulo: string;
  /** Que ES la pantalla. Lo dice el arbol, no la definicion. */
  readonly nota?: string;
  readonly alVolver?: () => void;
  /** Lo que se lee junto a las acciones del pie. Por omision, sin vocabulario de ningun sistema. */
  readonly avisos?: Avisos;
  /** Se avisa la primera vez que se toca un campo: es lo que marca la hoja como sucia. */
  readonly alEnsuciar?: () => void;
}

/** `bloque|campo` -> lo tecleado. Plano a proposito: una pantalla no anida mas. */
type Tecleado = Record<string, string | boolean>;

export function Pantalla({
  definicion,
  modulo,
  titulo,
  nota = '',
  alVolver = () => {},
  alEnsuciar = () => {},
  avisos,
}: PantallaProps) {
  const [tecleado, setTecleado] = useState<Tecleado>({});

  // Del dato, y no de una bandera: si algun campo de algun bloque no es de solo lectura, esta
  // pantalla se guarda.
  const guardable = definicion.bloques.some((b) =>
    b.campos.some((c) => seEscribe(tipoDe(c.tipo))),
  );

  const cambiar = (bloque: number, campo: number, valor: string | boolean) => {
    setTecleado((antes) => {
      // El aviso va UNA vez, en la primera tecla, y no en cada pulsacion: quien escucha esto
      // marca la hoja como sucia, y marcarla cuarenta veces seguidas es cuarenta renderizados.
      if (Object.keys(antes).length === 0) alEnsuciar();
      return { ...antes, [`${bloque}|${campo}`]: valor };
    });
  };

  const valoresDe = (bloque: number): Record<number, string | boolean> => {
    const salida: Record<number, string | boolean> = {};
    for (const [clave, valor] of Object.entries(tecleado)) {
      const [b, c] = clave.split('|');
      if (b === String(bloque) && c !== undefined) salida[Number(c)] = valor;
    }
    return salida;
  };

  return (
    <div className="flex-1 overflow-auto">
      <CabeceraDePantalla
        modulo={modulo}
        titulo={titulo}
        nota={nota}
        instruccion={definicion.instruccion}
      />
      <div className="px-[18px] pt-4 pb-6 flex flex-col gap-[14px] max-w-[1180px]">
        {definicion.bloques.map((bloque, i) => (
          <BloqueDeLaPantalla
            // El titulo del bloque: es unico dentro de cada pantalla en las cuarenta, y con el
            // indice, reordenar los bloques dejaria a React reusando el estado del anterior.
            key={bloque.titulo}
            bloque={bloque}
            valores={valoresDe(i)}
            alCambiar={(campo, valor) => cambiar(i, campo, valor)}
          />
        ))}
        <AccionesAlPie
          seEscribe={guardable}
          avisos={avisos}
          alVolver={alVolver}
          alActuar={(accion) => {
            if (accion === 'Imprimir') window.print();
            if (accion === 'Limpiar') setTecleado({});
          }}
        />
      </div>
    </div>
  );
}
