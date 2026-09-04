package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;

/**
 * Lo que sigue pendiente de un tributo en un ejercicio, <b>a una fecha de corte</b> (#56, #639).
 *
 * <p>Sale del <b>libro</b>, agregado por PostgreSQL: una fila por tributo, no una por obligacion
 * (AC 4 de #56). Hasta #639 salia de {@code saldo_proyectado}, y ahi estaba el defecto: la
 * proyeccion netea el insoluto de la obligacion entera <b>sin fecha de corte</b>, asi que la
 * cartera incluia la cuota que todavia no vence y la misma cifra salia igual preguntando por enero
 * que por diciembre. Medido en la municipalidad de demostracion, PREDIAL 2026 al 1 de setiembre de
 * 2026: la proyeccion decia 10 662,60 y lo pendiente a esa fecha eran 8 221,05 —los 2 441,55 de
 * diferencia son las siete cuotas con fecha valor 2026-11-30—.
 *
 * <p><b>Es insoluto, no deuda.</b> Se netea solo {@link Concepto#INSOLUTO}: el reajuste y el
 * interes dependen de la fecha en que se pregunte y darlos para el padron entero exigiria
 * calcularlos obligacion por obligacion en cada carga de la pantalla de inicio. Quien lea esta
 * cifra tiene que saber que es el principal pendiente y nada mas —es exactamente {@code
 * deudaActualizadaA(fecha).insoluto()}, sumado—.
 *
 * @param tributo el tributo de las obligaciones
 * @param pendiente la suma del insoluto neteado hasta la fecha de corte
 * @param obligaciones cuantas obligaciones —tributo/ejercicio/unidad, no cuotas— la componen
 */
public record PendienteAgregado(String tributo, Dinero pendiente, long obligaciones) {

    public PendienteAgregado {
        Objects.requireNonNull(tributo, "La linea necesita su tributo");
        Objects.requireNonNull(pendiente, "La linea necesita su importe");
        if (obligaciones < 0) {
            throw new IllegalArgumentException("El numero de obligaciones no puede ser negativo");
        }
    }
}
