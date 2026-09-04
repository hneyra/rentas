package kamayuk.rentas.licencias.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Un certificado de numeracion, zonificacion, parametros urbanisticos o jurisdiccion (#54, RF-115).
 *
 * <h2>No se edita, y por eso no tiene estado</h2>
 *
 * <p>V51 crea {@code certificado} <b>sin</b> conceder {@code UPDATE} ni {@code DELETE} a {@code
 * sgtm_app}, y el escaner de fuentes rechaza cualquier {@code UPDATE certificado SET} antes de que
 * llegue a ejecutarse. Un certificado equivocado se sustituye emitiendo <b>otro</b> —con su numero,
 * su derecho de tramite y su papel—, y los dos quedan. Es el mismo criterio que V39 aplico desde el
 * principio a la liquidacion de fiscalizacion.
 *
 * <p>Por eso tampoco hay columna {@code estado}: lo unico que le pasa a un certificado con el
 * tiempo es <b>caducar</b>, y eso no es un hecho suyo sino una relacion entre {@link
 * #vigenciaHasta} y el dia al que se pregunte. Se resuelve con {@link #vigenteA(LocalDate)}, que
 * recibe la fecha (regla 6, regla 9).
 *
 * <h2>La vigencia y el derecho van copiados, con su fecha</h2>
 *
 * <p>{@link #vigenciaHasta} se calculo con el parametro sellado que regia el dia de la emision, y
 * {@link #derecho} es lo que el recibo cobro, no lo que el TUPA cobraria hoy. Los dos se guardan y
 * no se recalculan: dentro de dos anios la norma puede ser otra y este papel ya esta en manos de
 * alguien. {@link #derechoA} es el dia al que corresponde el importe, y no es decorativo (regla 9,
 * RNF-075).
 *
 * @param id nulo mientras no se haya guardado
 * @param numero el numero del certificado, el que se cita en la escritura o el expediente
 * @param tipo que certifica; de el salen las dos llaves de parametros sellados
 * @param predioId el predio sobre el que se certifica
 * @param contribuyenteId el solicitante, que es su titular
 * @param codigoPredial el codigo de referencia catastral del predio, copiado el dia de la emision
 * @param direccion la direccion del predio, copiada el dia de la emision
 * @param expediente el numero de expediente del tramite; opcional
 * @param fechaEmision el dia de la emision; entra como argumento (regla 6)
 * @param vigenciaHasta hasta cuando vale, con el parametro que regia ese dia
 * @param reciboId el recibo de caja de tasas del derecho de tramite (RF-110)
 * @param derecho lo que ese recibo cobro por el concepto del certificado
 * @param derechoA el dia al que corresponde ese importe (regla 9, RNF-075)
 * @param documentoId el papel, como fila de {@code documento_emitido}
 * @param documentoNumero su numero impreso, para reimprimirlo sin cruzar tablas
 * @param parametros los parametros urbanisticos certificados, copiados
 * @param claveIdempotencia la cabecera {@code idempotency-key} del cliente; opcional
 * @param registradoEn el instante de registro, del reloj inyectado
 * @param usuarioRegistro quien lo emitio; sale del origen de la sesion
 * @param observacion por que se emite (regla 10, RNF-052)
 */
public record Certificado(
        @Nullable Long id,
        String numero,
        TipoDeCertificado tipo,
        long predioId,
        long contribuyenteId,
        String codigoPredial,
        String direccion,
        @Nullable String expediente,
        LocalDate fechaEmision,
        LocalDate vigenciaHasta,
        long reciboId,
        Dinero derecho,
        LocalDate derechoA,
        long documentoId,
        String documentoNumero,
        ParametrosUrbanisticos parametros,
        @Nullable String claveIdempotencia,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    public Certificado {
        Objects.requireNonNull(numero, "Un certificado sin numero no se puede citar");
        Objects.requireNonNull(tipo, "El certificado necesita saber que certifica");
        Objects.requireNonNull(codigoPredial, "El certificado copia el codigo del predio");
        Objects.requireNonNull(direccion, "El certificado necesita la direccion que certifica");
        Objects.requireNonNull(fechaEmision, "La fecha de emision entra como argumento (regla 6)");
        Objects.requireNonNull(vigenciaHasta, "Un certificado dice hasta cuando vale");
        Objects.requireNonNull(derecho, "El certificado copia lo que se cobro por el");
        Objects.requireNonNull(derechoA, "Toda cifra indica a que fecha esta (regla 9, RNF-075)");
        Objects.requireNonNull(documentoNumero, "El certificado necesita el numero de su papel");
        Objects.requireNonNull(parametros, "Los parametros son vacios, no nulos");
        Objects.requireNonNull(registradoEn, "El certificado dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        numero = numero.strip();
        codigoPredial = codigoPredial.strip();
        direccion = direccion.strip();
        if (codigoPredial.isEmpty()) {
            throw new IllegalArgumentException(
                    "El codigo de referencia catastral del predio no puede estar vacio: es por el"
                            + " que se busca el certificado en la grilla");
        }
        if (numero.isEmpty()) {
            throw new IllegalArgumentException("El numero del certificado no puede estar vacio");
        }
        if (direccion.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un certificado sin direccion no identifica el predio que certifica");
        }
        if (predioId <= 0) {
            throw new IllegalArgumentException(
                    "Un certificado de numeracion o zonificacion es SOBRE un predio: sin el no"
                            + " certifica nada");
        }
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "El certificado se emite a un solicitante del padron: sin el no hay a quien"
                            + " entregarlo ni de quien exigir el derecho de tramite");
        }
        if (reciboId <= 0) {
            throw new IllegalArgumentException(
                    "Sin el recibo del derecho de tramite no se emite (RF-110, RF-115)");
        }
        if (derecho.esNegativo()) {
            throw new IllegalArgumentException(
                    "Un derecho de tramite negativo no es un cobro: llego "
                            + derecho.valor().toPlainString());
        }
        if (vigenciaHasta.isBefore(fechaEmision)) {
            throw new IllegalArgumentException(
                    "El certificado "
                            + numero
                            + " caducaria el "
                            + vigenciaHasta
                            + ", antes de emitirse el "
                            + fechaEmision
                            + ": nace vencido y nadie lo nota hasta que se lo rechazan al"
                            + " administrado");
        }
    }

    /** Si todavia no se guardo. */
    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, ya guardado. */
    public long identificador() {
        return Objects.requireNonNull(
                id, "El certificado todavia no se guardo: no tiene identificador");
    }

    /**
     * Si el certificado sigue valiendo ese dia.
     *
     * <p>La fecha entra como argumento y no sale del reloj: «caducado» no es un hecho del
     * certificado sino una relacion entre su vigencia y un dia, y un padron con fecha de corte de
     * marzo tiene que decir lo que decia en marzo (regla 6, regla 9).
     */
    public boolean vigenteA(LocalDate fecha) {
        Objects.requireNonNull(fecha, "La vigencia se pregunta a una fecha (regla 6, regla 9)");
        return !fecha.isBefore(fechaEmision) && !fecha.isAfter(vigenciaHasta);
    }

    /** La palabra con que la grilla pinta su columna «Estado». */
    public String estadoA(LocalDate fecha) {
        return vigenteA(fecha) ? "VIGENTE" : "CADUCADO";
    }

    /** El mismo certificado, con el identificador que le puso la base. */
    public Certificado con(long identificador) {
        return new Certificado(
                identificador,
                numero,
                tipo,
                predioId,
                contribuyenteId,
                codigoPredial,
                direccion,
                expediente,
                fechaEmision,
                vigenciaHasta,
                reciboId,
                derecho,
                derechoA,
                documentoId,
                documentoNumero,
                parametros,
                claveIdempotencia,
                registradoEn,
                usuarioRegistro,
                observacion);
    }
}
