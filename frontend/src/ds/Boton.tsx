import type { ButtonHTMLAttributes, ReactNode } from 'react';

/**
 * El boton del artboard. Tres variantes y dos tallas, ni una mas.
 *
 * En una barra de acciones **la ultima es la primaria** y las demas secundarias.
 * Eso lo decide quien compone la barra, no este componente: aqui no hay forma de
 * declarar «soy la accion de esta pantalla», porque entonces habria dos sitios
 * donde se decide cual es y se contradirian.
 *
 * `type="button"` por omision y no `submit`. Es el defecto clasico del HTML: un
 * boton dentro de un `<form>` sin `type` envia el formulario, asi que un boton
 * de «Buscar» junto a un formulario de alta lo guarda. Quien quiera enviar lo
 * pide por `type`, que sigue pasando en `...resto`.
 */
export type VarianteDeBoton = 'primario' | 'secundario' | 'fantasma';

export interface BotonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  readonly variante?: VarianteDeBoton;
  readonly menudo?: boolean;
  readonly children: ReactNode;
}

export function Boton({
  variante = 'secundario',
  menudo = false,
  children,
  className,
  type = 'button',
  ...resto
}: BotonProps) {
  const clases = ['kr-boton', `kr-boton--${variante}`];
  if (menudo) {
    clases.push('kr-boton--menudo');
  }
  if (className !== undefined) {
    clases.push(className);
  }

  return (
    <button type={type} className={clases.join(' ')} {...resto}>
      {children}
    </button>
  );
}
