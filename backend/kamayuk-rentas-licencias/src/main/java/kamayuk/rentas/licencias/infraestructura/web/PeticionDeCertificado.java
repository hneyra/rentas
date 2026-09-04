package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla {@code certificados} manda para emitir uno (#54, RF-115).
 *
 * <p>Las claves son las que declara su seccion «Datos del certificado»: {@code tipoDeCertificado},
 * {@code codigoPredial}, {@code solicitante}, {@code nDeExpediente}, {@code zonificacion}, {@code
 * alturaMaximaPermitida}, {@code areaLibreMinima}, {@code retiroMunicipal}, {@code
 * coeficienteDeEdificacion}.
 *
 * <p><b>{@code derechoDeTramiteS} y {@code vigencia} NO estan aqui</b>, y la pantalla los pinta
 * como campos de solo lectura precisamente por eso: el importe del derecho lo dice el recibo —el
 * backend se lo pregunta a {@code tesoreria}— y la vigencia sale del conjunto sellado. Aceptarlos
 * del cliente convertiria dos datos que el sistema sabe en dos que el operador puede teclear mal, y
 * el segundo acabaria impreso en el papel.
 *
 * <p>{@code nDeRecibo} si viene del cliente: es el numero impreso del recibo que el administrado
 * trae a ventanilla, y sin el no hay nada que comprobar (RF-115).
 *
 * @param observacion por que se emite; obligatoria (regla 10, RNF-052)
 */
public record PeticionDeCertificado(
        @Nullable String tipoDeCertificado,
        @Nullable String codigoPredial,
        @Nullable String solicitante,
        @Nullable String nDeExpediente,
        @Nullable String fechaDeEmision,
        @Nullable String nDeRecibo,
        @Nullable String zonificacion,
        @Nullable String alturaMaximaPermitida,
        @Nullable String areaLibreMinima,
        @Nullable String retiroMunicipal,
        @Nullable String coeficienteDeEdificacion,
        @Nullable String formato,
        @Nullable String observacion) {}
