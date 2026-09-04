package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla {@code anuncios} manda para autorizar (#51, RF-114).
 *
 * <p>Todos los campos llegan como texto y se analizan en el controlador: una fecha mal escrita
 * tiene que producir un 422 que diga cual, no un 400 generico de Jackson que no dice nada util.
 *
 * <p><b>El numero de la autorizacion no esta aqui</b>, y es deliberado: lo pone el sistema desde su
 * correlativo. La pantalla lo muestra como campo de solo lectura, y si viniera del cliente, dos
 * peticiones podrian pedir el mismo.
 *
 * <p><b>La tasa tampoco.</b> La pantalla la pinta como campo de solo lectura y el cuerpo no la
 * trae: sale del conjunto sellado (regla 5, D-02b). Si viajara, cualquiera podria autorizar un
 * panel por un sol cambiando un numero en la peticion.
 *
 * @param observacion por que se autoriza (regla 10, RNF-052)
 */
public record PeticionDeAnuncio(
        @Nullable String codContribuyente,
        @Nullable String nroLicencia,
        @Nullable Long predioId,
        @Nullable String claseAnuncio,
        @Nullable String tipoAnuncio,
        @Nullable String ubicacion,
        @Nullable String forma,
        @Nullable String denominacion,
        @Nullable String direccion,
        @Nullable String area,
        @Nullable Integer nroLados,
        @Nullable Integer cantidad,
        @Nullable String fecInicio,
        @Nullable String fecVenc,
        @Nullable String nroDeExpediente,
        @Nullable String fechaExp,
        @Nullable String observacion) {}
