package kamayuk.rentas.tesoreria;

import java.util.Optional;

/**
 * Los recibos de caja de tasas, publicados para que otro contexto pueda comprobar que un tramite
 * esta pagado (ARQ-01 §4, #44, RF-110).
 *
 * <p>Es la <b>segunda</b> API publica de {@code tesoreria}, junto a {@link
 * ConveniosDelContribuyente}. Vive en el paquete raiz, no en {@code .aplicacion} ni en {@code
 * .dominio}, por el mismo motivo que {@code cuentacorriente.ConsultaDeDeudaPublica}: Spring
 * Modulith trata como interno todo lo que esta en un subpaquete, asi que un {@code import} desde
 * {@code licencias} de {@code tesoreria.dominio.Recibo} no pasa la verificacion.
 *
 * <p>Esto es exactamente lo que {@code licencias} puede ver de la caja. <b>Su tabla, no</b>: el AC
 * de #44 lo pide con todas sus letras —«el recibo se verifica contra tesoreria por su API publica,
 * no leyendo su tabla»—, y no es formalismo. Leer {@code recibo} desde otro contexto significaria
 * que ese contexto tiene que saber que la anulacion vive en {@code recibo_movimiento} desde #34,
 * que el tipo de pago decide si abona en el libro, y que el desglose esta congelado. El primero que
 * se olvide de una de las tres acepta un recibo anulado.
 *
 * <h2>Solo lectura, y de un recibo</h2>
 *
 * <p>No hay aqui ni cobrar, ni anular, ni duplicar: eso son actos con su caja, su turno y su
 * observacion, y viven en {@code CobrarTasa}, {@code AnularRecibo} y {@code DuplicadoDeRecibo}.
 * Publicar una escritura seria abrir un segundo camino a la caja sin nada de eso.
 */
public interface RecibosDeTramite {

    /**
     * El recibo con ese numero impreso, si existe en esta municipalidad.
     *
     * <p>El numero llega <b>como esta impreso en el papel</b> —{@code 001-0000123}—, que es lo que
     * el administrado trae a ventanilla, y se analiza <b>aqui</b>: el formato de un numero de
     * recibo es de {@code tesoreria}, y descomponerlo en el contexto que consulta seria tener dos
     * analizadores que un dia difieren del formato con que se imprimio.
     *
     * <p>Vacio si el numero no existe o no tiene la forma de un numero de recibo. Las dos cosas
     * significan lo mismo para quien pregunta —«ese recibo no respalda nada»— y distinguirlas
     * obligaria a publicar una excepcion de formato que solo serviria para que el consumidor la
     * tradujera otra vez.
     */
    Optional<ReciboDeTramite> porNumeroImpreso(String numeroImpreso);
}
