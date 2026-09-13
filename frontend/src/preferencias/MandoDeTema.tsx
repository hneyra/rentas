import {
  Cajon,
  IDENTIDADES,
  MODOS,
  NotaDelCajon,
  PanelDelCajon,
  TituloDelCajon,
  cn,
  useTema,
  type Identidad,
  type Modo,
} from '@kamayuk/ui';
import { useId } from 'react';
import { useTranslation } from 'react-i18next';

/**
 * **El mando de los temas** (#111).
 *
 * <h2>Por que un cajon y no una pantalla</h2>
 *
 * Porque V8 **no dibuja ninguna pantalla de preferencias**, y las cuarenta que dibuja estan
 * comparadas campo por campo contra `diseno/RentasV8.dc.html`. Inventar la cuarenta y uno seria
 * meter en el arbol una pantalla que el artboard no tiene, que es justo lo que esa guarda existe
 * para impedir. El menu de sesion **si** trae «Preferencias» —esta en el artboard, y hasta este
 * issue no hacia nada—, asi que el mando cuelga de ahi.
 *
 * El cajon es la pieza de `@kamayuk/ui` que corresponde: sale por un lado, se cierra con Escape y
 * es un dialogo de verdad para el lector de pantalla. No hace falta pieza nueva.
 *
 * <h2>Los dos ejes se ofrecen SEPARADOS, porque son dos cosas distintas</h2>
 *
 *     Identidad visual   institucional | alto-contraste | sepia     <- de que servicio es esto
 *     Apariencia         claro | oscuro | el del sistema            <- como lo quiere ver quien mira
 *
 * Una sola lista de cinco —«claro, oscuro, alto contraste, sepia claro, sepia oscuro»— obliga a
 * escribir seis entradas y a repensarlas cada vez que entre una identidad. Cruzados son tres por
 * tres, y el tercero del segundo eje —«el del sistema»— no es un tema: es **no elegir**, y por eso
 * vale `null` y quita el atributo en vez de ponerlo en claro.
 *
 * <h2>Las opciones salen de la libreria, no de una lista de aqui</h2>
 *
 * `IDENTIDADES` y `MODOS` son de `@kamayuk/ui`. Escribirlas aqui a mano dejaria este mando corto
 * el dia que entre una cuarta identidad, y **sin que nada lo dijera**: el tema existiria, su CSS
 * viajaria en el paquete, y aqui no habria como elegirlo. Lo unico propio es el rotulo con que se
 * leen, que es texto y va por `t()`.
 *
 * <h2>Y son `<input type="radio">` de verdad</h2>
 *
 * Un `<button aria-checked>` dibuja lo mismo y deja fuera el recorrido con flechas, el grupo con
 * nombre y el anuncio «2 de 3» del lector de pantalla. El aspecto se pinta sobre la etiqueta —con
 * los tokens del artboard, nunca con un color escrito aqui—, asi que no se pierde nada.
 */

/** Como se lee cada identidad. El castellano ES la clave: ver `src/i18n/i18n.ts`. */
const ROTULO_DE_LA_IDENTIDAD: Readonly<Record<Identidad, string>> = {
  institucional: 'Institucional',
  'alto-contraste': 'Alto contraste',
  sepia: 'Sepia',
};

/** Como se lee cada modo. El tercero —no elegir— no esta aqui: no es un modo. */
const ROTULO_DEL_MODO: Readonly<Record<Modo, string>> = {
  claro: 'Claro',
  oscuro: 'Oscuro',
};

/** Lo que se ofrece por cada eje: su clave estable, su rotulo y el valor que fija. */
interface Opcion<T> {
  /** Estable y sin tildes: es lo que el arnes usa para apuntar a la opcion. */
  readonly clave: string;
  readonly rotulo: string;
  readonly valor: T;
}

const DE_LA_IDENTIDAD: readonly Opcion<Identidad>[] = IDENTIDADES.map((identidad) => ({
  clave: identidad,
  rotulo: ROTULO_DE_LA_IDENTIDAD[identidad],
  valor: identidad,
}));

const DEL_MODO: readonly Opcion<Modo | null>[] = [
  ...MODOS.map((modo) => ({ clave: modo, rotulo: ROTULO_DEL_MODO[modo], valor: modo })),
  // El tercero es **no elegir**, y por eso su valor es `null` y no una tercera paleta.
  { clave: 'sistema', rotulo: 'El del sistema', valor: null },
];

function Eje<T>({
  rotulo,
  nota,
  opciones,
  elegido,
  al,
}: {
  readonly rotulo: string;
  readonly nota: string;
  readonly opciones: readonly Opcion<T>[];
  readonly elegido: T;
  readonly al: (valor: T) => void;
}) {
  const { t } = useTranslation();
  // El `name` del grupo tiene que ser unico en el documento: con el mismo en los dos ejes, marcar
  // una identidad desmarcaria el modo — son el mismo grupo de radios para el navegador.
  const grupo = useId();

  return (
    <fieldset data-slot="eje-del-tema" className="m-0 border-0 p-0">
      <legend className="mb-[7px] p-0 text-[12.5px] font-bold text-tinta-3">{t(rotulo)}</legend>
      <div className="grid gap-[6px]">
        {opciones.map((opcion) => {
          const marcada = opcion.valor === elegido;
          return (
            <label
              key={opcion.clave}
              data-slot="opcion-del-tema"
              data-opcion={opcion.clave}
              data-elegida={marcada ? '1' : '0'}
              className={cn(
                'flex cursor-pointer items-center gap-[9px] rounded-sm border px-[10px] py-2 text-[13.5px] transition-colors',
                marcada
                  ? 'border-azul bg-azul-suave font-bold text-info-tinta'
                  : 'border-borde-campo bg-superficie text-tinta-2 hover:border-borde-hover',
              )}
            >
              <input
                type="radio"
                name={grupo}
                value={opcion.clave}
                checked={marcada}
                onChange={() => {
                  al(opcion.valor);
                }}
                className="size-4 shrink-0 accent-azul"
              />
              <span>{t(opcion.rotulo)}</span>
            </label>
          );
        })}
      </div>
      <p className="mt-[7px] mb-0 text-[12px] leading-[1.5] text-tinta-4">{t(nota)}</p>
    </fieldset>
  );
}

export interface MandoDeTemaProps {
  readonly abierto: boolean;
  readonly alCerrar: () => void;
}

export function MandoDeTema({ abierto, alCerrar }: MandoDeTemaProps) {
  const { t } = useTranslation();
  const { identidad, modo, fijarIdentidad, fijarModo } = useTema();

  return (
    <Cajon
      open={abierto}
      onOpenChange={(abre) => {
        if (!abre) alCerrar();
      }}
    >
      {/* El cajon de la libreria mide 262 px, que es el ancho del carril. Aqui dentro van dos
          listas con sus rotulos, y a 262 px las tres opciones del modo salen partidas. */}
      <PanelDelCajon lado="derecha" className="w-[min(360px,92vw)]">
        <TituloDelCajon>{t('Preferencias')}</TituloDelCajon>
        <NotaDelCajon>
          {t(
            'Se guarda en este navegador y solo aqui: no viaja al servidor ni cambia lo que ven las demas personas.',
          )}
        </NotaDelCajon>
        {/* El `data-slot` va en el cuerpo y NO en el panel: el panel ya lleva el suyo
            —`panel-del-cajon`, de la libreria— y pisarselo dejaria sin nombre a la pieza que lo
            dibuja, que es la que sus propias pruebas apuntan. */}
        <div
          data-slot="mando-de-tema"
          className="flex flex-col gap-[18px] overflow-y-auto px-[15px] pb-[15px]"
        >
          <Eje
            rotulo="Identidad visual"
            nota="La paleta con que se dibuja este servicio."
            opciones={DE_LA_IDENTIDAD}
            elegido={identidad}
            al={fijarIdentidad}
          />
          <Eje
            rotulo="Apariencia"
            nota="Sin elegir, se sigue lo que el equipo tenga puesto."
            opciones={DEL_MODO}
            elegido={modo}
            al={fijarModo}
          />
        </div>
      </PanelDelCajon>
    </Cajon>
  );
}
