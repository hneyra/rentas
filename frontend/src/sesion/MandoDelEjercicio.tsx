import { useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';
import {
  Alerta,
  Area,
  Boton,
  Cajon,
  Campo,
  Etiqueta,
  NotaDelCajon,
  PanelDelCajon,
  TituloDelCajon,
} from '@kamayuk/ui';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';

import type { SesionDeLaVentanilla, SesionTrasElCambio } from '../datos/lecturas.ts';
import { RUTAS, cambiarElEjercicio, pedirUno } from '../datos/lecturas.ts';
import { LLAVES, usePermisosDeLaSesion } from '../datos/useCatalogoPermitido.ts';
import { alFallar } from '../datos/useDatosDeLaHoja.ts';
import { CAMBIAR_EL_EJERCICIO, tieneElPrivilegio } from '../permisos.ts';

/**
 * **El ejercicio de trabajo de la barra: un acto, y se ve como tal** (#391).
 *
 * <h2>De que defecto viene</h2>
 *
 * `seg-aud` y `territorio` exigen ejercicio, y la cuenta medida contesta `ejercicioDeTrabajo: null`.
 * Su frase mandaba a fijarlo en «Seguridad · Sistema», que no escribe nada; y `cambiarElEjercicio`
 * —el `PUT /seguridad/sesion/ejercicio` que el backend sirve— no tenia ni un consumidor desde que
 * `623a968` (#90) borro `src/marco/Ejercicio.tsx` de la V6 con sus 290 lineas de prueba. Dos hojas
 * cuya lectura se sirve no se podian abrir por ningun camino de la interfaz.
 *
 * <h2>Por que en la barra y no en `seg-sis`</h2>
 *
 * El ejercicio es **de la sesion** y vale para todas las hojas a la vez. Es exactamente lo que
 * `@kamayuk/shell` reserva con `enLaBarra` («el control que cambia `marco`»), y ahi se monta.
 * `seg-sis` sigue siendo lo que declara el artboard —una hoja de parametros cuyo resto es de
 * `normativa`— y su desplegable sigue sin mandar nada: #391 deja fuera el resto de esa hoja.
 *
 * `marco` **no se pasa**, y es a proposito: las hojas que exigen ejercicio lo leen de la sesion
 * cacheada (`LLAVES.sesion`, en `useDatosDeLaHoja`), y ponerlo ademas en `marco` seria una segunda
 * copia que nadie lee y que podria quedarse atras.
 *
 * <h2>Lo que se pide antes de mandar nada, y lo que no se comprueba aqui</h2>
 *
 * · **La observacion**, regla 10 y RNF-052. El boton no se puede pulsar con ella vacia, que no es
 *   duplicar la regla del backend —al menos cinco caracteres, que dice el con sus palabras— sino
 *   no mandar a proposito una peticion que ya se sabe que no lleva lo que la regla exige.
 * · **El ejercicio se TECLEA.** No hay lista: ninguna operacion publica los ejercicios que la
 *   municipalidad admite, y cualquier lista escrita aqui —o sacada del reloj del puesto— seria una
 *   invencion (AC2 de #181). El campo arranca en el que ya rige o **vacio**, nunca en el ano de hoy.
 *   El rango —1990 a 2100— lo sostiene el backend, y su 422 se ensena tal cual.
 *
 * <h2>Con la respuesta, la cache se entera, y las hojas vuelven a pedir</h2>
 *
 * Lo que devuelve el `PUT` se escribe en `LLAVES.sesion`. Esa llave la leen la barra y
 * `useDatosDeLaHoja`, cuya consulta lleva el ejercicio en la clave: cambiarlo trae **otra**
 * bitacora, no la cacheada. Sin esta escritura, la sesion cacheada seguiria diciendo `null` y la
 * hoja seguiria en «falta el ejercicio» con el servidor ya cambiado — que es la rotura que mide
 * `MandoDelEjercicio.test.tsx`.
 *
 * <h2>Solo se ofrece a quien puede</h2>
 *
 * Con `especial` sobre `cambiar_anio` —leido de `/sesion/permisos` por `tieneElPrivilegio`, el
 * mismo camino con que `permisos.ts` filtra el catalogo— hay boton. Sin el, la barra dice el valor
 * y, en su `title`, que opcion falta y por su nombre del catalogo. No es esconder una funcion por
 * cosmetica: es no ofrecer una puerta que contesta 403.
 */
export function MandoDelEjercicio() {
  const { t } = useTranslation();
  const consultas = useQueryClient();
  // La misma consulta —y la misma llave— que la barra y las hojas que exigen ejercicio: una ida.
  const sesion = useQuery({
    queryKey: LLAVES.sesion,
    queryFn: ({ signal }) => pedirUno<SesionDeLaVentanilla>(RUTAS.sesion, signal),
    retry: false,
  });
  const permisos = usePermisosDeLaSesion();

  const [abierto, setAbierto] = useState(false);
  const [anio, setAnio] = useState('');
  const [observacion, setObservacion] = useState('');
  const [enviando, setEnviando] = useState(false);
  const [fallo, setFallo] = useState<unknown>(null);

  const ejercicio = sesion.data?.ejercicioDeTrabajo ?? null;
  // Mientras la sesion no contesta —o si fallo— no se sabe el ejercicio, y una raya no afirma
  // nada. «sin fijar» es solo lo que el backend contesto: `null`.
  const valor =
    sesion.data === undefined ? '—' : ejercicio === null ? t('sin fijar') : String(ejercicio);
  const puede =
    permisos.data !== undefined &&
    tieneElPrivilegio(permisos.data, CAMBIAR_EL_EJERCICIO.codigo, CAMBIAR_EL_EJERCICIO.privilegio);

  if (!puede) {
    return (
      <span
        data-slot="ejercicio-de-la-sesion"
        className="flex items-center gap-[6px] text-[12.5px] text-sobre-barra-2"
        title={t(
          'Cambiar el ejercicio de trabajo pide el privilegio especial sobre «{{opcion}}», que esta cuenta no tiene.',
          { opcion: t(CAMBIAR_EL_EJERCICIO.nombre) },
        )}
      >
        <span>{t('Ejercicio')}</span>
        <span className="font-bold text-sobre-barra">{valor}</span>
      </span>
    );
  }

  // `Number` y no `parseInt`: `parseInt('20a6')` da 20 y mandaria un ejercicio que nadie tecleo.
  const pedido = Number(anio.trim());
  const sePuedeMandar =
    !enviando && anio.trim() !== '' && Number.isInteger(pedido) && observacion.trim() !== '';

  const abrir = () => {
    // El que ya rige, que es lo que casi siempre se corrige a un digito de distancia; vacio si no
    // hay ninguno. Rellenarlo con el ano del reloj seria la afirmacion que el backend no hace.
    setAnio(ejercicio === null ? '' : String(ejercicio));
    setObservacion('');
    setFallo(null);
    setAbierto(true);
  };

  const mandar = () => {
    if (!sePuedeMandar) return;
    setEnviando(true);
    setFallo(null);
    cambiarElEjercicio(pedido, observacion).then(
      (tras) => {
        escribirLaSesion(consultas, tras);
        setEnviando(false);
        setAbierto(false);
      },
      (motivo: unknown) => {
        // Se queda ABIERTO a proposito: el 422 mas probable es «la observacion es corta», y
        // cerrar obligaria a teclear otra vez lo que ya estaba escrito.
        setFallo(motivo);
        setEnviando(false);
      },
    );
  };

  const porQueNo = fallo === null ? null : alFallar(fallo, t);

  return (
    <>
      <Boton
        variante="barra"
        tamano="menudo"
        data-slot="mando-del-ejercicio"
        aria-haspopup="dialog"
        aria-expanded={abierto}
        aria-label={t('Ejercicio de trabajo: {{valor}}. Cambiarlo queda registrado.', { valor })}
        onClick={abrir}
      >
        <span className="text-sobre-barra-2">{t('Ejercicio')}</span>
        <span className="font-bold">{valor}</span>
      </Boton>
      <Cajon
        open={abierto}
        onOpenChange={(abre) => {
          if (!abre && !enviando) setAbierto(false);
        }}
      >
        <PanelDelCajon lado="derecha" className="w-[min(360px,92vw)]">
          <TituloDelCajon>{t('Cambiar el ejercicio de trabajo')}</TituloDelCajon>
          <NotaDelCajon>
            {t(
              'El ejercicio es de la sesion, no de esta pestana: al cambiarlo cambia para todos los modulos. Queda registrado con su observacion y con la cuenta que lo cambio.',
            )}
          </NotaDelCajon>
          <form
            data-slot="forma-del-ejercicio"
            className="flex flex-col gap-[14px] overflow-y-auto px-[15px] pb-[15px]"
            onSubmit={(evento) => {
              evento.preventDefault();
              mandar();
            }}
          >
            {porQueNo === null ? null : (
              <Alerta tono={porQueNo.tono}>
                <p className="m-0 break-words">{porQueNo.explicacion}</p>
              </Alerta>
            )}
            <Etiqueta
              rotulo={t('Ejercicio')}
              ayuda={t('El ano de trabajo. Si no se admite, el sistema lo dira.')}
            >
              <Campo
                inputMode="numeric"
                autoComplete="off"
                placeholder={t('AAAA')}
                value={anio}
                onChange={(evento) => {
                  setAnio(evento.target.value);
                }}
              />
            </Etiqueta>
            <Etiqueta
              rotulo={t('Observacion')}
              ayuda={t('Por que se cambia. Queda en la auditoria junto con la cuenta que lo cambio.')}
            >
              <Area
                value={observacion}
                onChange={(evento) => {
                  setObservacion(evento.target.value);
                }}
              />
            </Etiqueta>
            <div className="flex justify-end gap-[8px]">
              <Boton
                type="button"
                disabled={enviando}
                onClick={() => {
                  setAbierto(false);
                }}
              >
                {t('Cancelar')}
              </Boton>
              <Boton type="submit" variante="primario" disabled={!sePuedeMandar}>
                {t('Cambiar el ejercicio')}
              </Boton>
            </div>
          </form>
        </PanelDelCajon>
      </Cajon>
    </>
  );
}

/**
 * **Lo que el `PUT` contesto, escrito en la sesion cacheada** (#391).
 *
 * Solo el ejercicio: `SesionTrasElCambio` no trae ni la cuenta ni el nombre, que no cambian. Si la
 * sesion todavia no estaba en la cache —no llego a contestar—, se invalida y se vuelve a pedir: no
 * se inventa una sesion con los campos que faltan.
 */
function escribirLaSesion(consultas: QueryClient, tras: SesionTrasElCambio): void {
  if (consultas.getQueryData<SesionDeLaVentanilla>(LLAVES.sesion) === undefined) {
    void consultas.invalidateQueries({ queryKey: LLAVES.sesion });
    return;
  }
  consultas.setQueryData<SesionDeLaVentanilla>(LLAVES.sesion, (antes) =>
    antes === undefined ? antes : { ...antes, ejercicioDeTrabajo: tras.ejercicioDeTrabajo },
  );
}
