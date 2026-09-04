package kamayuk.rentas.licencias.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * Lo que la pantalla {@code fue_edificacion} manda para presentar un FUE (#48, RF-113).
 *
 * <p>Todos los campos llegan como texto y se analizan en el controlador: una fecha mal escrita
 * tiene que producir un 422 que diga cual, no un 400 generico de Jackson que no dice nada util.
 *
 * <p><b>El numero de la licencia no esta aqui</b>, y es deliberado: presentar un FUE no otorga
 * nada. El numero lo pone el sistema al emitir, desde su correlativo.
 *
 * @param observacion por que se registra (regla 10, RNF-052)
 */
public record PeticionDeFue(
        @Nullable String nroExpediente,
        @Nullable String fechaDeclaracion,
        @Nullable String codContribuyente,
        @Nullable Long predioId,
        @Nullable String tipoTramite,
        @Nullable String obra,
        @Nullable String modalidadAprobacion,
        @Nullable String revision,
        @Nullable String nroExpedienteAnterior,
        @Nullable String nroLicenciaAnterior,
        @Nullable Boolean solicitanteEsPropietario,
        @Nullable String representanteDni,
        @Nullable String representanteNombre,
        @Nullable String representantePartidaRegistral,
        @Nullable String representanteVigenciaPoder,
        @Nullable String observacion) {}
