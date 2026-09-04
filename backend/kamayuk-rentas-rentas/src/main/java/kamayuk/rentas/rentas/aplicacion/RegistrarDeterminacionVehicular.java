package kamayuk.rentas.rentas.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.dominio.ValorReferencial;
import kamayuk.rentas.rentas.dominio.Vehiculo;
import kamayuk.rentas.rentas.dominio.VehiculoRepository;
import kamayuk.rentas.rentas.dominio.predial.Determinacion;
import kamayuk.rentas.rentas.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.rentas.dominio.vehicular.ImpuestoVehicular;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Determina el impuesto al patrimonio vehicular de un vehículo para un ejercicio (#32, RF-025).
 *
 * <p><b>El plazo de afectación se respeta automáticamente</b>: un vehículo fuera de {@link
 * Vehiculo#afectoEn} no se determina, sin que nadie tenga que decidirlo caso por caso —es el
 * criterio de aceptación de #32—. Ver {@link VehiculoNoAfecto}.
 *
 * <p><b>El modo simulación no escribe nada</b> (RF-025, «el manual lo distingue explícitamente»):
 * con {@code simulacion = true}, {@link #calcular} devuelve la {@link Determinacion} calculada sin
 * guardarla —{@link Determinacion#esNueva()} sigue siendo {@code true}— y sin auditarla. Ni una
 * fila de {@code determinacion} ni de {@code auditoria} cambia.
 *
 * <p><b>La alícuota y el mínimo imponible salen del conjunto sellado del ejercicio</b>, igual que
 * {@code RT001ValorDeTerreno} lee el arancel: parametrizados, nunca un literal (regla 5). El mínimo
 * llegaba como argumento hasta #399 —y el argumento venía del <b>cuerpo de la petición</b>, o sea
 * del cliente—: el artículo 34 del TUO de la LTM lo escribe como un porcentaje de la UIT, así que
 * es una cifra normativa y no un dato de la operación. Se lee aquí con las mismas dos llaves con
 * que lo lee {@link CuadroPredialParametrizado}: {@link #TIPO_UIT} y {@link #MINIMO_VEHICULAR}. Sin
 * ellas la determinación <b>falla nombrando la llave</b> y no calcula con cero, que es lo que hacía
 * antes: un mínimo en cero no falla, deja el impuesto en su importe bruto y solo se nota en los
 * vehículos baratos —los que el mínimo existe para cubrir—.
 *
 * <p><b>Ningún asiento de cuenta corriente se genera aquí</b>, igual que en {@link
 * RegistrarDeterminacionPredial}: trasladar el monto a una deuda exigible es un acto posterior
 * (#24).
 */
@Service
public class RegistrarDeterminacionVehicular {

    /**
     * El tipo del parámetro que trae la alícuota; una sola clave por ejercicio (no por vehículo).
     */
    public static final String ALICUOTA_VEHICULAR = "ALICUOTA_VEHICULAR";

    /**
     * El mínimo imponible del ejercicio, como porcentaje de la UIT (TUO LTM art. 34; #399).
     *
     * <p>Mismo trato y misma forma que {@code PREDIAL_MINIMO}: la norma lo escribe en UIT y la
     * conversión a soles se hace con la UIT del <b>mismo</b> conjunto. Sin clave, porque el tipo
     * tiene un solo valor por ejercicio.
     */
    public static final String MINIMO_VEHICULAR = "VEHICULAR_MINIMO";

    /**
     * La UIT del ejercicio, en soles: la misma llave que lee el cuadro del predial.
     *
     * <p>No es «la UIT del vehicular»: hay una sola por ejercicio y los dos tributos la leen del
     * mismo conjunto sellado. Se referencia la constante del cuadro predial en vez de repetir la
     * cadena, para que no puedan separarse.
     */
    public static final String TIPO_UIT = CuadroPredialParametrizado.TIPO_UIT;

    private static final String TABLA_AUDITADA = "determinacion";

    private final VehiculoRepository vehiculos;
    private final ValoresReferenciales valoresReferenciales;
    private final DeterminacionRepository determinaciones;
    private final LectorDeParametros parametros;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarDeterminacionVehicular(
            VehiculoRepository vehiculos,
            ValoresReferenciales valoresReferenciales,
            DeterminacionRepository determinaciones,
            LectorDeParametros parametros,
            Auditoria auditoria,
            Clock reloj) {
        this.vehiculos = vehiculos;
        this.valoresReferenciales = valoresReferenciales;
        this.determinaciones = determinaciones;
        this.parametros = parametros;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Calcula el impuesto de un vehículo. Con {@code simulacion = true}, calcula sin guardar ni
     * generar declaración jurada; con {@code false}, guarda una determinación nueva y la audita.
     *
     * <p>Devuelve un {@link Calculo} y no la {@link Determinacion} a secas porque la memoria del
     * cálculo necesita las dos cifras con que se hizo —la alícuota y el mínimo— y el nombre del
     * conjunto del que salieron: sin ellos la pantalla enseña un importe que nadie puede reproducir
     * (ARQ-09 §3), y volver a preguntárselos al lector sería una segunda lectura que podría caer en
     * otro conjunto.
     */
    @Transactional
    public Calculo calcular(
            long vehiculoId, Ejercicio ejercicio, boolean simulacion, Observacion observacion) {
        Vehiculo vehiculo =
                vehiculos
                        .findById(vehiculoId)
                        .orElseThrow(() -> new VehiculoInexistente(vehiculoId));

        if (!vehiculo.afectoEn(ejercicio)) {
            throw new VehiculoNoAfecto(vehiculo, ejercicio);
        }

        ValorReferencial valorReferencial =
                valoresReferenciales
                        .de(vehiculo, ejercicio)
                        .orElseThrow(() -> new SinValorReferencial(vehiculo, ejercicio));

        ParametrosSellados sellados = parametros.vigenteEn(ejercicio);
        long conjuntoId = parametros.conjuntoVigenteEn(ejercicio).valor();
        Alicuota alicuota =
                Alicuota.de(
                        sellados.exigirNumero(ALICUOTA_VEHICULAR, null).valor().toPlainString());
        Dinero minimoImponible = minimoImponibleDe(sellados);

        Dinero montoDeterminado =
                ImpuestoVehicular.calcular(valorReferencial.valor(), alicuota, minimoImponible);

        Determinacion nueva =
                Determinacion.nuevaVehicular(
                        ejercicio,
                        vehiculo.contribuyenteId(),
                        vehiculoId,
                        conjuntoId,
                        valorReferencial.valor(),
                        montoDeterminado,
                        java.util.List.of(ALICUOTA_VEHICULAR, MINIMO_VEHICULAR));

        String conjunto = sellados.ejercicio() + " v" + sellados.version();
        if (simulacion) {
            return new Calculo(nueva, conjunto, alicuota, minimoImponible);
        }

        Determinacion guardada = determinaciones.insertar(nueva);
        auditar(guardada, observacion);
        return new Calculo(guardada, conjunto, alicuota, minimoImponible);
    }

    /**
     * El mínimo imponible del ejercicio, en soles, leído del mismo conjunto que la alícuota.
     *
     * <p>El artículo 34 lo escribe como porcentaje de la UIT —«no menor al 1.5 % de la UIT»— y así
     * se publica; la conversión a soles se hace aquí, con la UIT del mismo conjunto sellado. Si
     * falta cualquiera de las dos llaves, {@link ParametrosSellados#exigirNumero} lanza nombrando
     * cuál: no hay valor por omisión, porque un mínimo inventado no produce ningún error —produce
     * un piso que ninguna norma puso, cobrado a todo vehículo barato del padrón—.
     */
    private static Dinero minimoImponibleDe(ParametrosSellados sellados) {
        java.math.BigDecimal porcentaje = sellados.exigirNumero(MINIMO_VEHICULAR, null).valor();
        Dinero uit = Dinero.de(sellados.exigirNumero(TIPO_UIT, null).valor().toPlainString());
        return uit.por(porcentaje.movePointLeft(2));
    }

    /**
     * Lo que produce una determinación vehicular: la determinación y las cifras con que se hizo.
     *
     * @param determinacion la cabecera calculada —sin id si fue simulación—
     * @param conjunto cómo se nombra el conjunto sellado que la produjo: «2026 v1»
     * @param alicuota la alícuota del ejercicio, leída de ese conjunto
     * @param minimoImponible el mínimo del ejercicio, ya convertido a soles
     */
    public record Calculo(
            Determinacion determinacion,
            String conjunto,
            Alicuota alicuota,
            Dinero minimoImponible) {}

    private void auditar(Determinacion guardada, Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                TABLA_AUDITADA,
                                String.valueOf(guardada.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada)));
    }

    private static String descripcion(Determinacion determinacion) {
        return "{\"tributo\":\"VEHICULAR\",\"vehiculoId\":"
                + determinacion.vehiculoId()
                + ",\"contribuyenteId\":"
                + determinacion.contribuyenteId()
                + ",\"ejercicio\":\""
                + determinacion.ejercicio()
                + "\",\"conjuntoId\":"
                + determinacion.conjuntoId()
                + ",\"baseImponible\":\""
                + determinacion.baseImponible()
                + "\",\"montoDeterminado\":\""
                + determinacion.montoDeterminado()
                + "\"}";
    }

    /** No hay ningún vehículo con ese identificador, o es de otra municipalidad. */
    public static final class VehiculoInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        VehiculoInexistente(long id) {
            super("No hay ningun vehiculo con identificador " + id + " en esta municipalidad");
        }
    }

    /**
     * El vehículo ya no está afecto en el ejercicio pedido: el plazo de tres años venció. No se
     * determina — es la respuesta automática que #32 exige, sin que nadie tenga que revisarlo.
     */
    public static final class VehiculoNoAfecto extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        VehiculoNoAfecto(Vehiculo vehiculo, Ejercicio ejercicio) {
            super(
                    "El vehiculo "
                            + vehiculo.placa()
                            + " no esta afecto en el ejercicio "
                            + ejercicio
                            + ": su afectacion corrio de "
                            + vehiculo.rangoDeAfectacion().desde()
                            + " a "
                            + vehiculo.rangoDeAfectacion().hasta());
        }
    }

    /**
     * El vehículo no tiene valor referencial en la tabla del ejercicio: no hay base para calcular.
     */
    public static final class SinValorReferencial extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SinValorReferencial(Vehiculo vehiculo, Ejercicio ejercicio) {
            super(
                    "El vehiculo "
                            + vehiculo.placa()
                            + " ("
                            + vehiculo.marca()
                            + " "
                            + vehiculo.modelo()
                            + " "
                            + vehiculo.anioFabricacion()
                            + ") no tiene valor referencial en la tabla del ejercicio "
                            + ejercicio);
        }
    }
}
