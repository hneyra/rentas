package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La licencia municipal de funcionamiento (#44, RF-110).
 *
 * <h2>No se edita</h2>
 *
 * <p>V37 le retira a {@code sgtm_app} el privilegio de {@code UPDATE}, y el escaner de fuentes
 * rechaza cualquier {@code UPDATE licencia_funcionamiento SET} antes de que llegue a ejecutarse. No
 * es purismo: la licencia es un acto administrativo que el titular se lleva impreso y cuelga en el
 * establecimiento. Corregirla en la base deja al papel y al sistema diciendo cosas distintas, y
 * quien tiene el papel gana la discusion. Los seis «procesos» que la pantalla enumera —renovacion,
 * ampliacion de giro, cambio de titular, cese— son tramites que producen actos nuevos, no ediciones
 * de un formulario.
 *
 * <h2>Su estado no esta aqui</h2>
 *
 * <p>No hay ningun campo {@code estado}: se deriva de {@link MovimientoDeLicencia} y de la fecha a
 * la que se pregunte ({@link EstadoDeLicencia#derivarDe}). Ver V37 §1.
 *
 * @param id nulo mientras no se haya guardado
 * @param numero el numero de la licencia municipal, el del papel del establecimiento
 * @param contribuyenteId el titular
 * @param predioId donde esta el establecimiento; opcional, porque hay giros sin predio empadronado
 * @param fichaId la version de la ficha economica del predio vigente al emitir (#19); opcional
 * @param nombreComercial la denominacion comercial
 * @param direccion la direccion del establecimiento
 * @param areaSolicitada el area del establecimiento
 * @param tipoLicencia definitiva, temporal o cesionaria
 * @param zonificacion la zona del indice de usos declarada
 * @param aforo el aforo autorizado
 * @param fechaEmision el dia en que se emite; entra como argumento (regla 6)
 * @param vigenciaHasta hasta cuando rige; obligatorio en una temporal
 * @param reciboId el recibo de caja de tasas del derecho de tramite (RF-110)
 * @param documentoId la fila de {@code documento_emitido} que la materializa
 * @param expediente el numero de expediente del tramite
 * @param fechaExpediente su fecha
 * @param registradoEn el instante de registro, del reloj inyectado
 * @param usuarioRegistro quien la registro; sale del origen de la sesion
 * @param observacion por que se emite (regla 10, RNF-052)
 * @param giros los giros CIIU autorizados; al menos uno, exactamente uno principal
 */
public record LicenciaDeFuncionamiento(
        @Nullable Long id,
        String numero,
        long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long fichaId,
        String nombreComercial,
        String direccion,
        AreaM2 areaSolicitada,
        TipoDeLicencia tipoLicencia,
        @Nullable String zonificacion,
        @Nullable Integer aforo,
        LocalDate fechaEmision,
        @Nullable LocalDate vigenciaHasta,
        long reciboId,
        long documentoId,
        @Nullable String expediente,
        @Nullable LocalDate fechaExpediente,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion,
        List<GiroDeLaLicencia> giros) {

    public LicenciaDeFuncionamiento {
        Objects.requireNonNull(numero, "Una licencia sin numero no es una licencia");
        Objects.requireNonNull(nombreComercial, "La licencia necesita su denominacion comercial");
        Objects.requireNonNull(direccion, "La licencia necesita la direccion del establecimiento");
        Objects.requireNonNull(areaSolicitada, "La licencia necesita el area del establecimiento");
        Objects.requireNonNull(tipoLicencia, "La licencia necesita su tipo");
        Objects.requireNonNull(fechaEmision, "La fecha de emision entra como argumento (regla 6)");
        Objects.requireNonNull(registradoEn, "La licencia dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        Objects.requireNonNull(giros, "La lista de giros es vacia, no nula");

        numero = numero.strip();
        nombreComercial = nombreComercial.strip();
        direccion = direccion.strip();
        if (numero.isEmpty()) {
            throw new IllegalArgumentException("El numero de la licencia no puede estar vacio");
        }
        if (nombreComercial.isEmpty()) {
            throw new IllegalArgumentException("La denominacion comercial no puede estar vacia");
        }
        if (direccion.isEmpty()) {
            throw new IllegalArgumentException(
                    "La direccion del establecimiento no puede estar vacia");
        }
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException("La licencia es de un titular concreto");
        }
        if (aforo != null && aforo <= 0) {
            throw new IllegalArgumentException("Un aforo de cero personas no autoriza nada");
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(fechaEmision)) {
            throw new IllegalArgumentException(
                    "La vigencia de la licencia " + numero + " termina antes de empezar");
        }
        if (tipoLicencia.exigeVigencia() && vigenciaHasta == null) {
            throw new IllegalArgumentException(
                    "Una licencia "
                            + tipoLicencia.etiqueta().toLowerCase(java.util.Locale.ROOT)
                            + " sin fecha de vencimiento no es temporal: es una definitiva mal"
                            + " rotulada");
        }
        giros = List.copyOf(giros);
        if (giros.isEmpty()) {
            throw new IllegalArgumentException(
                    "Una licencia sin ningun giro no autoriza ninguna actividad (RF-110)");
        }
        long principales = giros.stream().filter(GiroDeLaLicencia::principal).count();
        if (principales != 1) {
            throw new IllegalArgumentException(
                    "Una licencia tiene exactamente un giro principal, y llegaron "
                            + principales
                            + ": la actividad principal es la que decide el riesgo de la ITSE y la"
                            + " compatibilidad con la zonificacion");
        }
        long distintos = giros.stream().map(GiroDeLaLicencia::ciiuId).distinct().count();
        if (distintos != giros.size()) {
            throw new IllegalArgumentException(
                    "El mismo giro no se autoriza dos veces en la misma licencia");
        }
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        Long guardado = id;
        if (guardado == null) {
            throw new IllegalStateException("Una licencia sin guardar no tiene identificador");
        }
        return guardado;
    }
}
