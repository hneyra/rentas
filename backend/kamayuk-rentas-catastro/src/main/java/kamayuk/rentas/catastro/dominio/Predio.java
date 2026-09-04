package kamayuk.rentas.catastro.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import org.jspecify.annotations.Nullable;

/**
 * El predio y su identificador.
 *
 * <p>Sin esto no hay ficha, no hay determinacion y no hay padron: el manual (cap. 2) llama al
 * padron «la herramienta fundamental que garantice el sostenimiento o incremento del importe a
 * cobrar».
 *
 * <p>El {@link CodigoReferenciaCatastral} valida su composicion tramo por tramo. <b>La longitud es
 * un parametro, no una constante</b> (D-10): la plantilla del manual da 23 posiciones y los
 * ejemplos del prototipo traen 21. Cerrar D-10 sera fijar el parametro, no reescribir la
 * validacion.
 *
 * <p>Aqui no se calcula nada. El autovaluo, el arancel y los valores unitarios son del contexto de
 * rentas y estan bloqueados por D-02a.
 *
 * @param id nulo mientras no se ha guardado
 * @param viaId la via de la direccion; nulo en un rustico, que no da a ninguna calle
 */
public record Predio(
        @Nullable Long id,
        CodigoReferenciaCatastral codigo,
        TipoPredio tipo,
        @Nullable Long viaId,
        @Nullable String numeroMunicipal,
        String direccion,
        @Nullable Long sectorId,
        @Nullable Long manzanaId,
        @Nullable String lote,
        @Nullable String ubigeo,
        EstadoPredio estado) {

    private static final int DIRECCION_MAXIMA = 300;
    private static final int NUMERO_MAXIMO = 20;
    private static final int LOTE_MAXIMO = 10;
    private static final int UBIGEO = 6;

    public Predio {
        Objects.requireNonNull(codigo, "El predio necesita su codigo de referencia catastral");
        Objects.requireNonNull(tipo, "El predio necesita su tipo");
        Objects.requireNonNull(direccion, "El predio necesita su direccion");
        Objects.requireNonNull(estado, "El predio necesita su estado");

        direccion = direccion.strip();
        if (direccion.isEmpty() || direccion.length() > DIRECCION_MAXIMA) {
            throw new IllegalArgumentException(
                    "La direccion del predio va de 1 a " + DIRECCION_MAXIMA + " caracteres");
        }
        if (numeroMunicipal != null && numeroMunicipal.strip().length() > NUMERO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El numero municipal excede " + NUMERO_MAXIMO + " caracteres");
        }
        if (lote != null && lote.strip().length() > LOTE_MAXIMO) {
            throw new IllegalArgumentException("El lote excede " + LOTE_MAXIMO + " caracteres");
        }
        if (ubigeo != null && ubigeo.length() != UBIGEO) {
            throw new IllegalArgumentException(
                    "El ubigeo son " + UBIGEO + " posiciones: '" + ubigeo + "'");
        }
        // Una manzana pertenece a un sector; referenciarla sin decir a cual deja el predio
        // colgando de una jerarquia incompleta que despues nadie sabe reconstruir.
        if (manzanaId != null && sectorId == null) {
            throw new IllegalArgumentException(
                    "Un predio con manzana necesita su sector: la manzana pertenece a uno");
        }
    }

    /** Un predio urbano que todavia no esta en la base. */
    public static Predio urbano(CodigoReferenciaCatastral codigo, String direccion) {
        return new Predio(
                null,
                codigo,
                TipoPredio.URBANO,
                null,
                null,
                direccion,
                null,
                null,
                null,
                null,
                EstadoPredio.ACTIVO);
    }

    public boolean esNuevo() {
        return id == null;
    }

    public boolean estaActivo() {
        return estado == EstadoPredio.ACTIVO;
    }

    /**
     * El ubigeo que declara el propio codigo catastral, en sus tres primeros tramos.
     *
     * <p>Sirve para contrastarlo con el de la municipalidad: un codigo cuyo ubigeo no es el del
     * distrito describe un predio de otra jurisdiccion.
     */
    public String ubigeoDelCodigo() {
        return codigo.ubigeo();
    }

    /** Se da de baja, nunca se borra: aparece en determinaciones ya emitidas (RNF-051). */
    public Predio dadoDeBaja() {
        return new Predio(
                id,
                codigo,
                tipo,
                viaId,
                numeroMunicipal,
                direccion,
                sectorId,
                manzanaId,
                lote,
                ubigeo,
                EstadoPredio.DADO_DE_BAJA);
    }

    /**
     * Vuelve a poner el predio en el padron.
     *
     * <p>Es la vuelta de {@link #dadoDeBaja()}, y existe porque sin ella la baja es una puerta de
     * un solo sentido: nada vuelve a admitir una ficha sobre un predio retirado por error.
     */
    public Predio reactivado() {
        return new Predio(
                id,
                codigo,
                tipo,
                viaId,
                numeroMunicipal,
                direccion,
                sectorId,
                manzanaId,
                lote,
                ubigeo,
                EstadoPredio.ACTIVO);
    }

    public Predio ubicadoEn(long sectorId, @Nullable Long manzanaId, @Nullable String lote) {
        return new Predio(
                id,
                codigo,
                tipo,
                viaId,
                numeroMunicipal,
                direccion,
                sectorId,
                manzanaId,
                lote,
                ubigeo,
                estado);
    }

    public Predio enLaVia(long viaId, @Nullable String numeroMunicipal) {
        return new Predio(
                id,
                codigo,
                tipo,
                viaId,
                numeroMunicipal,
                direccion,
                sectorId,
                manzanaId,
                lote,
                ubigeo,
                estado);
    }
}
