package kamayuk.rentas.sanciones.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/transito/constancias-libres} (#53, RF-068). <b>Lista blanca</b>:
 * lo que no está aquí no entra.
 *
 * @param placa el vehículo sobre el que se acredita
 * @param vehiculoId el vehículo del padrón, si está registrado; una placa que no lo está también
 *     puede pedir la constancia
 * @param solicitanteId quién la pide, si se identificó
 * @param solicitante su nombre, para el papel
 * @param verificadaAl el día al que se acredita; si falta, hoy. <b>No es la fecha de emisión</b>:
 *     «no tiene papeletas pendientes» es cierto o falso según el día (regla 9, RNF-075)
 * @param formato en qué formato sale el papel: PDF, XLS o RTF; si falta, PDF (RF-132)
 * @param observacion por qué se emite (regla 10, RNF-052)
 */
public record PeticionDeConstanciaLibre(
        @Nullable String placa,
        @Nullable Long vehiculoId,
        @Nullable Long solicitanteId,
        @Nullable String solicitante,
        @Nullable String verificadaAl,
        @Nullable String formato,
        @Nullable String observacion) {}
