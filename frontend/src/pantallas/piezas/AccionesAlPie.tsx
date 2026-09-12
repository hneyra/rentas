import { Boton } from '@kamayuk/ui';

/**
 * Las acciones, **al pie** y **decididas por el dato**.
 *
 * <h2>Al pie, y no arriba</h2>
 *
 * El orden de la pantalla es llenar y luego confirmar. Con el boton de guardar arriba, se ofrece
 * confirmar antes de haber leido nada.
 *
 * <h2>Que se ofrece NO lo elige quien escribe la pantalla</h2>
 *
 * Sale de si la pantalla tiene algun campo que se escriba:
 *
 *   · **ninguno** -> es de consulta -> `Exportar` + `Imprimir`
 *   · **alguno**  -> se guarda      -> `Limpiar` + `Guardar`
 *
 * Es lo que hace el artboard, y dejarlo como parametro seria abrir la puerta a una pantalla de
 * solo lectura con un boton de guardar que no guarda nada — un boton que miente, y que nadie
 * descubre hasta que alguien lo pulsa esperando algo.
 *
 * Y el aviso va con ellas, porque dice lo que el boton implica: «nada se escribe hasta que pulse
 * Guardar» es la mitad del contrato de un formulario.
 *
 * <h2>Por que los avisos son parametro con valor por omision NEUTRO</h2>
 *
 * Porque el del artboard dice «los datos son los que figuran en el PADRON», y un padron es
 * vocabulario de un contexto. Este archivo esta destinado a `@kamayuk/ui`, donde esa palabra no
 * puede estar (ADR-0030 §4). El valor por omision dice lo mismo sin nombrarlo, y quien monta la
 * pantalla pasa el suyo — `rentas` pasa el de V8, palabra por palabra, desde `avisos.ts`.
 */

export interface AccionesAlPieProps {
  /** Si la pantalla tiene algun campo que se escribe. Lo calcula quien la dibuja, del dato. */
  readonly seEscribe: boolean;
  readonly alVolver: () => void;
  readonly alActuar: (accion: string) => void;
  /** Lo que se lee junto a los botones. Ver el javadoc: por omision, sin vocabulario de nadie. */
  readonly avisos?: Avisos;
}

const DE_CONSULTA = ['Exportar', 'Imprimir'] as const;
const DE_ESCRITURA = ['Limpiar', 'Guardar'] as const;

export interface Avisos {
  readonly consulta: string;
  readonly escritura: string;
}

const NEUTROS: Avisos = {
  consulta: 'Los datos son los que figuran a la fecha de hoy.',
  escritura: 'Nada se escribe hasta que pulse Guardar.',
};

export function AccionesAlPie({
  seEscribe,
  alVolver,
  alActuar,
  avisos = NEUTROS,
}: AccionesAlPieProps) {
  const acciones = seEscribe ? DE_ESCRITURA : DE_CONSULTA;
  return (
    <div className="flex items-center gap-[10px] flex-wrap pt-0.5">
      <Boton type="button" onClick={alVolver}>
        Volver
      </Boton>
      <p className="m-0 flex-1 min-w-[180px] text-[12.5px] leading-[1.5] text-tinta-3 text-pretty">
        {seEscribe ? avisos.escritura : avisos.consulta}
      </p>
      {acciones.map((a, i) => (
        <Boton
          key={a}
          type="button"
          // La segunda es la que confirma: es la unica primaria. Dos botones azules al lado
          // obligan a leerlos para saber cual es el que hace algo.
          variante={i === acciones.length - 1 ? 'primario' : 'secundario'}
          onClick={() => alActuar(a)}
        >
          {a}
        </Boton>
      ))}
    </div>
  );
}
