package kamayuk.rentas.cuentacorriente.infraestructura.web;

import java.util.List;
import kamayuk.rentas.cuentacorriente.dominio.ConstanciaDeNoAdeudo;

/**
 * La constancia de no adeudo, tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04
 * §3).
 *
 * <p>{@code seNiega} es la decision que RNF-084 exige mostrar antes que nada: la pantalla no
 * imprime un documento en blanco, niega explicitamente. El detalle trae cada obligacion con su
 * {@link kamayuk.rentas.web.ImporteActualizado} —fecha incluida siempre, regla 9— via {@link
 * ObligacionConDeudaResource}.
 */
public record ConstanciaResource(
        String codigoContribuyente,
        String fechaDeCorte,
        boolean seNiega,
        List<ObligacionConDeudaResource> obligaciones) {

    public static ConstanciaResource de(ConstanciaDeNoAdeudo constancia) {
        return new ConstanciaResource(
                constancia.codigoContribuyente(),
                constancia.fecha().toString(),
                constancia.seNiega(),
                constancia.obligaciones().stream().map(ObligacionConDeudaResource::de).toList());
    }
}
