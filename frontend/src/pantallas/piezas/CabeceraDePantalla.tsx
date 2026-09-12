/**
 * La cabecera de una pantalla, al modo de V7 que V8 conserva: miga, titulo, nota e instruccion.
 *
 * <h2>El titulo va en peso 400, y no es un descuido</h2>
 *
 * 27 px en peso normal. Un 27 px en negrita pesa mas que la barra global y la pantalla se lee al
 * reves: primero el titulo, que es lo que menos cambia, y despues lo que hay que hacer. El tamano
 * ya jerarquiza; el peso encima lo hace gritar.
 *
 * <h2>La instruccion NO es la nota, y por eso son dos parrafos</h2>
 *
 * La **nota** dice que ES la pantalla; la **instruccion** dice que hay que HACER aqui. Mezcladas
 * en una sola frase, lo segundo se pierde — y es lo unico que alguien que abre la pantalla por
 * primera vez necesita. Por eso la instruccion tiene su barra, con el modulo en negrita delante.
 */

export interface CabeceraDePantallaProps {
  /** El modulo al que pertenece. Va en la miga y delante de la instruccion. */
  readonly modulo: string;
  readonly titulo: string;
  readonly nota: string;
  readonly instruccion: string;
}

export function CabeceraDePantalla({ modulo, titulo, nota, instruccion }: CabeceraDePantallaProps) {
  return (
    <>
      <div className="px-[18px] pt-4 pb-1">
        <nav
          aria-label="Ruta"
          className="flex items-center gap-[7px] text-[12.5px] text-tinta-3 mb-[7px]"
        >
          <span className="whitespace-nowrap">{modulo}</span>
          <span aria-hidden="true">/</span>
          <span className="whitespace-nowrap font-bold">{titulo}</span>
        </nav>
        <h1 className="m-0 text-[27px] font-normal tracking-[-0.01em] text-tinta text-pretty">
          {titulo}
        </h1>
        {nota === '' ? null : (
          <p className="mt-[7px] mb-0 text-[13.5px] leading-[1.55] text-tinta-2 max-w-[82ch] text-pretty">
            {nota}
          </p>
        )}
      </div>
      <div className="bg-sup border-y border-linea">
        <p className="m-0 px-[18px] py-[10px] text-[13.5px] leading-[1.55] text-tinta-2 max-w-[96ch] text-pretty">
          <strong>{modulo}:</strong> {instruccion}
        </p>
      </div>
    </>
  );
}
