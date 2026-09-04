package kamayuk.rentas.licencias.dobles;

import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.licencias.dominio.TipoDeCertificado;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.jspecify.annotations.Nullable;

/**
 * Un {@link LectorDeParametros} con un solo conjunto sellado dentro: los conceptos del TUPA que
 * cobran el derecho de tramite de una licencia y de su duplicado.
 *
 * <p><b>Los codigos entran por el constructor, no compilados en la clase.</b> Es un doble de
 * prueba, y aun asi el dato viaja como dato: lo contrario seria escribir un codigo de TUPA en el
 * codigo y quedarse sin poder probar que pasa cuando la municipalidad use otro.
 *
 * <p>Un codigo {@code null} significa «ese parametro no esta en el conjunto», que es lo que hace
 * falta para probar que la operacion falla nombrando la llave.
 */
public final class DerechosDeMentira implements LectorDeParametros {

    public static final long CONJUNTO = 41L;

    private final @Nullable String conceptoDeLaLicencia;
    private final @Nullable String conceptoDelDuplicado;
    private @Nullable String conceptoDeLaEdificacion;
    private @Nullable String conceptoDeLaRevalidacion;

    private final java.util.Map<TipoDeCertificado, String> conceptosDeCertificado =
            new java.util.EnumMap<>(TipoDeCertificado.class);

    private final java.util.Map<TipoDeCertificado, Integer> vigenciasDeCertificado =
            new java.util.EnumMap<>(TipoDeCertificado.class);

    public DerechosDeMentira(
            @Nullable String conceptoDeLaLicencia, @Nullable String conceptoDelDuplicado) {
        this.conceptoDeLaLicencia = conceptoDeLaLicencia;
        this.conceptoDelDuplicado = conceptoDelDuplicado;
    }

    /**
     * Los dos conceptos del FUE de edificacion (#48).
     *
     * <p>Un {@code null} significa «ese parametro no esta en el conjunto», igual que arriba: es lo
     * que hace falta para probar que la emision falla nombrando la llave en vez de admitir
     * cualquier recibo.
     */
    public DerechosDeMentira conEdificacion(
            @Nullable String deLaLicencia, @Nullable String deLaRevalidacion) {
        this.conceptoDeLaEdificacion = deLaLicencia;
        this.conceptoDeLaRevalidacion = deLaRevalidacion;
        return this;
    }

    /**
     * Lo que el TUPA dice de un tipo de certificado (#54): su concepto y sus meses de vigencia.
     *
     * <p><b>Las dos mitades se siembran por separado y las dos pueden faltar</b>, que es lo que
     * hace demostrable la regla 5 en sus dos formas: sin el concepto no se sabe que recibo vale, y
     * sin los meses no se sabe hasta cuando vale el papel. Ninguna de las dos tiene valor por
     * omision, y por eso hay que poder montar el doble sin ellas.
     *
     * <p>Los meses entran como dato del constructor, no compilados: una cifra escrita aqui seria
     * una cifra normativa en el codigo aunque fuera de prueba, y ademas dejaria sin poder probar
     * que pasa cuando la municipalidad use otra.
     */
    public DerechosDeMentira conCertificado(
            TipoDeCertificado tipo, @Nullable String concepto, @Nullable Integer meses) {
        if (concepto != null) {
            conceptosDeCertificado.put(tipo, concepto);
        }
        if (meses != null) {
            vigenciasDeCertificado.put(tipo, meses);
        }
        return this;
    }

    /**
     * Ningun conjunto sellado rige el ejercicio, que es lo que ocurre <b>hoy</b> en todas las
     * municipalidades con D-02a abierta (#562).
     *
     * <p>No es lo mismo que un conjunto sin la llave —para eso basta pasar {@code null} en el
     * constructor—: ahi hay un conjunto y le falta una cifra, y aqui no hay conjunto. Las dos
     * situaciones se distinguen en el mensaje —una nombra la llave y la otra el ejercicio— y por
     * eso el doble sabe fingir las dos.
     */
    public DerechosDeMentira sinSellar() {
        this.sinSellar = true;
        return this;
    }

    private boolean sinSellar;

    @Override
    public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
        if (sinSellar) {
            throw new EjercicioSinSellar(ejercicio);
        }
        ParametrosSellados.Constructor constructor = ParametrosSellados.de(ejercicio, 1);
        if (conceptoDeLaLicencia != null) {
            constructor.texto("TUPA", "DERECHO_LICENCIA_FUNCIONAMIENTO", conceptoDeLaLicencia);
        }
        if (conceptoDelDuplicado != null) {
            constructor.texto("TUPA", "DERECHO_DUPLICADO_LICENCIA", conceptoDelDuplicado);
        }
        if (conceptoDeLaEdificacion != null) {
            constructor.texto("TUPA", "DERECHO_LICENCIA_EDIFICACION", conceptoDeLaEdificacion);
        }
        if (conceptoDeLaRevalidacion != null) {
            constructor.texto("TUPA", "DERECHO_REVALIDACION_EDIFICACION", conceptoDeLaRevalidacion);
        }
        for (java.util.Map.Entry<TipoDeCertificado, String> concepto :
                conceptosDeCertificado.entrySet()) {
            constructor.texto("TUPA", concepto.getKey().claveDelDerecho(), concepto.getValue());
        }
        for (java.util.Map.Entry<TipoDeCertificado, Integer> vigencia :
                vigenciasDeCertificado.entrySet()) {
            constructor.numero(
                    "VIGENCIA_CERTIFICADO",
                    vigencia.getKey().claveDeLaVigencia(),
                    new ValorNormativo(java.math.BigDecimal.valueOf(vigencia.getValue())));
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
