package kamayuk.rentas.coactiva.dobles;

import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;

/**
 * Un {@link LectorDeParametros} con un solo conjunto sellado dentro: el del plazo de la REC-1.
 *
 * <p><b>El valor entra por el constructor, no compilado en la clase.</b> Es un doble de prueba, y
 * aun asi la cifra viaja como dato: lo contrario seria escribir «7» en el codigo y quedarse sin
 * poder probar que pasa cuando la norma diga otra cosa.
 *
 * <p>La prueba de que el plazo sale de verdad del conjunto sellado —y de que la operacion falla
 * cuando falta— es {@code ActosCoactivosJdbcTest} contra PostgreSQL. Aqui solo se necesita que el
 * transporte HTTP tenga un plazo con el que trabajar.
 */
public final class PlazosDeMentira implements LectorDeParametros {

    /** El identificador del conjunto que este doble finge tener sellado. */
    public static final long CONJUNTO = 77L;

    private final @org.jspecify.annotations.Nullable String plazoDeLaRec1;

    private boolean sinSellar;

    public PlazosDeMentira(@org.jspecify.annotations.Nullable String plazoDeLaRec1) {
        this.plazoDeLaRec1 = plazoDeLaRec1;
    }

    /**
     * Ningun conjunto sellado rige el ejercicio, que es lo que ocurre <b>hoy</b> en todas las
     * municipalidades con D-02a abierta (#562).
     *
     * <p>No es lo mismo que un conjunto sin la llave —para eso basta construirlo con {@code null}—:
     * ahi hay un conjunto y le falta una cifra, y aqui no hay conjunto. Las dos situaciones se
     * distinguen en el mensaje —una nombra la llave y la otra el ejercicio— y por eso el doble sabe
     * fingir las dos.
     */
    public PlazosDeMentira sinSellar() {
        this.sinSellar = true;
        return this;
    }

    @Override
    public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
        if (sinSellar) {
            throw new EjercicioSinSellar(ejercicio);
        }
        ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
        if (plazoDeLaRec1 != null) {
            constructor.texto("PLAZO", "REC1_CUMPLIMIENTO", plazoDeLaRec1);
        }
        return constructor.construir();
    }

    @Override
    public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
        return vigenteEn(new Ejercicio(2026));
    }

    @Override
    public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
        if (sinSellar) {
            throw new EjercicioSinSellar(ejercicio);
        }
        return IdentificadorDeConjunto.de(CONJUNTO);
    }
}
