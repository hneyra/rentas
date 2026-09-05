package kamayuk.rentas.nucleo.aplicacion;

import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.predial.ValuacionRecibida;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El candado que va ANTES de la emision (ADR-0027 §2, P5C).
 *
 * <h2>Que impide</h2>
 *
 * <p>Que una corrida de emision arranque sobre una valuacion incompleta. Con `catastro` y `rentas`
 * en dos bases, lo que llega llega por eventos, y un ingestor detenido a mitad no produce ningun
 * sintoma: la proyeccion se queda con las valuaciones que alcanzo a aplicar y todo lo demas sigue
 * funcionando. Emitir asi produce <b>miles de recibos mal calculados</b> —a unos contribuyentes les
 * falta un predio en la base— y cada uno es plausible por separado: se descubre en ventanilla, con
 * los papeles ya notificados y el plazo del art. 137 corriendo.
 *
 * <h2>Por que se comprueban TRES cosas y no una</h2>
 *
 * <p>Porque se arreglan de tres maneras distintas y decir la equivocada manda a quien opera a
 * buscar donde no es:
 *
 * <ul>
 *   <li><b>No hay cierre.</b> Catastro no ha cerrado la corrida del ejercicio, o el evento no
 *       llego. Se arregla corriendo la valuacion, o mirando la cola.
 *   <li><b>El conteo no cuadra.</b> El cierre llego y faltan valuaciones. Se arregla esperando o
 *       reprocesando la cola — y el mensaje dice <b>cuantas</b>, porque «faltan tres de 14 422» y
 *       «faltan 9 000» no se atienden igual.
 *   <li><b>La huella no cuadra.</b> Llego el numero correcto de valuaciones y NO son las mismas. Es
 *       el caso peor y el unico que no se arregla esperando: hay que volver a correr la valuacion,
 *       porque lo que hay aqui describe un padron que catastro no emitio.
 * </ul>
 *
 * <h2>Y por que el conteo y la huella vienen CON el cierre</h2>
 *
 * <p>Porque si `rentas` los derivara de lo que recibio, estaria comprobando que lo que tiene es
 * igual a lo que tiene. Lo que se compara es lo que catastro <b>dice que emitio</b> contra lo que
 * aqui <b>llego</b>, y por eso las dos mitades tienen que venir de sitios distintos.
 */
@Service
public class CandadoDeEmision {

    private final ValuacionRecibida valuacion;

    public CandadoDeEmision(ValuacionRecibida valuacion) {
        this.valuacion = valuacion;
    }

    /**
     * Deja pasar, o se niega diciendo por que.
     *
     * @throws ValuacionSinCerrar si catastro no ha cerrado la corrida del ejercicio
     * @throws ValuacionIncompleta si el cierre llego y faltan valuaciones
     * @throws ValuacionQueNoCuadra si estan todas y no son las que catastro emitio
     */
    @Transactional(readOnly = true)
    public ValuacionRecibida.CierreDeCorrida exigirLaValuacionCompleta(Ejercicio ejercicio) {
        Optional<ValuacionRecibida.CierreDeCorrida> cierre = valuacion.cierreDe(ejercicio);
        if (cierre.isEmpty()) {
            throw new ValuacionSinCerrar(ejercicio);
        }

        long recibidas = valuacion.valuacionesRecibidasDe(ejercicio);
        if (recibidas != cierre.get().conteo()) {
            throw new ValuacionIncompleta(ejercicio, cierre.get().conteo(), recibidas);
        }

        String aqui = valuacion.huellaDeLoRecibido(ejercicio);
        if (!aqui.equals(cierre.get().huella())) {
            throw new ValuacionQueNoCuadra(ejercicio, cierre.get().huella(), aqui);
        }
        return cierre.get();
    }

    /** `catastro` no ha cerrado la corrida de valuacion del ejercicio, o su cierre no llego. */
    public static final class ValuacionSinCerrar extends RuntimeException {
        public ValuacionSinCerrar(Ejercicio ejercicio) {
            super(
                    "La emision del ejercicio "
                            + ejercicio.valor()
                            + " no arranca: `catastro` no ha cerrado su corrida de valuacion, o su"
                            + " cierre todavia no ha llegado. Emitir sin ella calcularia el padron"
                            + " con las valuaciones que hubiera (ADR-0027 §2)");
        }
    }

    /** El cierre llego y faltan valuaciones por aplicar. */
    public static final class ValuacionIncompleta extends RuntimeException {

        private final int esperadas;
        private final long recibidas;

        public ValuacionIncompleta(Ejercicio ejercicio, int esperadas, long recibidas) {
            super(
                    "La emision del ejercicio "
                            + ejercicio.valor()
                            + " no arranca: `catastro` cerro su corrida con "
                            + esperadas
                            + " valuaciones y aqui han llegado "
                            + recibidas
                            + ". Faltan "
                            + (esperadas - recibidas)
                            + ", y emitir ahora dejaria a esos predios fuera de la base de su"
                            + " contribuyente sin que ninguna cifra pareciera mal");
            this.esperadas = esperadas;
            this.recibidas = recibidas;
        }

        public int esperadas() {
            return esperadas;
        }

        public long recibidas() {
            return recibidas;
        }
    }

    /** Estan todas y no son las mismas. */
    public static final class ValuacionQueNoCuadra extends RuntimeException {
        public ValuacionQueNoCuadra(Ejercicio ejercicio, String emitida, String recibida) {
            super(
                    "La emision del ejercicio "
                            + ejercicio.valor()
                            + " no arranca: llego el numero correcto de valuaciones y NO son las"
                            + " que `catastro` emitio. Su huella agregada es "
                            + emitida
                            + " y la de lo recibido "
                            + recibida
                            + ". Esto no se arregla esperando: hay que volver a correr la"
                            + " valuacion");
        }
    }
}
