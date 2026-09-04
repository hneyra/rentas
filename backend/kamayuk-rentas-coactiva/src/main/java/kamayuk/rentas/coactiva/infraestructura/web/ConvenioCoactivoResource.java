package kamayuk.rentas.coactiva.infraestructura.web;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.tesoreria.ConvenioCoactivo;
import kamayuk.rentas.tesoreria.CuotaDelConvenio;
import org.jspecify.annotations.Nullable;

/**
 * El convenio coactivo como lo ve la interfaz (#42, RF-105).
 *
 * <p>{@code nroConvenio} es {@code null} en una simulacion: una simulacion no consume correlativo,
 * y si llevara numero la pantalla podria imprimir un papel con uno que no existe.
 *
 * <p><b>La fase de origen de cada cuota viaja</b>, y no es adorno: es lo que explica a donde vuelve
 * la deuda si el convenio se quiebra. Que la pantalla de coactiva pueda mostrar «COACTIVA» en cada
 * fila es lo que hace visible la propiedad que #42 verifica —quebrar devuelve a coactiva, no a
 * ordinaria— sin tener que quebrar nada.
 *
 * <p>Los importes salen como texto en su representacion decimal, como en el resto de la API (regla
 * 1, RNF-055).
 *
 * @param nroConvenio el numero impreso; nulo en una simulacion
 * @param expedCoact el numero impreso del expediente
 * @param tipo siempre {@code COACTIVO}
 * @param estado siempre {@code PRECONVENIO} al registrarse: sin inicial cobrada no hay convenio
 * @param fecha el dia del convenio
 * @param fechaCorte a que fecha esta {@code deudaTotalS} (regla 9)
 * @param deudaTotalS lo acogido a la fecha de corte
 * @param cuotaInicialS lo que se paga en el acto
 * @param nroDeCuotas cuantas cuotas sin contar la inicial
 * @param totalDelCronogramaS la suma de la inicial y las cuotas
 * @param interesDeFraccionamientoMensual el interes leido del conjunto sellado
 * @param conjuntoDeParametros de que conjunto salio (ARQ-09 §3)
 * @param cronograma la inicial y las cuotas
 * @param deudaAcogida que se acoge, cuota por cuota y con su fase de origen
 */
public record ConvenioCoactivoResource(
        @Nullable String nroConvenio,
        String expedCoact,
        String tipo,
        String estado,
        LocalDate fecha,
        LocalDate fechaCorte,
        String deudaTotalS,
        String cuotaInicialS,
        int nroDeCuotas,
        String totalDelCronogramaS,
        String interesDeFraccionamientoMensual,
        long conjuntoDeParametros,
        List<CuotaResource> cronograma,
        List<DeudaAcogidaResource> deudaAcogida) {

    public static ConvenioCoactivoResource de(
            ConvenioCoactivo convenio, String numeroDeExpediente) {

        List<CuotaResource> cuotas = new ArrayList<>(convenio.cronograma().size());
        for (CuotaDelConvenio cuota : convenio.cronograma()) {
            cuotas.add(CuotaResource.de(cuota));
        }
        List<DeudaAcogidaResource> acogida = new ArrayList<>(convenio.deudaAcogida().size());
        for (DeudaAcogida cuota : convenio.deudaAcogida()) {
            acogida.add(DeudaAcogidaResource.de(cuota));
        }

        return new ConvenioCoactivoResource(
                convenio.numero(),
                numeroDeExpediente,
                convenio.tipo(),
                convenio.estado(),
                convenio.fecha(),
                convenio.fechaCorte(),
                convenio.total().valor().toPlainString(),
                convenio.cuotaInicial().valor().toPlainString(),
                convenio.numeroDeCuotas(),
                convenio.totalDelCronograma().valor().toPlainString(),
                convenio.interesMensual().valor().toPlainString(),
                convenio.conjuntoId(),
                cuotas,
                acogida);
    }

    /** Una fila del cronograma. La cuota 0 es la inicial. */
    public record CuotaResource(
            int nro,
            LocalDate vencimiento,
            String cuotaS,
            String capitalS,
            String interesS,
            String gastoS) {

        static CuotaResource de(CuotaDelConvenio cuota) {
            return new CuotaResource(
                    cuota.numero(),
                    cuota.vencimiento(),
                    cuota.monto().valor().toPlainString(),
                    cuota.capital().valor().toPlainString(),
                    cuota.interes().valor().toPlainString(),
                    cuota.gasto().valor().toPlainString());
        }
    }

    /** Una fila de la deuda acogida, con la fase de la que sale y a la que volveria. */
    public record DeudaAcogidaResource(
            String tributo,
            int ejercicio,
            int periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            String faseOrigen,
            LocalDate aLaFecha,
            String insolutoS,
            String reajusteS,
            String interesS,
            String gastoS,
            String totalS) {

        static DeudaAcogidaResource de(DeudaAcogida acogida) {
            return new DeudaAcogidaResource(
                    acogida.tributo(),
                    acogida.ejercicio().valor(),
                    acogida.periodo(),
                    acogida.predioId(),
                    acogida.vehiculoId(),
                    acogida.faseOrigen(),
                    acogida.fecha(),
                    acogida.insoluto().valor().toPlainString(),
                    acogida.reajuste().valor().toPlainString(),
                    acogida.interes().valor().toPlainString(),
                    acogida.gasto().valor().toPlainString(),
                    acogida.total().valor().toPlainString());
        }
    }
}
