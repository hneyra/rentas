package kamayuk.rentas.fiscalizacion.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La inspección de campo de un predio o un vehículo, sobre una <b>copia</b>: nada de lo que guarda
 * este tipo toca {@code catastro} ni {@code rentas} (ARQ-01 §3.5, AC de #45).
 *
 * <p>Es un solo tipo para las dos familias —predial y vehicular—, porque comparten tabla ({@code
 * acta_fiscalizacion}, V4) y ciclo de vida; lo que cambia es cuál de {@code predioId}/{@code
 * vehiculoId} trae valor, exigido en el compacto y reforzado en la base por {@code
 * acta_fisc_predio_xor_vehiculo_ck} (V24).
 *
 * <p>{@code fichaId} es la versión de {@code ficha_catastral} vigente a {@code fechaVisita} —nunca
 * la actual—, para que comparar lo hallado contra lo declarado sea reproducible más adelante
 * (RNF-075), igual que {@code fichaCatastralId} en {@code DeclaracionJurada} (#28). Solo tiene
 * sentido en un acta predial: una fiscalización vehicular no versiona ficha alguna.
 *
 * <p>{@code usoHallado} es lo que la inspección observó, y desde #599 vive aquí: hasta entonces el
 * acta guardaba el área y ninguna columna de uso, así que el uso lo tecleaba <b>quien liquidaba</b>
 * y quien visitó no podía dejarlo escrito. Con él, {@link Hallazgo} gana su quinto valor. Las dos
 * reglas que lo acompañan están abajo, en el compacto, y otra vez en la base ({@code
 * acta_fisc_uso_hallado_predial_ck} y {@code acta_fisc_uso_distinto_ck}, V76): sólo un acta predial
 * lo consigna —un vehículo no tiene uso declarado contra el que contrastar— y un acta que declara
 * {@code USO_DISTINTO} tiene que decir cuál es ese uso. De las dos sale, sin escribirla, la
 * tercera: un acta vehicular no puede declarar {@code USO_DISTINTO}.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param programaId el programa que la originó
 * @param version la visita número N sobre este contribuyente dentro del mismo programa
 * @param contribuyenteId a quién se fiscaliza
 * @param predioId el predio inspeccionado, si el acta es predial
 * @param vehiculoId el vehículo inspeccionado, si el acta es vehicular
 * @param fichaId la versión de ficha vigente a la visita, si el acta es predial y el predio tiene
 *     ficha registrada
 * @param fechaVisita cuándo se hizo la inspección
 * @param fiscalizador quién la hizo
 * @param hallazgo qué encontró, si ya se determinó
 * @param areaHallada el área que el fiscalizador midió en campo, si aplica
 * @param usoHallado el uso que la inspección observó, si se consignó. Sólo un acta predial lo
 *     lleva, y toda acta que declare {@link Hallazgo#USO_DISTINTO} tiene que traerlo
 * @param detalle notas libres de la inspección
 * @param estado en qué punto está
 * @param observacion por qué se registra (regla 10)
 */
public record ActaFiscalizacion(
        @Nullable Long id,
        long programaId,
        int version,
        long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long vehiculoId,
        @Nullable Long fichaId,
        LocalDate fechaVisita,
        String fiscalizador,
        @Nullable Hallazgo hallazgo,
        @Nullable AreaM2 areaHallada,
        @Nullable String usoHallado,
        @Nullable String detalle,
        EstadoDeActa estado,
        Observacion observacion) {

    private static final int FISCALIZADOR_MAXIMO = 60;
    private static final int DETALLE_MAXIMO = 1000;

    /** El mismo largo que {@code ficha_catastral.uso}, que es el lado declarado (V1, V76). */
    private static final int USO_MAXIMO = 60;

    public ActaFiscalizacion {
        if (programaId <= 0) {
            throw new IllegalArgumentException("El acta necesita el programa que la origino");
        }
        if (version <= 0) {
            throw new IllegalArgumentException("La version de un acta empieza en 1");
        }
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "El acta necesita el identificador del contribuyente fiscalizado");
        }
        if ((predioId == null) == (vehiculoId == null)) {
            throw new IllegalArgumentException(
                    "Un acta es de un predio o de un vehiculo, nunca de los dos ni de ninguno");
        }
        if (fichaId != null && predioId == null) {
            throw new IllegalArgumentException("Solo un acta predial referencia una ficha");
        }
        usoHallado = normalizarUso(usoHallado);
        if (usoHallado != null && predioId == null) {
            throw new IllegalArgumentException(
                    "Solo un acta predial consigna el uso hallado: un vehiculo no tiene uso"
                            + " declarado contra el que contrastarlo");
        }
        if (hallazgo == Hallazgo.USO_DISTINTO && usoHallado == null) {
            throw new IllegalArgumentException(
                    "Un acta que anota USO_DISTINTO tiene que decir cual es el uso observado: sin"
                            + " el afirma un hallazgo que no puede sustentar");
        }
        Objects.requireNonNull(fechaVisita, "El acta necesita la fecha de la visita");
        Objects.requireNonNull(fiscalizador, "El acta necesita quien la hizo");
        fiscalizador = fiscalizador.strip();
        if (fiscalizador.isEmpty() || fiscalizador.length() > FISCALIZADOR_MAXIMO) {
            throw new IllegalArgumentException(
                    "El fiscalizador va de 1 a " + FISCALIZADOR_MAXIMO + " caracteres");
        }
        if (detalle != null) {
            detalle = detalle.strip();
            if (detalle.isEmpty()) {
                detalle = null;
            } else if (detalle.length() > DETALLE_MAXIMO) {
                throw new IllegalArgumentException(
                        "El detalle no puede superar " + DETALLE_MAXIMO + " caracteres");
            }
        }
        Objects.requireNonNull(estado, "El acta necesita su estado");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda un acta (regla 10)");
    }

    /** Un acta predial nueva, todavía sin guardar. */
    public static ActaFiscalizacion nuevaPredial(
            long programaId,
            int version,
            long contribuyenteId,
            long predioId,
            @Nullable Long fichaId,
            LocalDate fechaVisita,
            String fiscalizador,
            @Nullable Hallazgo hallazgo,
            @Nullable AreaM2 areaHallada,
            @Nullable String usoHallado,
            @Nullable String detalle,
            Observacion observacion) {
        return new ActaFiscalizacion(
                null,
                programaId,
                version,
                contribuyenteId,
                predioId,
                null,
                fichaId,
                fechaVisita,
                fiscalizador,
                hallazgo,
                areaHallada,
                usoHallado,
                detalle,
                EstadoDeActa.ABIERTA,
                observacion);
    }

    /** Un acta vehicular nueva, todavía sin guardar: nunca lleva ficha, ni área, ni uso. */
    public static ActaFiscalizacion nuevaVehicular(
            long programaId,
            int version,
            long contribuyenteId,
            long vehiculoId,
            LocalDate fechaVisita,
            String fiscalizador,
            @Nullable Hallazgo hallazgo,
            @Nullable String detalle,
            Observacion observacion) {
        return new ActaFiscalizacion(
                null,
                programaId,
                version,
                contribuyenteId,
                null,
                vehiculoId,
                null,
                fechaVisita,
                fiscalizador,
                hallazgo,
                null,
                null,
                detalle,
                EstadoDeActa.ABIERTA,
                observacion);
    }

    /**
     * El uso hallado, recortado; el vacío es que no se consignó.
     *
     * <p>No se normaliza el <b>contenido</b> —ni mayúsculas ni tildes—: el lado declarado es {@code
     * ficha_catastral.uso}, texto libre por municipalidad, y {@link ComparacionHalladoDeclarado}
     * los compara ignorando mayúsculas. Reescribirlo aquí cambiaría lo que el acta dice que se vio.
     */
    private static @Nullable String normalizarUso(@Nullable String uso) {
        if (uso == null) {
            return null;
        }
        String limpio = uso.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        if (limpio.length() > USO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El uso hallado no puede superar " + USO_MAXIMO + " caracteres");
        }
        return limpio;
    }

    public boolean esNueva() {
        return id == null;
    }

    public boolean esPredial() {
        return predioId != null;
    }

    /**
     * Esta misma acta, anulada (#214). Ninguno de sus demas datos cambia.
     *
     * <p><b>Es la unica transicion que existe</b>, y la maquina de estados cabe en dos lineas
     * porque el enumerado tiene dos valores: {@link EstadoDeActa} explica por que los otros tres se
     * derivan en vez de guardarse. Anular es terminal —una anulada no revive—, y eso no deja a
     * nadie sin salida: la correccion de una visita es <b>otra visita</b>, o sea otra version sobre
     * la misma unidad, que es justamente lo que {@code acta_fisc_version_uq} admite desde V60.
     *
     * <p>La comprobacion vive aqui y no en el caso de uso porque es una propiedad del acta:
     * cualquier camino que la anule manana pasa por esta puerta sin tener que acordarse. Es el
     * patron de {@code DeclaracionJurada#anulada} (#365).
     */
    public ActaFiscalizacion anulada() {
        if (estado.esTerminal()) {
            throw new TransicionIlegal(id, estado, EstadoDeActa.ANULADA);
        }
        return new ActaFiscalizacion(
                id,
                programaId,
                version,
                contribuyenteId,
                predioId,
                vehiculoId,
                fichaId,
                fechaVisita,
                fiscalizador,
                hallazgo,
                areaHallada,
                usoHallado,
                detalle,
                EstadoDeActa.ANULADA,
                observacion);
    }

    /**
     * Exige que la visita cuente: la regla que sostiene todo lo que parte de un acta (#339).
     *
     * <p>#214 hizo alcanzable {@code ANULADA}, y de los casos de uso que parten de un acta solo el
     * que la anula miraba su estado: liquidar, reliquidar y transferir seguian adelante sobre una
     * visita que la administracion ya habia declarado invalida, hasta emitir una resolucion de
     * determinacion sustentada en ella. La invariante vive <b>aqui</b> y no en tres {@code if}
     * sueltos por lo mismo que {@link #anulada}: un camino nuevo que parta de un acta pasa por esta
     * puerta sin tener que reescribir la regla.
     *
     * <p>Se decide con {@link EstadoDeActa#estaViva()}, que es el predicado en su forma de objeto
     * del {@code estado <> 'ANULADA'} que escriben las tres consultas del repositorio: el caso de
     * uso y el SQL no pueden discrepar sobre que visita cuenta.
     *
     * @throws ActaAnulada si la visita ya no vale
     */
    public void exigirViva() {
        if (!estado.estaViva()) {
            throw new ActaAnulada(id);
        }
    }

    /**
     * El acta esta anulada, y una visita anulada no sostiene ninguna liquidacion ni ninguna
     * resolucion de determinacion (#339). No hay nada que corregir en ella: lo que procede es
     * levantar otra acta —otra version sobre la misma unidad— y liquidar esa.
     */
    public static final class ActaAnulada extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ActaAnulada(@Nullable Long id) {
            super(
                    "El acta "
                            + id
                            + " esta anulada: una visita que no vale no sostiene ninguna"
                            + " liquidacion ni resolucion de determinacion. Lo que procede es"
                            + " levantar otra acta sobre la misma unidad y liquidar esa");
        }
    }

    /** El acta no admite ese acto en el estado en que esta. */
    public static final class TransicionIlegal extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TransicionIlegal(@Nullable Long id, EstadoDeActa desde, EstadoDeActa hasta) {
            super(
                    "El acta "
                            + id
                            + " esta "
                            + desde
                            + " y no puede pasar a "
                            + hasta
                            + ": una anulada no revive, y corregir una visita es levantar otra"
                            + " acta");
        }
    }
}
