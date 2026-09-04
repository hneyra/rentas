package kamayuk.rentas.licencias.infraestructura.web;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla {@code licencia_funcionamiento} manda para emitir (#44, RF-110).
 *
 * <p>Todos los campos llegan como texto y se analizan en el controlador: una fecha mal escrita
 * tiene que producir un 422 que diga cual, no un 400 generico de Jackson que no dice nada util.
 *
 * <p><b>El numero de la licencia no esta aqui</b>, y es deliberado: lo pone el sistema desde su
 * correlativo. La pantalla lo muestra como campo de solo lectura, y si viniera del cliente, dos
 * peticiones podrian pedir el mismo.
 *
 * @param observacion por que se emite (regla 10, RNF-052)
 */
public record PeticionDeLicencia(
        @Nullable String codContribuyente,
        @Nullable Long predioId,
        @Nullable String denominacionComercial,
        @Nullable String direccion,
        @Nullable String areaDelEstablecimiento,
        @Nullable String tipoDeLicencia,
        @Nullable String zonificacion,
        @Nullable Integer aforo,
        @Nullable String fechaDeEmision,
        @Nullable String fechaDeVencimiento,
        @Nullable String nDeRecibo,
        @Nullable List<String> giros,
        @Nullable String giroPrincipal,
        @Nullable String nExpediente,
        @Nullable String fechaDeExpediente,
        @Nullable String formato,
        @Nullable String observacion) {}
