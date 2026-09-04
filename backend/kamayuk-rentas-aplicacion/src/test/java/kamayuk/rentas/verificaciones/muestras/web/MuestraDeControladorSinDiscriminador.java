package kamayuk.rentas.verificaciones.muestras.web;

import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;

/**
 * Muestra que viola la guarda de #691: traduce «falta publicar una cifra normativa» a un 422
 * <b>sin</b> el discriminador.
 *
 * <p>No la instancia nadie. Existe para que {@code DiscriminadorDeLoQueFaltaPublicarTest} pueda
 * demostrar que la guarda muerde: una regla que no puede fallar no protege nada.
 *
 * <p>Los <b>cuatro</b> primeros metodos son las formas en que el defecto aparece de verdad, y son
 * las que el escaner tiene que encontrar. Los tres ultimos son el contraste, y son los que impiden
 * arreglarlo gritando siempre: dos traducen con el ayudante —uno por cada codigo— y el tercero no
 * compone ninguna respuesta.
 *
 * <p><b>El cuarto entro con #723</b>, y hasta entonces era uno de los buenos: se llamaba {@code
 * enReglaPorNoSer422} y el escaner lo dejaba pasar por contestar 404. Esa era exactamente la puerta
 * por la que las tres lecturas de cuadro de catastro llevaban mudas desde #691 — el miembro les
 * faltaba igual, y el codigo de estado no es lo que un programa lee.
 */
public final class MuestraDeControladorSinDiscriminador {

    private final LectorDeParametros parametros;

    public MuestraDeControladorSinDiscriminador(LectorDeParametros parametros) {
        this.parametros = parametros;
    }

    /** MALO: el ejercicio sin sellar sale como un 422 que no dice que le falta al sistema. */
    public ParametrosSellados sinConjuntoSellado(Ejercicio ejercicio) {
        try {
            return parametros.vigenteEn(ejercicio);
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, sinSellar.getMessage());
        }
    }

    /** MALO: y la fila que falta, igual. El mensaje la nombra; el cuerpo del problema, no. */
    public String sinLaFila(Ejercicio ejercicio) {
        try {
            return parametros.vigenteEn(ejercicio).exigirNumero("UIT", null).valor().toString();
        } catch (ParametrosSellados.ParametroAusente falta) {
            // Un comentario que mencione FaltaPublicar no arregla nada, y no debe dejarlo pasar.
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, falta.getMessage());
        }
    }

    /** MALO: la del dominio puro tambien, y esa ni siquiera declara la interfaz. */
    public String sinElPuntoDeRedondeo(Ejercicio ejercicio) {
        try {
            return parametros.vigenteEn(ejercicio).toString();
        } catch (PoliticasDeRedondeo.PuntoSinPolitica sinPolitica) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, sinPolitica.getMessage());
        }
    }

    /** BUENO: el mismo 422, traducido por el unico sitio que pone el discriminador. */
    public ParametrosSellados enRegla(Ejercicio ejercicio) {
        try {
            return parametros.vigenteEn(ejercicio);
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            throw FaltaPublicar.problema(sinSellar);
        }
    }

    /**
     * MALO desde #723: el 404 tampoco lleva el miembro, y por ahi se colaban las tres lecturas de
     * cuadro de catastro. Cambiar el numero no cambia lo que el cliente puede hacer con la
     * respuesta.
     */
    public String sinConjuntoSelladoEnUn404(Ejercicio ejercicio) {
        try {
            return parametros.vigenteEn(ejercicio).toString();
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, sinSellar.getMessage());
        }
    }

    /** BUENO: el mismo 404, por el sitio que si pone el discriminador (#723). */
    public String enReglaConUn404(Ejercicio ejercicio) {
        try {
            return parametros.vigenteEn(ejercicio).toString();
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            throw FaltaPublicar.noEncontrado(sinSellar);
        }
    }

    /**
     * BUENO: capturar una de la familia y <b>no componer ninguna respuesta</b> no es asunto de esta
     * guarda. Es lo que hace el resumen anual de licencias: cuenta los anios que si puede contar y
     * dice por que le falta la cifra del que no.
     */
    public String enReglaPorNoContestarNada(Ejercicio ejercicio) {
        try {
            return parametros.vigenteEn(ejercicio).toString();
        } catch (LectorDeParametros.EjercicioSinSellar sinSellar) {
            return "sin conjunto sellado: " + sinSellar.getMessage();
        }
    }
}
