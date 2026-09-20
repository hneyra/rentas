import type { PiezasDelConsumidor } from '@kamayuk/ui';

import { GraficoDeRecaudacion } from './GraficoDeRecaudacion.tsx';
import { GRAFICO_DE_RECAUDACION } from './serieDeAvance.ts';

/**
 * **Las piezas que este sistema dibuja dentro del interprete** (#288).
 *
 * <h2>Que es esto</h2>
 *
 * El registro `clave -> componente` que `<Pantalla piezas>` recibe. Una definicion que lleve
 * `{ tipo: 'delConsumidor', clave }` busca aqui su componente; una clave que no este dibuja un
 * aviso visible con su nombre —nunca un hueco en blanco—, y ademas sale roja sin montar nada en
 * `verificaciones/las-piezas-del-consumidor-estan-registradas.test.ts`, que usa la
 * `piezasSinRegistrar` que la libreria publica para eso.
 *
 * <h2>Por que el registro esta aqui y lo pasa `PantallaDeRentas`</h2>
 *
 * Por lo mismo que las tres `props` que ese archivo ya pone: escrito en cada sitio que monta una
 * pantalla, una prueba podria montar el interprete con OTRAS piezas que las de la aplicacion y
 * medir una pantalla que nadie ve. Escrito una vez, lo que la guarda monta es lo que se sirve.
 *
 * Hoy hay una. Entran las que el artboard declare y el interprete no pueda dibujar por su cuenta.
 */
export const PIEZAS_DE_RENTAS: PiezasDelConsumidor = {
  [GRAFICO_DE_RECAUDACION]: GraficoDeRecaudacion,
};
