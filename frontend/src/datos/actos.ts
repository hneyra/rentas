import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import type {
  DatosDeLaPantalla,
  EnvioDeUnActo,
  EstadoDeUnaLectura,
  ManejadoresDeLosActos,
} from '@kamayuk/ui';

import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import {
  ANULAR_LA_LICENCIA,
  LO_QUE_CONTESTO_LA_ANULACION,
  opcionQueLoPide,
  puedeHacerlo,
} from '../pantallas/actos.ts';
import { ANULAR_LA_LICENCIA_DE_EDIFICACION, tieneElPrivilegio } from '../permisos.ts';
import { anularLicenciaDeEdificacion } from './lecturas.ts';
import { usePermisosDeLaSesion } from './useCatalogoPermitido.ts';
import { alRechazarElActo } from './useDatosDeLaHoja.ts';

/**
 * **Quien atiende los actos de cada hoja** (#629). Es a las escrituras lo que `CONECTORES` es a las
 * lecturas: el interprete de `@kamayuk/ui` dibuja el acto desde su definicion y lo NOMBRA por su
 * `clave`; lo que se manda, a donde, y que se dice cuando el backend no lo acepta es de aqui.
 *
 * <h2>Uno, y es la anulacion de la licencia de edificacion</h2>
 *
 * `POST /licencias/edificacion/{expediente}/anulacion` existe desde #455 y la pantalla no la
 * ofrecia. Es la segunda escritura de esta interfaz —la primera es el ejercicio de la barra, #391—
 * y la primera que es un ACTO del interprete, con su observacion obligatoria **en el tipo**.
 *
 * <h2>Lo que cada escritura declara</h2>
 *
 * · **`operacion`** — la del contrato, con su verbo. No la usa quien escribe —`escribir` llama a su
 *   funcion de `lecturas.ts`—; la leen las guardas: que la hoja la declare en `arbol.ts`, que este
 *   servida y que el contrato la publique (`verificaciones/el-acto-escribe-lo-que-la-hoja-declara`).
 * · **`permiso`** — la opcion y el privilegio que el `@RequiereAcceso` del backend pide. Con el, el
 *   boton que abre el acto sale IMPEDIDO y dice por que a quien no lo tiene, en vez de ofrecerle
 *   una puerta que contesta 403.
 * · **`escribir`** — manda el envio y devuelve lo que la tarjeta de lo hecho ensena, por nombre.
 */
interface EscrituraDeUnActo {
  readonly operacion: string;
  readonly permiso: { readonly codigo: string; readonly privilegio: string; readonly nombre: string };
  readonly escribir: (envio: EnvioDeUnActo) => Promise<ReadonlyMap<string, string>>;
}

/** Lo que el interprete manda de un campo: texto, o `false` de una casilla. Aqui solo hay texto. */
function textoDe(envio: EnvioDeUnActo, nombre: string): string {
  const valor = envio.valores[nombre];
  return typeof valor === 'string' ? valor : '';
}

/**
 * `aut-sol` — anular la licencia de edificacion de un expediente.
 *
 * El expediente y el motivo salen del formulario del acto, y la observacion de su campo propio, que
 * el interprete exige y recorta. Lo que se ensena al terminar es el papel: la resolucion, que es lo
 * que se entrega, con la licencia y el expediente sobre los que actuo **tal como los devolvio el
 * backend** —no como se tecleo el expediente—.
 */
const ANULAR_LA_LICENCIA_DE_UN_FUE: EscrituraDeUnActo = {
  operacion: 'POST /licencias/edificacion/{expediente}/anulacion',
  permiso: ANULAR_LA_LICENCIA_DE_EDIFICACION,
  escribir: async (envio) => {
    const acto = await anularLicenciaDeEdificacion(
      textoDe(envio, 'expediente').trim(),
      textoDe(envio, 'motivo').trim(),
      envio.observacion,
    );
    return new Map([
      [LO_QUE_CONTESTO_LA_ANULACION.resolucion, acto.resolucion.numero],
      [LO_QUE_CONTESTO_LA_ANULACION.licencia, acto.nroLicencia],
      [LO_QUE_CONTESTO_LA_ANULACION.expediente, acto.nroExpediente],
    ]);
  },
};

/** Los actos de cada hoja, por la `clave` con que su definicion los nombra. */
export const ACTOS_DE_LAS_HOJAS: Partial<Record<ClaveDeHoja, Readonly<Record<string, EscrituraDeUnActo>>>> = {
  'aut-sol': { [ANULAR_LA_LICENCIA]: ANULAR_LA_LICENCIA_DE_UN_FUE },
};

/** Lo que una hoja con actos le suma a su pantalla. */
export interface LoQueLaHojaHace {
  /** Los manejadores, para `<Pantalla actos>`. Sin ellos, el acto sale impedido «sin quien lo atienda». */
  readonly actos: ManejadoresDeLosActos;
  /** Si la cuenta puede cada acto, que opcion lo pide, y lo que contesto el ultimo que se hizo. */
  readonly nombrados: ReadonlyMap<string, string>;
  /** El rechazo de cada acto, con su peldano ya resuelto, por la clave del acto. */
  readonly lecturas: ReadonlyMap<string, EstadoDeUnaLectura>;
}

const NINGUNO: LoQueLaHojaHace = { actos: {}, nombrados: new Map(), lecturas: new Map() };

/**
 * **Los actos de una hoja, listos para su pantalla** (#629).
 *
 * El rechazo se pone en `lecturas` con la clave del acto —que es donde el interprete lo busca— y
 * **la promesa se rechaza igual**: asi el acto sabe que no se hizo, deja lo escrito donde estaba y
 * suelta el boton. Volver a mandar lo quita antes de salir, para que no se lea el fallo viejo
 * encima de un envio nuevo.
 *
 * Los ganchos van **antes** de mirar si la hoja tiene actos: un gancho no se llama dentro de una
 * condicion, y la hoja de la misma pantalla cambia sin desmontarla.
 */
export function useActosDeLaHoja(clave: ClaveDeHoja): LoQueLaHojaHace {
  const { t } = useTranslation();
  const permisos = usePermisosDeLaSesion();
  const [contestado, setContestado] = useState<ReadonlyMap<string, string>>(new Map());
  const [rechazos, setRechazos] = useState<ReadonlyMap<string, EstadoDeUnaLectura>>(new Map());

  const escrituras = ACTOS_DE_LAS_HOJAS[clave];
  if (escrituras === undefined) return NINGUNO;

  const nombrados = new Map<string, string>(contestado);
  const actos: Record<string, (envio: EnvioDeUnActo) => Promise<unknown>> = {};
  for (const [claveDelActo, escritura] of Object.entries(escrituras)) {
    const { codigo, privilegio, nombre } = escritura.permiso;
    // Presente solo si PUEDE: la definicion impide el boton con `{ hay: false }`.
    if (permisos.data !== undefined && tieneElPrivilegio(permisos.data, codigo, privilegio)) {
      nombrados.set(puedeHacerlo(claveDelActo), 'si');
    }
    nombrados.set(opcionQueLoPide(claveDelActo), nombre);
    actos[claveDelActo] = async (envio) => {
      setRechazos((antes) => sin(antes, claveDelActo));
      try {
        const dicho = await escritura.escribir(envio);
        setContestado(dicho);
        return dicho;
      } catch (fallo: unknown) {
        setRechazos((antes) => new Map(antes).set(claveDelActo, alRechazarElActo(fallo, t)));
        throw fallo;
      }
    };
  }
  return { actos, nombrados, lecturas: rechazos };
}

/**
 * **Los datos de la hoja con los de sus actos encima** (#629). `nombrados` y `lecturas` son de los
 * dos —lo que la hoja lee y lo que hace— y se suman sin pisarse. Sin actos, los mismos datos, sin
 * copiar: una hoja que no escribe no cambia en nada.
 */
export function conLoQueHace(datos: DatosDeLaPantalla, hace: LoQueLaHojaHace): DatosDeLaPantalla {
  if (hace.nombrados.size === 0 && hace.lecturas.size === 0) return datos;
  return {
    ...datos,
    nombrados: new Map([...(datos.nombrados ?? []), ...hace.nombrados]),
    lecturas: new Map([...(datos.lecturas ?? []), ...hace.lecturas]),
  };
}

/** El mapa sin esa clave; el mismo si no la tenia, para no repintar por nada. */
function sin<V>(mapa: ReadonlyMap<string, V>, clave: string): ReadonlyMap<string, V> {
  if (!mapa.has(clave)) return mapa;
  const copia = new Map(mapa);
  copia.delete(clave);
  return copia;
}
