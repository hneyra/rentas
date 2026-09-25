package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.dominio.espectaculos.ClaseDeEspectaculo;
import kamayuk.rentas.nucleo.dominio.espectaculos.EspectaculoPublico;
import kamayuk.rentas.nucleo.dominio.espectaculos.EspectaculoPublicoRepository;
import kamayuk.rentas.nucleo.dominio.espectaculos.ImpuestoDeEspectaculo;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.parametros.ConjuntoVigente;
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
 * <p>La alícuota se lee del conjunto sellado con la llave que {@code normativa} publica, {@link
 * LlavesDelConjunto#ESPECTACULO_ALICUOTA}{@code :‹clase›}, y la clase es una de las siete del art.
 * 57 —{@link ClaseDeEspectaculo}—, no el texto que se teclea (#376). Hasta #376 la llave era {@code
 * ALICUOTA_ESPECTACULO:‹tipo tecleado›}, que nadie publica: con el conjunto real la operación
 * contestaba siempre 422 «falta publicar».
 *
 * <p>El taurino declara solo eso, {@link ClaseDeEspectaculo#TAURINO}: cuál de sus dos alícuotas
 * rige lo decide {@link ClaseDeEspectaculo#delTaurino} con el valor de la entrada y la UIT del
 * <b>mismo</b> conjunto sellado.
 *
 * <h2>Primero lo que puede faltar, después la escritura (#422)</h2>
 *
 * <p>Hasta #422 lo primero que hacía era insertar el espectáculo, y después leía el conjunto
 * sellado: sin conjunto, o con un organizador que no está en el padrón, el rechazo llegaba con la
 * fila ya escrita —y en el segundo caso ni siquiera llegaba: {@code espectaculo_contribuyente_fk}
 * rechazaba el {@code INSERT} y salía como un 500 con incidencia ERROR—. Ahora se leen los
 * parámetros, se decide la clase del art. 57 (#376) y se resuelve al organizador por {@link
 * DirectorioDeContribuyentes} <b>antes</b> de escribir nada, y el borde contesta 422 o 404 según lo
 * que falte.
 */
@Service
public class RegistrarEspectaculo {

    private static final String TABLA_AUDITADA = "determinacion";

    private final EspectaculoPublicoRepository eventos;
    private final DeterminacionRepository determinaciones;
    private final LectorDeParametros parametros;
    private final DirectorioDeContribuyentes padron;
    private final Auditoria auditoria;

    public RegistrarEspectaculo(
            EspectaculoPublicoRepository eventos,
            DeterminacionRepository determinaciones,
            LectorDeParametros parametros,
            DirectorioDeContribuyentes padron,
            Auditoria auditoria) {
        this.eventos = eventos;
        this.determinaciones = determinaciones;
        this.parametros = parametros;
        this.padron = padron;
        this.auditoria = auditoria;
    }

    /**
     * Registra el evento y determina su impuesto.
     *
     * @param tipo la clase del art. 57 que declara el organizador, o {@link
     *     ClaseDeEspectaculo#TAURINO}; ver {@link ClaseDeEspectaculo#declarada}
     * @param ingresoDeclarado la base imponible que declara el organizador
     * @throws IllegalArgumentException si lo declarado no es una clase del art. 57, o es un taurino
     *     sin valor de entrada
     * @throws OrganizadorInexistente si el organizador no esta en el padron (#422)
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

        // El conjunto se lee ANTES de guardar el evento (#422), y la clase del art. 57 se decide
        // con el (#376): la del taurino depende de la UIT, y un evento cuya clase no se puede
        // decidir no se registra.
        Ejercicio ejercicio = Ejercicio.de(fechaEvento);
        // Una resolucion, no dos (#361): los parametros y el id del mismo conjunto.
        ConjuntoVigente conjunto = parametros.vigenteConSuConjunto(ejercicio);
        ParametrosSellados sellados = conjunto.parametros();
        long conjuntoId = conjunto.id();
        @Nullable Dinero uit =
                ClaseDeEspectaculo.declaraUnTaurino(tipo)
                        ? new Dinero(sellados.exigirNumero(LlavesDelConjunto.UIT, null).valor())
                        : null;
        ClaseDeEspectaculo clase = ClaseDeEspectaculo.declarada(tipo, valorEntrada, uit);
        Alicuota alicuota =
                Alicuota.de(
                        sellados.exigirNumero(LlavesDelConjunto.ESPECTACULO_ALICUOTA, clase.clave())
                                .valor()
                                .toPlainString());

        if (!padron.porIds(Set.of(organizadorId)).containsKey(organizadorId)) {
            throw new OrganizadorInexistente(organizadorId);
        }

        Dinero montoDeterminado = ImpuestoDeEspectaculo.calcular(ingresoDeclarado, alicuota);

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

        eventos.liquidar(requerirId(guardado), ingresoDeclarado);

        Determinacion nueva =
                Determinacion.nuevaEspectaculos(
                        ejercicio,
                        organizadorId,
                        conjuntoId,
                        ingresoDeclarado,
                        montoDeterminado,
                        List.of(LlavesDelConjunto.ESPECTACULO_ALICUOTA + ":" + clase.clave()));

        Determinacion determinada = determinaciones.insertar(nueva);
        auditar(determinada, observacion);
        return determinada;
    }

    /** El organizador no esta en el padron de esta municipalidad (#422). */
    public static final class OrganizadorInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        OrganizadorInexistente(long id) {
            super(
                    "No hay ningun contribuyente con identificador "
                            + id
                            + " en esta municipalidad: no puede organizar el espectaculo");
        }
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
