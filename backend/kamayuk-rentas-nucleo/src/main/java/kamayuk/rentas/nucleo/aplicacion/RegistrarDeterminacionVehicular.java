package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.dominio.CriterioDeVehiculo;
import kamayuk.rentas.nucleo.dominio.EstadoVehiculo;
import kamayuk.rentas.nucleo.dominio.Transferencia;
import kamayuk.rentas.nucleo.dominio.TransferenciaRepository;
import kamayuk.rentas.nucleo.dominio.ValorReferencial;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.nucleo.dominio.VehiculoEncontrado;
import kamayuk.rentas.nucleo.dominio.VehiculoRepository;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.nucleo.dominio.vehicular.AdquisicionDelPropietario;
import kamayuk.rentas.nucleo.dominio.vehicular.BaseImponibleVehicular;
import kamayuk.rentas.nucleo.dominio.vehicular.ImpuestoVehicular;
import kamayuk.rentas.nucleo.dominio.vehicular.OrigenDeLaBase;
import kamayuk.rentas.nucleo.dominio.vehicular.PropietarioAlPrimeroDeEnero;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
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
 * que lo lee {@link CuadroPredialParametrizado}: {@link LlavesDelConjunto#UIT} y {@link
 * LlavesDelConjunto#VEHICULAR_MINIMO}. Sin ellas la determinación <b>falla nombrando la llave</b> y
 * no calcula con cero, que es lo que hacía antes: un mínimo en cero no falla, deja el impuesto en
 * su importe bruto y solo se nota en los vehículos baratos —los que el mínimo existe para cubrir—.
 *
 * <p><b>El ejercicio se determina a quien era propietario al 1 de enero</b> (TUO LTM art. 31;
 * #329), no a quien figura hoy como titular. Hasta #329 se asentaba a {@code
 * Vehiculo#contribuyenteId}, que cada transferencia sobrescribe: un vehículo vendido a mitad de año
 * le dejaba al comprador el impuesto de un ejercicio en el que nunca fue contribuyente, y al
 * vendedor sin el suyo. Ahora lo decide {@link PropietarioAlPrimeroDeEnero} sobre el histórico de
 * {@code transferencia} —con la convención del mismo 1 de enero escrita allí—, y con la misma regla
 * {@link #vehiculosDe} resuelve sobre qué vehículos calcula una persona: los que tenía ese día, no
 * los que tiene hoy.
 *
 * <p><b>Ningún asiento de cuenta corriente se genera aquí</b>, igual que en {@link
 * RegistrarDeterminacionPredial}: trasladar el monto a una deuda exigible es un acto posterior
 * (#24).
 */
@Service
public class RegistrarDeterminacionVehicular {

    private static final String TABLA_AUDITADA = "determinacion";

    /** El orden con que se listan los vehículos de una persona: el mismo que tenía la consulta. */
    private static final String ORDEN_POR_OMISION = "placa";

    private final VehiculoRepository vehiculos;
    private final TransferenciaRepository transferencias;
    private final ValoresReferenciales valoresReferenciales;
    private final DeterminacionRepository determinaciones;
    private final LectorDeParametros parametros;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarDeterminacionVehicular(
            VehiculoRepository vehiculos,
            TransferenciaRepository transferencias,
            ValoresReferenciales valoresReferenciales,
            DeterminacionRepository determinaciones,
            LectorDeParametros parametros,
            Auditoria auditoria,
            Clock reloj) {
        this.vehiculos = vehiculos;
        this.transferencias = transferencias;
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
                        sellados.exigirNumero(LlavesDelConjunto.ALICUOTA_VEHICULAR, null)
                                .valor()
                                .toPlainString());
        Dinero minimoImponible = minimoImponibleDe(sellados);

        // El contribuyente del ejercicio (art. 31, #329) y el precio al que ESE entro al
        // patrimonio (art. 32, #330) salen de la misma historia, leida una vez: asi la base no
        // puede ser la de un propietario y la determinacion de otro.
        List<Transferencia> historia = transferencias.historicoDeVehiculo(vehiculoId);
        long propietario =
                PropietarioAlPrimeroDeEnero.de(vehiculo.contribuyenteId(), historia, ejercicio);
        BaseImponibleVehicular base =
                BaseImponibleVehicular.segunArticulo32(
                        AdquisicionDelPropietario.de(
                                        propietario,
                                        vehiculo.valorAdquisicion(),
                                        historia,
                                        ejercicio)
                                .orElse(null),
                        valorReferencial.valor());
        Dinero montoDeterminado = ImpuestoVehicular.calcular(base, alicuota, minimoImponible);

        Determinacion nueva =
                Determinacion.nuevaVehicular(
                        ejercicio,
                        propietario,
                        vehiculoId,
                        conjuntoId,
                        base.valor(),
                        montoDeterminado,
                        java.util.List.of(
                                LlavesDelConjunto.ALICUOTA_VEHICULAR,
                                LlavesDelConjunto.VEHICULAR_MINIMO));

        String conjunto = sellados.ejercicio() + " v" + sellados.version();
        if (simulacion) {
            return new Calculo(nueva, conjunto, alicuota, minimoImponible, base.origen());
        }

        Determinacion guardada = determinaciones.insertar(nueva);
        auditar(guardada, observacion);
        return new Calculo(guardada, conjunto, alicuota, minimoImponible, base.origen());
    }

    /**
     * Los vehículos activos de los que la persona era propietaria al 1 de enero del ejercicio: el
     * objetivo del cálculo por contribuyente (#329).
     *
     * <p>Hasta #329 eran sus {@code ACTIVO} de hoy, y la lista contestaba la pregunta equivocada:
     * el vendedor de junio ya no veía el vehículo del que era contribuyente, y el comprador veía
     * uno del que no lo era. Ahora se reúnen los candidatos —los que tiene hoy más los que
     * transfirió <b>desde</b> el 1 de enero, ese día incluido— y a cada uno se le pregunta de quién
     * era el ejercicio con {@link PropietarioAlPrimeroDeEnero}, la misma regla con que {@link
     * #calcular} decide a quién asentar: así la lista y la determinación no pueden discrepar. Los
     * que compró desde el 1 de enero quedan fuera por esa misma pregunta.
     *
     * <p>Un código que no es de nadie en esta municipalidad devuelve la lista vacía, igual que
     * antes.
     */
    @Transactional(readOnly = true)
    public List<Vehiculo> vehiculosDe(String codContribuyente, Ejercicio ejercicio) {
        Optional<Long> persona = transferencias.contribuyentePorCodigo(codContribuyente);
        if (persona.isEmpty()) {
            return List.of();
        }
        long quien = persona.get();

        Map<Long, Vehiculo> candidatos = new LinkedHashMap<>();
        CriterioDeVehiculo suyosHoy =
                new CriterioDeVehiculo(null, null, codContribuyente, EstadoVehiculo.ACTIVO);
        for (VehiculoEncontrado fila :
                vehiculos
                        .buscar(
                                suyosHoy,
                                Paginacion.de(0, Paginacion.TAMANO_MAXIMO, ORDEN_POR_OMISION))
                        .contenido()) {
            candidatos.put(idDe(fila.vehiculo()), fila.vehiculo());
        }
        for (long vehiculoId :
                transferencias.vehiculosQueTransfirioDesde(quien, ejercicio.primerDia())) {
            vehiculos
                    .findById(vehiculoId)
                    .filter(vehiculo -> vehiculo.estado() == EstadoVehiculo.ACTIVO)
                    .ifPresent(vehiculo -> candidatos.putIfAbsent(vehiculoId, vehiculo));
        }

        return candidatos.entrySet().stream()
                .filter(
                        candidato ->
                                propietarioAlPrimeroDeEnero(
                                                candidato.getValue(), candidato.getKey(), ejercicio)
                                        == quien)
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparing(Vehiculo::placa))
                .toList();
    }

    /**
     * De quién era el vehículo al 1 de enero del ejercicio: la regla pura sobre su histórico de
     * transferencias. Es el único sitio de esta clase que lee el titular, para que el cálculo
     * puntual y el de una persona no puedan contestar distinto.
     */
    private long propietarioAlPrimeroDeEnero(
            Vehiculo vehiculo, long vehiculoId, Ejercicio ejercicio) {
        return PropietarioAlPrimeroDeEnero.de(
                vehiculo.contribuyenteId(),
                transferencias.historicoDeVehiculo(vehiculoId),
                ejercicio);
    }

    private static long idDe(Vehiculo vehiculo) {
        Long id = vehiculo.id();
        if (id == null) {
            throw new IllegalStateException("Un vehiculo leido de la base tiene identificador");
        }
        return id;
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
        java.math.BigDecimal porcentaje =
                sellados.exigirNumero(LlavesDelConjunto.VEHICULAR_MINIMO, null).valor();
        Dinero uit =
                Dinero.de(
                        sellados.exigirNumero(LlavesDelConjunto.UIT, null).valor().toPlainString());
        return uit.por(porcentaje.movePointLeft(2));
    }

    /**
     * Lo que produce una determinación vehicular: la determinación y las cifras con que se hizo.
     *
     * @param determinacion la cabecera calculada —sin id si fue simulación—
     * @param conjunto cómo se nombra el conjunto sellado que la produjo: «2026 v1»
     * @param alicuota la alícuota del ejercicio, leída de ese conjunto
     * @param minimoImponible el mínimo del ejercicio, ya convertido a soles
     * @param origenDeLaBase de cuál de los dos operandos del art. 32 salió la base (#330)
     */
    public record Calculo(
            Determinacion determinacion,
            String conjunto,
            Alicuota alicuota,
            Dinero minimoImponible,
            OrigenDeLaBase origenDeLaBase) {}

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
