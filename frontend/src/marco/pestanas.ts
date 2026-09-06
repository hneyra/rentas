/**
 * Las pestanas del marco: abrir, ensuciar y cerrar. **Sin React y sin DOM.**
 *
 * Es la mitad del marco que se puede razonar sola, asi que se prueba sola: las
 * tres reglas que el issue pide —abrir no duplica (AC3), editar ensucia (AC5),
 * cerrar la activa activa la vecina (AC6)— son transformaciones de un objeto en
 * otro, y meterlas dentro del componente obligaria a montar un arbol de React
 * para comprobar que cerrar la primera de tres activa la segunda.
 */

/** El estado navegable del marco. */
export interface EstadoDePestanas {
  /** Las pestanas abiertas, en el orden en que se abrieron. */
  readonly abiertas: readonly string[];
  /** La pestana activa. `null` cuando no queda ninguna abierta. */
  readonly activa: string | null;
  /** Las que tienen cambios sin guardar. La clave esta o no esta. */
  readonly sucias: Readonly<Record<string, true>>;
  /** La que se ha pedido cerrar y espera confirmacion. */
  readonly porCerrar: string | null;
}

export type AccionSobrePestanas =
  | { readonly tipo: 'abrir'; readonly destino: string }
  /** Cierra, o pregunta antes si la pestana tiene cambios sin guardar. */
  | { readonly tipo: 'pedir-cierre'; readonly destino: string }
  | { readonly tipo: 'cerrar'; readonly destino: string }
  | { readonly tipo: 'cancelar-cierre' }
  /** Se ha editado algo en la pestana activa. */
  | { readonly tipo: 'ensuciar' };

/**
 * El estado con el que arranca el marco.
 *
 * `destino` es lo que pide el hash al cargar la pagina, o `null`. **Y se abre
 * como pestana, no solo se activa**: el artboard fijaba `dest` desde el hash sin
 * tocar `abiertas`, de modo que recargar sobre `#determinacion` dejaba la
 * seccion activa y sin pestana en la barra —activa una cosa que no se ve, y que
 * no se puede cerrar—. Aqui la pestana existe, que es lo que el AC4 y el AC6
 * necesitan que sea cierto a la vez.
 */
export function estadoInicial(destino: string | null): EstadoDePestanas {
  const arranque = destino ?? 'panel';
  return {
    abiertas: arranque === 'panel' ? ['panel'] : ['panel', arranque],
    activa: arranque,
    sucias: {},
    porCerrar: null,
  };
}

export function reducir(
  estado: EstadoDePestanas,
  accion: AccionSobrePestanas,
): EstadoDePestanas {
  switch (accion.tipo) {
    case 'abrir':
      // Abrir lo que ya esta abierto Y activo no cambia nada, y devolver el
      // MISMO objeto —no uno igual— importa: el oyente de `hashchange` despacha
      // un `abrir` por cada cambio de hash, y el hash lo escribe el efecto que
      // sigue a la pestana activa. Con un objeto nuevo cada vez, los dos se
      // llamarian en circulo.
      if (estado.activa === accion.destino && estado.abiertas.includes(accion.destino)) {
        return estado;
      }
      // Abrir un submodulo lo anade a las pestanas si no estaba; si ya estaba,
      // solo lo activa. El panel lateral no se toca: es persistente (AC3).
      return {
        ...estado,
        activa: accion.destino,
        abiertas: estado.abiertas.includes(accion.destino)
          ? estado.abiertas
          : [...estado.abiertas, accion.destino],
      };

    case 'pedir-cierre':
      // Con cambios sin guardar no se cierra a la primera: cerrar los descarta y
      // eso no se puede deshacer (AC5).
      return estado.sucias[accion.destino] === true
        ? { ...estado, porCerrar: accion.destino }
        : reducir(estado, { tipo: 'cerrar', destino: accion.destino });

    case 'cerrar': {
      const donde = estado.abiertas.indexOf(accion.destino);
      if (donde < 0) {
        return estado;
      }
      const quedan = estado.abiertas.filter((clave) => clave !== accion.destino);
      const sucias = { ...estado.sucias };
      delete sucias[accion.destino];

      // La de la izquierda si hay, y si no la de la derecha: `donde - 1` es la
      // izquierda, y acotarlo a 0 da la derecha cuando se cerro la primera —que
      // tras el filtrado ocupa ese mismo indice—. Cerrar la ultima deja `null`,
      // que es honesto: no hay nada abierto (AC6).
      const activa =
        estado.activa === accion.destino
          ? (quedan[Math.max(donde - 1, 0)] ?? null)
          : estado.activa;

      return { abiertas: quedan, activa, sucias, porCerrar: null };
    }

    case 'cancelar-cierre':
      return { ...estado, porCerrar: null };

    case 'ensuciar':
      // Sin pestana activa no hay nada que ensuciar: el lienzo vacio no tiene
      // campos, y marcar una pestana que no existe dejaria un asterisco huerfano
      // en la siguiente que se abriera.
      return estado.activa === null
        ? estado
        : { ...estado, sucias: { ...estado.sucias, [estado.activa]: true } };
  }
}
