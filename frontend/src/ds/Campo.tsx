import { useId } from 'react';

import { Esqueleto } from './Esqueleto.tsx';

/**
 * Un campo de formulario, en los seis tipos que el artboard dibuja.
 *
 * Las reglas de pintado son las suyas: `sel` es un `select` con sus opciones,
 * `area` un `textarea` de tres filas que solo crece a lo alto, `chk` una casilla
 * con su texto dentro de una caja con borde, y `ro` un valor de solo lectura con
 * borde discontinuo y cifras tabulares. `ancho` ocupa la fila entera de la
 * rejilla (`[data-ancho="1"] { grid-column: 1 / -1 }` en el artboard).
 *
 * **Todo control lleva su etiqueta asociada por `id`.** La caja de ventanilla se
 * opera con teclado y un control sin etiqueta no se puede anunciar. El `id` sale
 * de `useId()` y no de una prop: un `id` que lo pone quien llama se repite en
 * cuanto el mismo campo se dibuja dos veces —una lista de cuotas, una rejilla de
 * predios— y entonces la etiqueta apunta al primero para todos.
 */
export type TipoDeCampo = 'text' | 'date' | 'sel' | 'area' | 'chk' | 'ro';

export interface CampoProps {
  readonly etiqueta: string;
  readonly tipo: TipoDeCampo;
  readonly valor?: string;
  readonly marcado?: boolean;
  /** El `placeholder`, y el texto de la casilla cuando el tipo es `chk`. */
  readonly ph?: string;
  readonly opciones?: readonly string[];
  /** Ocupa la fila entera de la rejilla. */
  readonly ancho?: boolean;
  /** Mientras el dato no ha llegado: en su sitio se dibuja un `Esqueleto`. */
  readonly cargando?: boolean;
  /**
   * El control se ve, pero no se escribe.
   *
   * No es lo mismo que `ro`: un `ro` es un valor que el sistema calcula y que
   * nadie teclea nunca, y esto es un campo que ESTA pantalla todavia no puede
   * mandar. Donde el HTML lo permite se usa `readonly` y no `disabled`, porque
   * un campo deshabilitado sale del recorrido del tabulador y en ventanilla se
   * trabaja con teclado.
   */
  readonly bloqueado?: boolean;
  /**
   * El mensaje del backend para ESTE campo, tal cual.
   *
   * Va sin reescribir: el servidor ya lo redacto en castellano y en lenguaje del
   * dominio, y una segunda version aqui se separa de la suya en la primera
   * correccion.
   */
  readonly error?: string;
  /**
   * La indicacion permanente bajo el control.
   *
   * No es un `placeholder`: el `placeholder` desaparece al escribir y en un
   * `input[type=date]` **el navegador ni lo pinta** —dibuja su propia mascara
   * `dd/mm/aaaa`—, asi que lo que se pusiera ahi no lo leeria nadie. Va enlazada
   * con `aria-describedby`, igual que el error.
   */
  readonly ayuda?: string;
  /** Marca el campo como no obligatorio, como el artboard: «opcional». */
  readonly opcional?: boolean;
  readonly onCambio?: (valor: string) => void;
}

/** Lo que ensena un campo de solo lectura que todavia no tiene valor. */
const SIN_VALOR = '—';

export function Campo({
  etiqueta,
  tipo,
  valor = '',
  marcado = false,
  ph,
  opciones,
  ancho = false,
  cargando = false,
  bloqueado = false,
  error,
  ayuda,
  opcional = false,
  onCambio,
}: CampoProps) {
  const id = useId();
  const idDelError = `${id}-error`;
  const idDeLaAyuda = `${id}-ayuda`;

  const clases = ['kr-campo'];
  if (ancho) {
    clases.push('kr-campo--ancho');
  }
  if (error !== undefined) {
    clases.push('kr-campo--con-error');
  }

  // El error primero: cuando hay los dos, lo que hay que corregir se anuncia
  // antes que lo que se explicaba.
  const describe =
    [error === undefined ? undefined : idDelError, ayuda === undefined ? undefined : idDeLaAyuda]
      .filter((referencia) => referencia !== undefined)
      .join(' ') || undefined;

  const comunes = {
    id,
    className: 'kr-campo__control',
    'aria-invalid': error === undefined ? undefined : true,
    'aria-describedby': describe,
  } as const;

  return (
    <div className={clases.join(' ')}>
      <label className="kr-campo__etiqueta" htmlFor={id}>
        {etiqueta}
        {opcional && <span className="kr-campo__opcional">opcional</span>}
      </label>
      {cargando ? <Esqueleto alto={36} /> : control()}
      {error !== undefined && (
        <p className="kr-campo__error" id={idDelError}>
          {error}
        </p>
      )}
      {ayuda !== undefined && (
        <p className="kr-campo__ayuda" id={idDeLaAyuda}>
          {ayuda}
        </p>
      )}
    </div>
  );

  function control() {
    if (tipo === 'sel') {
      // **Lo que sirvio la API manda sobre la lista del catalogo.** Las dos
      // vienen de sitios distintos y no tienen por que coincidir; un `select`
      // con un valor que no esta en su lista se dibuja mostrando la PRIMERA
      // opcion, y entonces la pantalla ensena una eleccion que nadie hizo.
      const declaradas = opciones ?? [];
      const todas =
        valor === '' || declaradas.includes(valor) ? declaradas : [valor, ...declaradas];
      return (
        <select
          {...comunes}
          value={valor}
          disabled={bloqueado}
          onChange={(evento) => onCambio?.(evento.target.value)}
        >
          {todas.map((opcion) => (
            <option key={opcion} value={opcion}>
              {opcion}
            </option>
          ))}
        </select>
      );
    }

    if (tipo === 'area') {
      return (
        <textarea
          {...comunes}
          rows={3}
          value={valor}
          placeholder={ph}
          readOnly={bloqueado}
          aria-readonly={bloqueado || undefined}
          onChange={(evento) => onCambio?.(evento.target.value)}
        />
      );
    }

    if (tipo === 'chk') {
      return (
        <span className="kr-campo__casilla">
          <input
            id={id}
            type="checkbox"
            checked={marcado}
            disabled={bloqueado}
            aria-describedby={describe}
            onChange={(evento) => onCambio?.(evento.target.checked ? 'si' : '')}
          />
          <span>{ph ?? etiqueta}</span>
        </span>
      );
    }

    if (tipo === 'ro') {
      // `<output>` y no `<span>`: es un valor que el sistema calculo, y ese es
      // justo el elemento que lo dice. Ademas es rotulable con `htmlFor`, que un
      // `<span>` no es — y sin eso la etiqueta no apuntaria a nada.
      return (
        <output id={id} className="kr-campo__control kr-campo__control--lectura">
          {valor === '' ? SIN_VALOR : valor}
        </output>
      );
    }

    return (
      <input
        {...comunes}
        type={tipo === 'date' ? 'date' : 'text'}
        value={valor}
        placeholder={ph}
        readOnly={bloqueado}
        aria-readonly={bloqueado || undefined}
        onChange={(evento) => onCambio?.(evento.target.value)}
      />
    );
  }
}
