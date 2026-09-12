import { useState } from 'react';

import { BloqueDeLaPantalla } from './piezas/BloqueDeLaPantalla.tsx';
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
 * <h2>Que dibuja, y que NO (#90)</h2>
 *
 * Dibuja **el cuerpo**: los bloques. La cabecera —miga, titulo e instruccion— y las acciones del
 * pie las pone `@kamayuk/shell`, que es quien sabe donde esta uno y a donde puede volver. Aqui
 * hubo una copia de las dos hasta que el armazon llego (#89): se retiran, porque dos cabeceras
 * que se dibujan igual acaban divirgiendo y la que se queda vieja es la que alguien esta mirando.
 *
 * La INSTRUCCION sigue viniendo de la definicion y se le pasa al armazon desde el catalogo: es
 * dato de este sistema, no del marco.
 *
 * <h2>Lo que todavia NO hace</h2>
 *
 * No llama a la API: dibuja lo que la definicion dice, que son las cifras del artboard.
 */

export interface PantallaProps {
  readonly definicion: Definicion;
  /** Se avisa la primera vez que se toca un campo: es lo que marca la hoja como sucia. */
  readonly alEnsuciar?: () => void;
}

/** `bloque|campo` -> lo tecleado. Plano a proposito: una pantalla no anida mas. */
type Tecleado = Record<string, string | boolean>;

export function Pantalla({ definicion, alEnsuciar = () => {} }: PantallaProps) {
  const [tecleado, setTecleado] = useState<Tecleado>({});


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
    <div className="flex flex-col gap-[14px]">
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
    </div>
  );
}
