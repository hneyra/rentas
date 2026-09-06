import { Aviso, Boton } from '../ds/index.ts';
import type { Peldano } from '../api/escalera.ts';

/**
 * Lo que se ve cuando la sesion no se pudo leer: **un peldano de la escalera, y cual**.
 *
 * <h2>Por que ocupa la pantalla entera y no un rincon</h2>
 *
 * Porque sin sesion no hay nada que hacer aqui. Las 181 operaciones del contrato exigen token, y
 * el inquilino sale del token: sin el, ninguna seccion puede pedir nada y el marco dibujaria un
 * padron vacio, una recaudacion vacia y una cola de trabajo vacia — tres pantallas que se ven
 * exactamente igual que «no hay contribuyentes», «no se recaudo nada» y «no hay nada parado»,
 * que es la peor manera de mentir que tiene una interfaz de recaudacion. Es mas honesto no
 * dibujar el marco que dibujarlo lleno de ceros ajenos.
 *
 * <h2>El boton que se ofrece depende del peldano, y por eso lo decide `escalera.ts`</h2>
 *
 * Volver a la puerta arregla el 401 y **no arregla** el 403 `SIN_MUNICIPALIDAD`: entrar otra vez
 * con la misma cuenta trae el mismo token, sin el mismo claim, y el mismo 403. Ofrecerlo ahi
 * seria mandar a dar vueltas a quien tiene que llamar al administrador. Lo mismo con reintentar,
 * que solo tiene sentido si esto fue una averia: reintentar una falta de permiso da la misma
 * falta de permiso, y a la tercera vez quien atiende deja de creerse los botones.
 */
export interface PuertaProps {
  readonly peldano: Peldano;
  readonly alVolverAIdentificarse: () => void;
  readonly alReintentar: () => void;
}

export function Puerta({ peldano, alVolverAIdentificarse, alReintentar }: PuertaProps) {
  return (
    <main className="kr-puerta">
      <div className="kr-puerta__caja">
        <p className="kr-puerta__marca">
          Rentas
          <span className="kr-puerta__marca-nota">Sistema de gestión tributaria municipal</span>
        </p>

        <Aviso
          // Una falta de permiso no es una averia, y el tono lo dice antes que el texto: el
          // candado manda a pedir el permiso y el aspa manda a mirar el despliegue.
          tipo={peldano.esAveria ? 'error' : 'sin-permiso'}
          titulo={peldano.titulo}
          detalle={peldano.detalle}
        >
          <p className="kr-puerta__remedio">{peldano.remedio}</p>
          {peldano.pideIdentidad && (
            <Boton variante="primario" onClick={alVolverAIdentificarse}>
              Volver a identificarse
            </Boton>
          )}
          {peldano.esAveria && (
            <Boton variante="secundario" onClick={alReintentar}>
              Reintentar
            </Boton>
          )}
        </Aviso>
      </div>
    </main>
  );
}
