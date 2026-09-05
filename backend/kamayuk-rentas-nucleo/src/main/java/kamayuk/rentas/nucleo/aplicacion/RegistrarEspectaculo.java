package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.dominio.espectaculos.EspectaculoPublico;
import kamayuk.rentas.nucleo.dominio.espectaculos.EspectaculoPublicoRepository;
import kamayuk.rentas.nucleo.dominio.espectaculos.ImpuestoDeEspectaculo;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra un espectáculo público no deportivo y determina su impuesto, en un solo paso (RF-028,
 * #32; TUO Ley de Tributación Municipal, D.S. 156-2004-EF, arts. 54 a 59).
 *
 * <p>El evento se guarda sobre la tabla {@code espectaculo} que V2 ya dio de alta: nace {@code
 * REGISTRADO} y esta clase lo pasa a {@code LIQUIDADO} —{@link EspectaculoPublicoRepository
 * #liquidar}— al fijar la base imponible, en la misma transacción en que crea la {@link
 * Determinacion} con el monto (#32).
 *
 * <p>La alícuota se lee del conjunto sellado, con la <b>clave compuesta por tipo de espectáculo</b>
 * —igual que {@code RT001ValorDeTerreno} busca el arancel por vía—: teatro, cine, concierto y
 * taurino no pagan la misma alícuota (TUO LTM art. 56).
 */
@Service
public class RegistrarEspectaculo {

    /** El tipo del parámetro que trae la alícuota; la clave es el tipo de espectáculo. */
    public static final String ALICUOTA_ESPECTACULO = "ALICUOTA_ESPECTACULO";

    private static final String TABLA_AUDITADA = "determinacion";

    private final EspectaculoPublicoRepository eventos;
    private final DeterminacionRepository determinaciones;
    private final LectorDeParametros parametros;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarEspectaculo(
            EspectaculoPublicoRepository eventos,
            DeterminacionRepository determinaciones,
            LectorDeParametros parametros,
            Auditoria auditoria,
            Clock reloj) {
        this.eventos = eventos;
        this.determinaciones = determinaciones;
        this.parametros = parametros;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Registra el evento y determina su impuesto.
     *
     * @param ingresoDeclarado la base imponible que declara el organizador
     */
    @Transactional
    public Determinacion registrar(
            long organizadorId,
            String denominacion,
            String tipo,
            String lugar,
            LocalDate fechaEvento,
            @Nullable Integer aforo,
            @Nullable Dinero valorEntrada,
            Dinero ingresoDeclarado,
            Observacion observacion) {

        EspectaculoPublico guardado =
                eventos.insertar(
                        EspectaculoPublico.nuevo(
                                organizadorId,
                                denominacion,
                                tipo,
                                lugar,
                                fechaEvento,
                                aforo,
                                valorEntrada));

        Ejercicio ejercicio = Ejercicio.de(fechaEvento);
        ParametrosSellados sellados = parametros.vigenteEn(ejercicio);
        long conjuntoId = parametros.conjuntoVigenteEn(ejercicio).valor();
        String tipoNormalizado = tipo.strip().toUpperCase(java.util.Locale.ROOT);
        Alicuota alicuota =
                Alicuota.de(
                        sellados.exigirNumero(ALICUOTA_ESPECTACULO, tipoNormalizado)
                                .valor()
                                .toPlainString());

        Dinero montoDeterminado = ImpuestoDeEspectaculo.calcular(ingresoDeclarado, alicuota);

        eventos.liquidar(requerirId(guardado), ingresoDeclarado);

        Determinacion nueva =
                Determinacion.nuevaEspectaculos(
                        ejercicio,
                        organizadorId,
                        conjuntoId,
                        ingresoDeclarado,
                        montoDeterminado,
                        List.of(ALICUOTA_ESPECTACULO + ":" + tipoNormalizado));

        Determinacion determinada = determinaciones.insertar(nueva);
        auditar(determinada, observacion);
        return determinada;
    }

    private static long requerirId(EspectaculoPublico evento) {
        Long id = evento.id();
        if (id == null) {
            throw new IllegalStateException(
                    "Un espectaculo ya guardado siempre tiene identificador");
        }
        return id;
    }

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
        return "{\"tributo\":\"ESPECTACULOS\",\"contribuyenteId\":"
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
}
