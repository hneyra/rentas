package kamayuk.rentas.nucleo.infraestructura.web;

import kamayuk.rentas.nucleo.aplicacion.RegistrarDeterminacionVehicular;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;

/**
 * Una determinación vehicular tal como sale por HTTP. Campos en español {@code camelCase} (ARQ-04
 * §3).
 *
 * <h2>Se llama {@code baseImponible} porque es la base imponible (#577)</h2>
 *
 * <p>Se llamaba {@code valorReferencial} y <b>no era el valor referencial del MEF</b>: es {@code
 * Determinacion#baseImponible}, o sea el <b>mayor</b> entre el de adquisicion y el referencial
 * (art. 32 de la Ley de Tributacion Municipal). Un campo llamado {@code valorReferencial} que trae
 * otra cifra es la clase de trampa que #427 encontro con {@code CertificadoResource.solicitante}:
 * compila, pasa el lint, y lo que llega a ventanilla es otra cosa. Aqui ademas la cifra
 * <b>coincide</b> con el valor referencial en la mayoria de los casos —el de adquisicion suele ser
 * menor—, asi que el nombre equivocado solo se delataria en los vehiculos recien comprados, que son
 * justo los que mas valen.
 *
 * <h2>Y dice de cual de los dos salio (#330)</h2>
 *
 * <p>Hasta #330 este javadoc afirmaba «el mayor entre el de adquisicion y el referencial», y el
 * codigo no leia el de adquisicion: la base era siempre la tabla, y la respuesta la rotulaba como
 * el resultado de una comparacion que no se hacia. Ahora la compara {@code
 * BaseImponibleVehicular#segunArticulo32}, y {@code origenDeLaBase} lo dice: {@code ADQUISICION},
 * {@code TABLA}, o {@code TABLA_SIN_ADQUISICION} cuando no hay valor de adquisicion capturado —la
 * base es la tabla porque falta el otro operando, no porque haya ganado—.
 *
 * <p>El origen viaja en la respuesta del calculo, pero <b>no se guarda</b> todavia en {@code
 * determinacion}: eso es una columna mas y un componente mas de {@code Determinacion}, con su
 * migracion, y queda en su propio issue.
 *
 * <p>{@code baseImponible} y {@code montoDeterminado} viajan como texto y no como {@link
 * kamayuk.rentas.dominio.Dinero}: son la cifra fija con que se determinó, no un saldo que cambie
 * con el tiempo —mismo motivo que {@code ArbitrioResource}—, así que no necesitan {@code
 * ImporteActualizado} para cumplir la regla de ArchUnit {@code TODA_CIFRA_DE_LA_WEB_LLEVA_SU_FECHA}
 * (regla 9): esa regla mira el tipo {@code Dinero}, y aquí no aparece. La fecha a la que están
 * calculadas es una sola para toda la petición y vive en {@link CalculoVehicularResource}.
 *
 * <p><b>Los importes viajan sin redondear.</b> El vehicular no tiene todavía ningún punto de
 * redondeo parametrizado y {@link kamayuk.rentas.dominio.Dinero} no elige escala por su cuenta
 * (D-03a/D-03c, ADR-0018): {@code 112800.00 × 1 %} sale «1128.0000». Redondearlo aquí sería tomar
 * esa decisión de paso y repartirla por la capa web.
 *
 * @param id el identificador de la determinación guardada; {@code 0} si esto fue una simulación
 * @param ejercicio el ejercicio determinado
 * @param vehiculoId el vehículo determinado
 * @param placa su placa
 * @param contribuyenteId de quién es
 * @param baseImponible el mayor entre el valor de adquisición y el referencial del MEF; no es «el
 *     valor referencial», y por eso ya no se llama así (#577)
 * @param origenDeLaBase {@code ADQUISICION}, {@code TABLA} o {@code TABLA_SIN_ADQUISICION} (#330)
 * @param montoDeterminado el impuesto resultante
 * @param simulacion si es {@code true}, esta determinación no se guardó (modo simulación, RF-025)
 */
public record DeterminacionVehicularResource(
        long id,
        String ejercicio,
        long vehiculoId,
        String placa,
        long contribuyenteId,
        String baseImponible,
        String origenDeLaBase,
        String montoDeterminado,
        boolean simulacion) {

    public static DeterminacionVehicularResource de(
            RegistrarDeterminacionVehicular.Calculo calculo, Vehiculo vehiculo) {
        Determinacion determinacion = calculo.determinacion();
        return new DeterminacionVehicularResource(
                determinacion.id() == null ? 0L : determinacion.id(),
                determinacion.ejercicio().toString(),
                vehiculo.id() == null ? 0L : vehiculo.id(),
                vehiculo.placa().toString(),
                determinacion.contribuyenteId(),
                determinacion.baseImponible().valor().toPlainString(),
                calculo.origenDeLaBase().name(),
                determinacion.montoDeterminado().valor().toPlainString(),
                determinacion.esNueva());
    }
}
