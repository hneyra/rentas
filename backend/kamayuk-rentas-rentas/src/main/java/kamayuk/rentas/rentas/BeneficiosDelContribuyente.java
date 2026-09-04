package kamayuk.rentas.rentas;

import java.time.LocalDate;
import java.util.List;

/**
 * Que beneficios tiene registrados un contribuyente <b>a una fecha</b>, publicado para otros
 * contextos acotados (ARQ-01 §4, #42, RF-107).
 *
 * <p>Es la primera API publica de {@code rentas}. Vive en el paquete raiz, no en {@code .dominio},
 * por el mismo motivo que {@code cuentacorriente.ConsultaDeDeudaPublica} y {@code
 * tesoreria.ConveniosDelContribuyente}: Spring Modulith trata como interno todo lo que esta en un
 * subpaquete, asi que un {@code import} de {@code rentas.dominio.Beneficio} desde otro contexto no
 * pasa la verificacion.
 *
 * <h2>Registro, no efecto</h2>
 *
 * <p>Lo que este puerto responde es <b>que beneficio esta registrado</b>: su tipo, su clase, su
 * base legal y el porcentaje o el importe que la norma declara. Lo que <b>no</b> responde —ni
 * puede— es cuanto descuenta ese beneficio de una deuda concreta.
 *
 * <p>No es una omision tecnica. Aplicar un beneficio exige decidir sobre que se aplica —¿solo el
 * insoluto? ¿tambien el interes? ¿tambien las costas?—, en que orden respecto del fraccionamiento y
 * con que redondeo, y esas decisiones son D-02b: valores de ordenanza local con su ratificacion
 * provincial (#191). Es el mismo criterio con que #33 dejo {@code recibo.campania_beneficio} como
 * «SOLO constancia»: lo que se cobra es lo que se debe, y una condonacion es un asiento con su
 * motivo que escribira quien tenga la ordenanza firmada.
 *
 * <p>Devolver el porcentaje declarado <b>no</b> es aplicarlo: es dato de la norma, transcrito al
 * registrar el beneficio, y sirve para que la pantalla lo muestre junto a la deuda sin que nadie
 * finja haber calculado nada.
 */
public interface BeneficiosDelContribuyente {

    /**
     * Los beneficios del contribuyente que <b>rigen</b> a esa fecha.
     *
     * <p>«Rigen a la fecha», no «los ultimos» (regla 9): un beneficio cesado en marzo no rige en
     * abril, y resolver «el ultimo» haria que una consulta de enero mostrara el que se dio de alta
     * en junio.
     *
     * <p>Vacia si el contribuyente no tiene ninguno. Que no tenga beneficios no es un error.
     *
     * @param contribuyenteId el titular; lo resolvio quien llama
     * @param aLaFecha la fecha a la que se pregunta por la vigencia
     */
    List<BeneficioRegistrado> vigentesA(long contribuyenteId, LocalDate aLaFecha);
}
