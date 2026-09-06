import { Aviso, Boton } from '../ds/index.ts';
import type { Peldano } from '../api/escalera.ts';

/**
 * Lo que se ve cuando la navegacion no se pudo leer (I-3, AC7).
 *
 * <h2>Por que no es un marco vacio</h2>
 *
 * Porque un marco sin arbol **se ve exactamente igual que una cuenta sin modulos**: barra
 * arriba, panel de la izquierda en blanco, ninguna pestana. Quien atiende no tiene forma de
 * distinguir «el sistema no pudo leer el catalogo» de «a mi no me han dado ningun modulo», y
 * las dos se arreglan en sitios distintos —una reintentando o llamando a soporte, la otra
 * pidiendo permisos—. Asi que si el arbol no se pudo componer, no se dibuja a medias: se dice.
 *
 * <h2>Aqui SI se ofrece reintentar aunque no sea una averia, y el motivo esta escrito</h2>
 *
 * `escalera.ts` argumenta —y con razon— que reintentar una falta de permiso da la misma falta
 * de permiso, y que a la tercera vez quien atiende deja de creerse los botones. Ahi hablaba de
 * la sesion: un 403 `SIN_MUNICIPALIDAD` esta decidido y no cambia mientras alguien no toque el
 * emisor de identidad.
 *
 * Este caso es distinto y por eso la regla es otra. Lo que falta aqui es un permiso del
 * catalogo, y ADR-0013 dice que la matriz se vuelve a pedir en cada renovacion del token
 * **«asi un cambio de permisos entra sin que el usuario cierre sesion»**. O sea que el remedio
 * —que un administrador conceda el acceso— surte efecto **durante** la sesion, y reintentar es
 * exactamente el gesto con el que entra. Un boton que no estuviera obligaria a cerrar sesion
 * para recoger un permiso que ya esta concedido.
 *
 * <h2>Y nombra el permiso, porque es el unico dato con el que se arregla</h2>
 *
 * Las dos lecturas que componen el arbol —`GET /seguridad/modulos` y `GET /seguridad/accesos`—
 * declaran `@RequiereAcceso` sobre **«Módulos del sistema»** y **«Accesos y políticas»**, que
 * son opciones de ADMINISTRACION del catalogo. Una cuenta de ventanilla no tiene por que
 * tenerlas, y sin ellas se queda sin arbol entero. Decir «no se pudo» mandaria a mirar un
 * despliegue; decir que opcion falta manda a quien puede concederla.
 */
export interface SinArbolProps {
  readonly peldano: Peldano;
  readonly alVolverAIdentificarse: () => void;
  readonly alReintentar: () => void;
}

export function SinArbol({ peldano, alVolverAIdentificarse, alReintentar }: SinArbolProps) {
  return (
    <main className="kr-puerta">
      <div className="kr-puerta__caja">
        <p className="kr-puerta__marca">
          Rentas
          <span className="kr-puerta__marca-nota">Sistema de gestión tributaria municipal</span>
        </p>

        <Aviso
          tipo={peldano.esAveria ? 'error' : 'sin-permiso'}
          titulo="No se pudo leer el árbol de módulos"
          detalle={peldano.detalle}
        >
          <p className="kr-puerta__remedio">
            Sin él no hay navegación, y dibujar el marco vacío diría que esta cuenta no tiene
            ningún módulo. {peldano.remedio}
          </p>
          {!peldano.esAveria && (
            <p className="kr-puerta__remedio">
              El árbol se compone de «GET /seguridad/modulos» y «GET /seguridad/accesos», que
              piden las opciones «Módulos del sistema» y «Accesos y políticas».
            </p>
          )}
          <Boton variante="primario" onClick={alReintentar}>
            Reintentar
          </Boton>
          {peldano.pideIdentidad && (
            <Boton variante="secundario" onClick={alVolverAIdentificarse}>
              Volver a identificarse
            </Boton>
          )}
        </Aviso>
      </div>
    </main>
  );
}
