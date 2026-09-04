package kamayuk.rentas.contribuyentes.infraestructura.web;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.OrigenContext;
import kamayuk.rentas.autorizacion.ComprobadorDeAcceso;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;

/**
 * Dar de baja exige {@code ELIMINACION}, aunque la ruta pida {@code MODIFICACION}.
 *
 * <p>La anotacion {@code @RequiereAcceso} declara lo que exige la <i>ruta</i>, y aqui una misma
 * ruta corrige o retira segun lo que traiga el cuerpo — que el guardia no lee, porque el
 * interceptor corre antes de que el cuerpo se analice. El privilegio {@code ELIMINACION} del manual
 * «gobierna la baja —desactivar—, no un {@code DELETE}» (ver {@link Privilegio}), asi que dejar las
 * bajas del padron solo bajo {@code MODIFICACION} lo volveria un privilegio que no gobierna nada.
 *
 * <p>Se pregunta por el <b>mismo puerto</b> que usa el guardia y se lanza el <b>mismo</b> {@code
 * ProblemaDeNegocio}: quien no lo tiene recibe el 403 de siempre y no distingue este camino del
 * otro —ni deduce, de paso, que su peticion llego a interpretarse—.
 *
 * <p>Vive fuera de los dos controladores porque los dos la necesitan —el padron para la baja del
 * contribuyente, la ficha para la del contacto y el cierre del vinculo— y dos copias de una
 * comprobacion de privilegio son dos sitios donde puede quedarse una desactualizada.
 */
final class GuardaDeBaja {

    private final ComprobadorDeAcceso comprobador;
    private final Clock reloj;

    GuardaDeBaja(ComprobadorDeAcceso comprobador, Clock reloj) {
        this.comprobador = comprobador;
        this.reloj = reloj;
    }

    void exigir(String acceso) {
        String usuario = OrigenContext.actual().usuario();
        if (!comprobador.autoriza(usuario, acceso, Privilegio.ELIMINACION, LocalDate.now(reloj))) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.SIN_PRIVILEGIO,
                    "No tiene el privilegio " + Privilegio.ELIMINACION + " sobre " + acceso);
        }
    }
}
