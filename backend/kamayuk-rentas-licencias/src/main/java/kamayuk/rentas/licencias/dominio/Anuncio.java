package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * La autorizacion municipal para instalar un elemento publicitario (#51, RF-114).
 *
 * <h2>No se edita</h2>
 *
 * <p>V45 le retira a {@code sgtm_app} el privilegio de {@code UPDATE}, y el escaner de fuentes
 * rechaza cualquier {@code UPDATE anuncio SET} antes de que llegue a ejecutarse. Los tres tramites
 * que la pantalla enumera —renovacion, cese, retiro— producen actos nuevos, no ediciones del
 * formulario.
 *
 * <h2>Su estado no esta aqui, y su vigencia vigente tampoco</h2>
 *
 * <p>No hay ningun campo {@code estado}: se deriva de {@link MovimientoDeAnuncio} y de la fecha a
 * la que se pregunte ({@link EstadoDelAnuncio#derivarDe}). Y {@link #vigenciaHasta} es la del
 * <b>acto fundacional</b>, no la de hoy: una renovacion prorroga sin tocar esta fila, asi que la
 * vigencia que rige se pregunta con {@link EstadoDelAnuncio#vigenciaSegun}.
 *
 * <h2>Ninguna cifra de tasa</h2>
 *
 * <p>Aqui estan las medidas declaradas —{@link #area} y {@link #lados}— y la {@link #clase}, que es
 * la que la ordenanza tarifa. Lo que <b>no</b> esta es cuanto cuesta: eso lo dice el conjunto
 * sellado bajo {@code TASA_ANUNCIO:<CLASE>} (D-02b, #199), y lo que se cobro de verdad queda
 * copiado en el movimiento que lo asento.
 *
 * @param id nulo mientras no se haya guardado
 * @param numero el numero de la autorizacion, el del papel del administrado
 * @param contribuyenteId el titular
 * @param predioId el predio donde se instala; opcional
 * @param licenciaId el establecimiento asociado, como licencia de funcionamiento (#44); opcional
 * @param clase la clase del elemento; de ella sale la llave de la tasa
 * @param tipo si el aviso es simple, luminoso, iluminado o electronico
 * @param emplazamiento donde se emplaza, tal como la pantalla lo ofrece; descriptivo
 * @param forma la forma del soporte; descriptiva
 * @param denominacion el texto o la marca que el anuncio exhibe
 * @param ubicacion la direccion donde esta instalado
 * @param area la superficie declarada del elemento
 * @param lados cuantas caras tiene; al menos una
 * @param cantidad cuantos elementos ampara la autorizacion
 * @param fechaAutorizacion el dia en que se autoriza; entra como argumento (regla 6)
 * @param vigenciaHasta hasta cuando rige <b>segun el acto fundacional</b>
 * @param expediente el numero de expediente del tramite
 * @param fechaExpediente su fecha
 * @param claveIdempotencia la cabecera {@code idempotency-key} del cliente; opcional
 * @param registradoEn el instante de registro, del reloj inyectado
 * @param usuarioRegistro quien lo registro; sale del origen de la sesion
 * @param observacion por que se autoriza (regla 10, RNF-052)
 */
public record Anuncio(
        @Nullable Long id,
        String numero,
        long contribuyenteId,
        @Nullable Long predioId,
        @Nullable Long licenciaId,
        ClaseDeAnuncio clase,
        TipoDeAnuncio tipo,
        @Nullable String emplazamiento,
        @Nullable String forma,
        @Nullable String denominacion,
        String ubicacion,
        AreaM2 area,
        int lados,
        int cantidad,
        LocalDate fechaAutorizacion,
        @Nullable LocalDate vigenciaHasta,
        @Nullable String expediente,
        @Nullable LocalDate fechaExpediente,
        @Nullable String claveIdempotencia,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    public Anuncio {
        Objects.requireNonNull(numero, "Una autorizacion sin numero no es una autorizacion");
        Objects.requireNonNull(clase, "El anuncio necesita su clase: de ella sale la tasa");
        Objects.requireNonNull(tipo, "El anuncio necesita su tipo");
        Objects.requireNonNull(ubicacion, "El anuncio necesita la direccion donde se instala");
        Objects.requireNonNull(area, "El anuncio necesita el area declarada del elemento");
        Objects.requireNonNull(
                fechaAutorizacion, "La fecha de autorizacion entra como argumento (regla 6)");
        Objects.requireNonNull(registradoEn, "El anuncio dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        numero = numero.strip();
        ubicacion = ubicacion.strip();
        if (numero.isEmpty()) {
            throw new IllegalArgumentException("El numero de la autorizacion no puede estar vacio");
        }
        if (ubicacion.isEmpty()) {
            throw new IllegalArgumentException(
                    "La direccion donde se instala el anuncio no puede estar vacia: sin ella no se"
                            + " puede fiscalizar");
        }
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException("La autorizacion es de un titular concreto");
        }
        if (area.esCero()) {
            throw new IllegalArgumentException(
                    "Un anuncio de area cero no ocupa nada: el area es lo que el expediente declara"
                            + " y lo que la ordenanza mide");
        }
        if (lados < 1) {
            throw new IllegalArgumentException(
                    "Un anuncio tiene al menos una cara; llegaron " + lados);
        }
        if (cantidad < 1) {
            throw new IllegalArgumentException(
                    "Una autorizacion ampara al menos un elemento; llegaron " + cantidad);
        }
        if (vigenciaHasta != null && vigenciaHasta.isBefore(fechaAutorizacion)) {
            throw new IllegalArgumentException(
                    "La vigencia de la autorizacion "
                            + numero
                            + " termina antes de empezar: nace vencida y nadie lo nota hasta que el"
                            + " titular reclama");
        }
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        Long guardado = id;
        if (guardado == null) {
            throw new IllegalStateException("Un anuncio sin guardar no tiene identificador");
        }
        return guardado;
    }
}
