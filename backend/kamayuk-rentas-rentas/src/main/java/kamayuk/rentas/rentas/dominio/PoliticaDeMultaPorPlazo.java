package kamayuk.rentas.rentas.dominio;

import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;

/**
 * El importe de la multa tributaria por presentar una declaracion jurada fuera de plazo (RF-023).
 *
 * <p><b>El punto de extension, sin ninguna cifra.</b> D-02 bloquea el importe, no la deteccion de
 * fuera de plazo —esa la resuelve {@link DeclaracionJurada#fueraDePlazo}, comparando contra el
 * plazo parametrizado—. Lo que falta es cuanto vale la multa, y eso depende de una tabla normativa
 * (UIT, tramos de la infraccion tributaria) que todavia no tiene valores firmados.
 *
 * <p>Es el mismo mecanismo que {@code PoliticaDeMora} de #22: una interfaz pura —sin base de datos,
 * sin reloj, sin configuracion global (regla 6)— que {@code RegistrarDeclaracionJurada} podra
 * invocar el dia que D-02 cierre, sin que el caso de uso ni la firma cambien. Hasta entonces no
 * tiene ninguna implementacion de produccion: nada del flujo de registro la llama todavia, porque
 * no hay ningun importe de multa que mostrar.
 */
public interface PoliticaDeMultaPorPlazo {

    /**
     * El importe de la multa para esta declaracion, o {@link Dinero#CERO} si no corresponde —{@link
     * DeclaracionJurada#fueraDePlazo()} es falso, o el tipo no genera multa—.
     */
    Dinero multaPor(DeclaracionJurada declaracion, PoliticaDeRedondeo redondeo);
}
