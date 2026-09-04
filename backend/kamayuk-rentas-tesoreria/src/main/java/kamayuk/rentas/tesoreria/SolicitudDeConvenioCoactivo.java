package kamayuk.rentas.tesoreria;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Alicuota;
import org.jspecify.annotations.Nullable;

/**
 * Lo que {@code coactiva} pide para fraccionar (#42, RF-105).
 *
 * <p><b>No lleva ningun importe, ni el interes, ni el maximo de cuotas.</b> Exactamente por el
 * motivo por el que no los lleva {@code PeticionDeFraccionamiento}: el cuanto lo resuelve {@code
 * cuentacorriente} releyendo su libro a la fecha de corte, y las condiciones salen del conjunto
 * sellado (regla 5, D-02b). Admitirlos aqui seria admitir que otro contexto decida cuanto se
 * fracciona y a que precio.
 *
 * <p>Tampoco lleva el <b>tipo</b> de convenio: por este puerto solo entra el coactivo, y que no se
 * pueda elegir es lo que impide registrar un ordinario por la ruta de coactiva.
 *
 * @param contribuyenteId el obligado; lo resolvio quien llama
 * @param obligaciones las deudas marcadas en la grilla
 * @param fecha el dia del convenio; decide el conjunto sellado (regla 6)
 * @param fechaDeCorte a que fecha se lee la deuda que se acoge (regla 9)
 * @param cuotas cuantas cuotas se piden, sin contar la inicial
 * @param porcentajeInicial que parte se paga en el acto; lo elige quien atiende, no es normativo
 * @param primeraCuotaVence cuando vence la primera cuota
 * @param resolucion la resolucion que lo aprueba, si consta
 */
public record SolicitudDeConvenioCoactivo(
        long contribuyenteId,
        List<SeleccionDeObligacion> obligaciones,
        LocalDate fecha,
        LocalDate fechaDeCorte,
        int cuotas,
        Alicuota porcentajeInicial,
        LocalDate primeraCuotaVence,
        @Nullable String resolucion) {

    public SolicitudDeConvenioCoactivo {
        Objects.requireNonNull(obligaciones, "La lista es vacia, no nula");
        obligaciones = List.copyOf(obligaciones);
        if (obligaciones.isEmpty()) {
            throw new IllegalArgumentException(
                    "Hay que marcar al menos una deuda: un convenio sin deuda acogida no fracciona"
                            + " nada");
        }
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException("El convenio es de un obligado concreto");
        }
        Objects.requireNonNull(fecha, "El convenio es de un dia concreto (regla 6)");
        Objects.requireNonNull(fechaDeCorte, "La deuda se lee a una fecha (regla 9)");
        Objects.requireNonNull(
                porcentajeInicial, "Hay que decir que porcentaje se paga de inicial");
        Objects.requireNonNull(primeraCuotaVence, "La primera cuota vence en una fecha");
    }
}
