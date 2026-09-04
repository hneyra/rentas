package kamayuk.rentas.licencias.dobles;

import java.util.LinkedHashMap;
import java.util.Map;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.licencias.dominio.ClaseDeAnuncio;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;

/**
 * Un {@link LectorDeParametros} con las tarifas de anuncios de la prueba dentro (#51).
 *
 * <p><b>Las cifras entran por el constructor, no compiladas en la clase.</b> Es un doble de prueba,
 * y aun asi el dato viaja como dato: lo contrario seria escribir aqui una tarifa —que ademas es de
 * ordenanza local, D-02b, y la espera #199— y quedarse sin poder probar que pasa cuando la
 * municipalidad tarife otra cosa.
 *
 * <p>Un conjunto <b>sin</b> la clase que se pide es lo que hace falta para probar que el registro
 * falla nombrando la llave en vez de cobrar cero.
 */
public final class TarifasDeMentira implements LectorDeParametros {

    public static final long CONJUNTO = 51L;

    private static final String TIPO = "TASA_ANUNCIO";

    private final Map<ClaseDeAnuncio, String> tarifas = new LinkedHashMap<>();

    private boolean sinSellar;

    /** Declara la tarifa de una clase. Sin llamadas, el conjunto no tarifa nada. */
    public TarifasDeMentira con(ClaseDeAnuncio clase, String importe) {
        tarifas.put(clase, importe);
        return this;
    }

    /**
     * Ningun conjunto sellado rige el ejercicio, que es lo que ocurre <b>hoy</b> en todas las
     * municipalidades con D-02a abierta (#562).
     *
     * <p>No es lo mismo que un conjunto sin la tarifa —para eso basta no declarar ninguna—: ahi hay
     * un conjunto y le falta una cifra, y aqui no hay conjunto. Las dos situaciones se distinguen
     * en el mensaje, una nombra la llave y la otra el ejercicio.
     */
    public TarifasDeMentira sinSellar() {
        this.sinSellar = true;
        return this;
    }

    @Override
    public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
        if (sinSellar) {
            throw new EjercicioSinSellar(ejercicio);
        }
        ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
        for (Map.Entry<ClaseDeAnuncio, String> tarifa : tarifas.entrySet()) {
            constructor.numero(
                    TIPO, tarifa.getKey().claveDeLaTasa(), ValorNormativo.de(tarifa.getValue()));
        }
        return constructor.construir();
    }

    @Override
    public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
        return vigenteEn(new Ejercicio(2026));
    }

    @Override
    public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
        return IdentificadorDeConjunto.de(CONJUNTO);
    }
}
