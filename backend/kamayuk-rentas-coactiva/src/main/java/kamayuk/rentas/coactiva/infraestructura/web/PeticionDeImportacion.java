package kamayuk.rentas.coactiva.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/coactiva/expedientes/importacion} (RF-100). <b>Lista blanca</b>:
 * lo que no esta aqui no entra.
 *
 * <p>Los campos son los que la pantalla {@code importacion_valores} declara: el contribuyente, los
 * valores marcados en la grilla, los dos encargados, el asunto y la direccion referencial.
 *
 * <p><b>No hay ningun importe ni ningun numero de expediente.</b> Cuanto se debe lo dice el libro,
 * y el numero lo compone la plantilla vigente sobre el correlativo que la base entrega (D-09):
 * dejar que el cliente proponga uno seria dejarle elegir el orden del ejercicio.
 *
 * @param codContribuyente el obligado; obligatorio
 * @param valores los numeros de los valores marcados; vacia significa «todos los que se puedan»
 * @param ejecutor el ejecutor coactivo que se hara cargo; obligatorio
 * @param auxiliar el auxiliar coactivo, si se designa
 * @param asunto el asunto de la caratula
 * @param direccionReferencialDelContribuyente donde notificar, si difiere del domicilio fiscal
 * @param fecha el dia de la importacion, en ISO; si falta, hoy
 * @param observacion por que se importa (regla 10)
 */
public record PeticionDeImportacion(
        @Nullable String codContribuyente,
        @Nullable List<String> valores,
        @Nullable String ejecutor,
        @Nullable String auxiliar,
        @Nullable String asunto,
        @Nullable String direccionReferencialDelContribuyente,
        @Nullable String fecha,
        @Nullable String observacion) {}
