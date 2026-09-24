import { Component, type ReactNode } from 'react';

/**
 * **Lo que una hoja lanza al dibujarse se queda en esa hoja** (#354).
 *
 * <h2>De que defecto viene</h2>
 *
 * El reparto de un conector corre en el RENDER: `useDatosDeLaHoja` llama a `repartir` en el cuerpo
 * del gancho, fuera de la consulta, asi que lo que lance no pasa por `isError` ni por `alFallar`.
 * Y hasta #354 no habia ninguna frontera ni en este arbol ni en `@kamayuk/shell` —cuya raiz del
 * enrutador no declara `errorElement`—: react-router envolvia entonces la coincidencia con su
 * componente por omision, que dibuja **«Unexpected Application Error!», en ingles**, en lugar de
 * la cascara entera. `ini-panel` con la corrida en 204 lo hacia en la primera hoja del arbol, y F5
 * repetia lo mismo porque el destino vive en el hash.
 *
 * Las excepciones son deliberadas —`formatearImporte` «falla ruidosamente» a proposito— y el
 * diseno contaba con que acabaran siendo el hueco de ESA hoja. Esto es lo que lo cumple, y cubre
 * cualquier cosa que lance la hoja: un conector, el interprete o una pieza.
 *
 * <h2>Por que se reinicia con `reinicio` y no con una `key`</h2>
 *
 * Una `key` con la ruta desmontaria la hoja en cada cambio de ruta —cada pagina de una tabla, cada
 * orden—, y `@kamayuk/shell` pone la suya en el DESTINO y no en la ruta precisamente para que un
 * maestro-detalle conserve su lista mientras cambia el detalle. Asi que esta frontera no desmonta
 * nada mientras la hoja se dibuja bien, y **solo cuando ha caido** vuelve a intentarlo si lo que
 * recibe en `reinicio` cambio: otro sujeto, otra pagina. Sin eso, una hoja caida se quedaria caida
 * aunque el operador abriera otro contribuyente en ella.
 *
 * Es una clase porque React solo sabe recoger un error de render con `getDerivedStateFromError`, y
 * eso no tiene forma de gancho. No sabe nada de Rentas: que se dibuja en su lugar lo decide quien
 * la monta.
 */
interface PropsDeLaFrontera {
  /** Lo que, si cambia mientras la hoja esta caida, la devuelve a su camino normal. */
  readonly reinicio: string;
  /** Lo que se dibuja en lugar de la hoja, con lo que se lanzo. */
  readonly enSuLugar: (lanzado: unknown) => ReactNode;
  readonly children: ReactNode;
}

interface EstadoDeLaFrontera {
  /** Lo que se lanzo, envuelto: `undefined` o `null` tambien se pueden lanzar. */
  readonly caida: { readonly lanzado: unknown } | null;
}

export class FronteraDeLaHoja extends Component<PropsDeLaFrontera, EstadoDeLaFrontera> {
  override state: EstadoDeLaFrontera = { caida: null };

  static getDerivedStateFromError(lanzado: unknown): EstadoDeLaFrontera {
    return { caida: { lanzado } };
  }

  override componentDidUpdate(anteriores: PropsDeLaFrontera): void {
    if (this.state.caida !== null && anteriores.reinicio !== this.props.reinicio) {
      this.setState({ caida: null });
    }
  }

  override render(): ReactNode {
    const { caida } = this.state;
    return caida === null ? this.props.children : this.props.enSuLugar(caida.lanzado);
  }
}
