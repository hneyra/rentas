package kamayuk.rentas.valores.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/valores/masivo} (RF-091). <b>Lista blanca</b>: lo que no esta
 * aqui no entra.
 *
 * <p>Exactamente uno de {@code contribuyentes} o {@code archivoCsv} tiene que venir con datos: es
 * el "seleccion individual o importada de hoja de calculo" del manual (RF-091). {@code archivoCsv}
 * llega en base64 -no hay adjuntos multiparte en este contrato- con una columna, {@code
 * codContribuyente}, un candidato por fila.
 */
public record PeticionDeValorMasivo(
        @Nullable String tipo,
        @Nullable String tributo,
        @Nullable Integer ejercicioDesde,
        @Nullable Integer ejercicioHasta,
        @Nullable String fechaCriterio,
        @Nullable List<String> contribuyentes,
        @Nullable String archivoCsv,
        @Nullable String observacion) {}
