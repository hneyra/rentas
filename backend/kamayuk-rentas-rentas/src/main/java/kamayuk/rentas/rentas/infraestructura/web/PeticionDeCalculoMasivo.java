package kamayuk.rentas.rentas.infraestructura.web;

import org.jspecify.annotations.Nullable;

/**
 * El cuerpo de {@code POST /api/v1/rentas/predial/calculo-masivo} (#395).
 *
 * <p>Lista blanca, como todas. Los dos interruptores que la pantalla dibuja y esta corrida no hace
 * —{@code incluyeArbitrios} y {@code generaCuponeraPdf}— estan declarados <b>para poder
 * rechazarlos</b>: no declararlos los haria desaparecer en silencio, y quien los marco creeria que
 * se hicieron.
 *
 * @param observacion por que se corre la emision (regla 10)
 * @param ejercicio el ejercicio que se recalcula
 * @param alcance TODOS o SECTOR
 * @param sector obligatorio con alcance SECTOR
 * @param modalidad el cronograma de cuotas; TRIMESTRAL si no se dice
 * @param recalculaYaEmitidos si tambien entran los que ya tienen su determinacion emitida
 * @param simulacion obligatorio: true corre sin guardar nada, false asienta
 * @param incluyeArbitrios se rechaza si viene en true: los arbitrios son otro tributo
 * @param generaCuponeraPdf se rechaza si viene en true: esta corrida determina, no imprime
 */
public record PeticionDeCalculoMasivo(
        @Nullable String observacion,
        @Nullable String ejercicio,
        @Nullable String alcance,
        @Nullable String sector,
        @Nullable String codigoDesde,
        @Nullable String codigoHasta,
        @Nullable String modalidad,
        @Nullable Boolean recalculaYaEmitidos,
        @Nullable Boolean simulacion,
        @Nullable Boolean incluyeArbitrios,
        @Nullable Boolean generaCuponeraPdf) {}
