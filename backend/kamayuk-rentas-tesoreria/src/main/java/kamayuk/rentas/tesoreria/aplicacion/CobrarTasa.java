package kamayuk.rentas.tesoreria.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dominio.FormaDePago;
import kamayuk.rentas.tesoreria.dominio.LineaDeRecibo;
import kamayuk.rentas.tesoreria.dominio.LineaDeTasaPedida;
import kamayuk.rentas.tesoreria.dominio.NumeroDeRecibo;
import kamayuk.rentas.tesoreria.dominio.Recibo;
import kamayuk.rentas.tesoreria.dominio.ReciboRepository;
import kamayuk.rentas.tesoreria.dominio.Tasa;
import kamayuk.rentas.tesoreria.dominio.TasaRepository;
import kamayuk.rentas.tesoreria.dominio.TipoDePago;
import kamayuk.rentas.tesoreria.dominio.TurnoDeCaja;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caja de tasas: cobra derechos del TUPA y emite su recibo (#33, RF-081, RF-133).
 *
 * <p><b>No toca la cuenta corriente</b>, y es la diferencia esencial con {@link CobrarDeuda}: un
 * derecho de tramite no es deuda tributaria —no se determina, no devenga interes, no prescribe—,
 * asi que no hay cargo que abonar. Lo que hay es un servicio que se presta y se cobra en el acto.
 * Por eso este caso de uso no depende de {@code cuentacorriente} para nada.
 *
 * <p><b>El precio sale de la tabla {@code tasa}</b>, vigente a la fecha del cobro, nunca de la
 * peticion ni de una constante (regla 5, ADR-0007). Que venga de la peticion seria dejar que el
 * cliente ponga la tarifa; que venga compilada seria una tarifa que solo se cambia desplegando, y
 * las que no se cambian son las que se acaban cobrando mal.
 *
 * <p>La multiplicacion cantidad x precio la comprueba ademas la base, en {@code
 * recibo_detalle_tasa_ck} (V29).
 */
@Service
public class CobrarTasa {

    /** El concepto con el que se rotula una linea de tasa en {@code recibo_detalle}. */
    private static final String CONCEPTO_TASA = "TASA";

    private final AbrirCaja abrirCaja;
    private final TasaRepository tasas;
    private final ReciboRepository recibos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public CobrarTasa(
            AbrirCaja abrirCaja,
            TasaRepository tasas,
            ReciboRepository recibos,
            Auditoria auditoria,
            Clock reloj) {
        this.abrirCaja = abrirCaja;
        this.tasas = tasas;
        this.recibos = recibos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Cobra los conceptos marcados y emite.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link CobroDeTasas}: la regla 10
     * exige que se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros
     * del metodo transaccional.
     *
     * @param peticion lo que el cajero marco
     * @param observacion por que se cobra (regla 10, RNF-052)
     * @throws TasaSinTarifaVigente si algun concepto no tiene tarifa vigente a esa fecha
     * @throws TarifaEnCero si la tarifa vigente es cero: un recibo por cero no documenta un cobro
     */
    @Transactional
    public Recibo cobrar(CobroDeTasas peticion, Observacion observacion) {
        Objects.requireNonNull(peticion, "No se cobra sin peticion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        AbrirCaja.Abierta abierta =
                abrirCaja.enLaCaja(
                        peticion.codigoDeCaja(),
                        peticion.cajero(),
                        peticion.fechaDeCobro(),
                        observacion);

        String clave = peticion.claveDeIdempotencia();
        if (clave != null) {
            Optional<Recibo> yaEmitido = recibos.porClaveDeIdempotencia(clave);
            if (yaEmitido.isPresent()) {
                return yaEmitido.get();
            }
        }

        List<LineaDeRecibo> lineas = new ArrayList<>(peticion.conceptos().size());
        for (LineaDeTasaPedida pedida : peticion.conceptos()) {
            Tasa tasa =
                    tasas.vigenteA(pedida.codigoDeTasa(), peticion.fechaDeCobro())
                            .orElseThrow(
                                    () ->
                                            new TasaSinTarifaVigente(
                                                    pedida.codigoDeTasa(),
                                                    peticion.fechaDeCobro()));
            if (!tasa.importe().esPositivo()) {
                throw new TarifaEnCero(tasa);
            }
            lineas.add(
                    new LineaDeRecibo(
                            tasa.codigo(),
                            CONCEPTO_TASA,
                            null,
                            null,
                            tasa.idGuardado(),
                            null,
                            null,
                            null,
                            pedida.cantidad(),
                            tasa.importe(),
                            // Un derecho de tramite no tiene reajuste, ni interes moratorio, ni
                            // gastos de cobranza: su importe integro es la parte de insoluto.
                            tasa.por(pedida.cantidad()),
                            Dinero.CERO,
                            Dinero.CERO,
                            Dinero.CERO));
        }

        NumeroDeRecibo numero = recibos.siguienteNumero(abierta.caja());
        TurnoDeCaja turno = abierta.turno();
        Recibo recibo =
                new Recibo(
                        null,
                        numero,
                        Objects.requireNonNull(abierta.caja().id()),
                        turno.idGuardado(),
                        peticion.cajero(),
                        peticion.contribuyenteId(),
                        reloj.instant(),
                        peticion.formaDePago(),
                        TipoDePago.TASA,
                        null,
                        // La fecha a la que la tarifa estaba vigente: es lo que hace que el
                        // duplicado pueda explicar su cifra el dia que la ordenanza la suba.
                        peticion.fechaDeCobro(),
                        observacion,
                        lineas);

        Recibo emitido = recibos.emitir(recibo, clave);
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                peticion.fechaDeCobro(),
                                "recibo",
                                String.valueOf(emitido.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(emitido)));
        return emitido;
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(Recibo recibo) {
        return "{\"numero\":\""
                + recibo.numero().impreso()
                + "\",\"tipoDePago\":\"TASA\",\"conceptos\":"
                + recibo.lineas().size()
                + ",\"total\":"
                + recibo.total().valor().toPlainString()
                + ",\"actualizadoA\":\""
                + recibo.actualizadoA()
                + "\"}";
    }

    /**
     * Lo que el cajero marco en caja de tasas.
     *
     * @param codigoDeCaja la ventanilla
     * @param cajero quien cobra
     * @param contribuyenteId a quien se le cobra; lo resolvio el borde HTTP
     * @param conceptos los del TUPA, con su cantidad
     * @param formaDePago con que se paga
     * @param fechaDeCobro la fecha a la que se resuelve la tarifa vigente (regla 6)
     * @param claveDeIdempotencia la cabecera {@code idempotency-key}, si vino
     */
    public record CobroDeTasas(
            String codigoDeCaja,
            String cajero,
            long contribuyenteId,
            List<LineaDeTasaPedida> conceptos,
            FormaDePago formaDePago,
            LocalDate fechaDeCobro,
            @Nullable String claveDeIdempotencia) {

        public CobroDeTasas {
            Objects.requireNonNull(codigoDeCaja, "El cobro es de una caja");
            Objects.requireNonNull(cajero, "El cobro lo hace un cajero con nombre");
            Objects.requireNonNull(conceptos, "La lista es vacia, no nula");
            Objects.requireNonNull(formaDePago, "Hay que decir con que se paga");
            Objects.requireNonNull(fechaDeCobro, "La fecha entra como argumento (regla 6)");
            conceptos = List.copyOf(conceptos);
            if (conceptos.isEmpty()) {
                throw new IllegalArgumentException("Hay que marcar al menos un concepto del TUPA");
            }
            if (contribuyenteId <= 0) {
                throw new IllegalArgumentException("El cobro es de un contribuyente concreto");
            }
        }
    }

    /** Ese concepto no tiene tarifa vigente a esa fecha. */
    public static final class TasaSinTarifaVigente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TasaSinTarifaVigente(String codigo, LocalDate fecha) {
            super(
                    "El concepto '"
                            + codigo
                            + "' no tiene tarifa vigente al "
                            + fecha
                            + ". La tarifa es dato registrado con su ordenanza y su vigencia"
                            + " (regla 5): sin una vigente no hay nada que cobrar");
        }
    }

    /** La tarifa vigente es cero: no hay cobro que documentar. */
    public static final class TarifaEnCero extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TarifaEnCero(Tasa tasa) {
            super(
                    "La tarifa vigente del concepto '"
                            + tasa.codigo()
                            + "' es cero: un recibo por cero no documenta un cobro, y la base lo"
                            + " rechaza igual");
        }
    }
}
