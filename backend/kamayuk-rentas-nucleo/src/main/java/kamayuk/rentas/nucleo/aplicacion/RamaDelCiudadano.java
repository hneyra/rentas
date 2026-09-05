package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.compartido.CiudadanoContext;
import kamayuk.rentas.contribuyentes.AcreditacionEnElPadron;
import kamayuk.rentas.contribuyentes.ContribuyenteAcreditado;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.dominio.Observacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lo que el ciudadano ve <b>en una municipalidad</b>: el sondeo del padron y, si figura, su deuda y
 * sus predios a la fecha (ADR-0020, #57).
 *
 * <h2>Es una rama del recorrido, no una consulta suelta</h2>
 *
 * <p>La llama {@link ConsultaDelCiudadano} una vez por municipalidad activa, ya con el contexto de
 * tenant fijado por {@code RecorridoPorMunicipalidades} y dentro de la transaccion que ese
 * recorrido abre para ella. Por eso <b>no recibe la municipalidad</b>: no la necesita y no debe
 * poder usarla (regla 2). Lo unico que sabe es a quien pregunta —del contexto del ciudadano— y a
 * que fecha.
 *
 * <h2>El sondeo primero, y si no figura no se lee nada mas</h2>
 *
 * <p>Vacio significa «en esta municipalidad no esta», y entonces esta rama <b>no lee su deuda, no
 * lee sus predios y no escribe ninguna fila de auditoria</b>. Las tres cosas por el mismo motivo:
 * el sondeo del padron no es un acceso, y auditarlo convertiria la bitacora de cada municipio en
 * una forma de saber que alguien existe en otro (ADR-0020 §5, precedente #344).
 *
 * <h2>Por que es transaccional de escritura</h2>
 *
 * <p>Por la fila de {@code ACCESO} y solo por ella: tiene que caer <b>dentro</b> de la misma
 * transaccion que la lectura, o quedaria constancia de las consultas que fallaron y no de las que
 * si devolvieron la deuda de una persona. Su {@link Observacion} la compone el sistema porque aqui
 * no hay usuario que observe —nadie escribe un motivo para mirar su propia deuda—, y por eso este
 * metodo esta nombrado uno a uno en {@code SIN_USUARIO_QUE_OBSERVE}, con este porque.
 *
 * <h2>Las mismas cifras que en ventanilla</h2>
 *
 * <p>El resumen es {@link ConsultaUnificada.ResumenDeSaldos}, el mismo tipo y el mismo calculo que
 * la ficha 360° del back-office: las cinco partes sumadas por el servidor sobre <b>todas</b> las
 * obligaciones y el total como suma de las cuatro (RNF-083). Un segundo calculo aqui es como se
 * consigue que el portal y la ventanilla digan cifras distintas de la misma persona el mismo dia.
 */
@Service
public class RamaDelCiudadano {

    /**
     * La tabla que se anota en la bitacora.
     *
     * <p>{@code contribuyente} y no {@code cuenta_corriente_asiento}: lo que se consulto es la
     * situacion de una persona, y es por esa persona por quien un auditor buscaria.
     */
    private static final String TABLA_AUDITADA = "contribuyente";

    private final AcreditacionEnElPadron acreditacion;
    private final ConsultaDeDeudaPublica deuda;
    private final PrediosDelContribuyente predios;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RamaDelCiudadano(
            AcreditacionEnElPadron acreditacion,
            ConsultaDeDeudaPublica deuda,
            PrediosDelContribuyente predios,
            Auditoria auditoria,
            Clock reloj) {
        this.acreditacion = acreditacion;
        this.deuda = deuda;
        this.predios = predios;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Lee la situacion del ciudadano en la municipalidad en curso, o nada si no figura en ella.
     *
     * @param aLaFecha la fecha de corte, <b>la misma para todas las ramas</b>. Entra como argumento
     *     y no sale de ningun reloj (regla 6, regla 9): si cada rama resolviera la suya, el total
     *     consolidado sumaria cifras de instantes distintos y las presentaria como una sola
     */
    @Transactional
    public Optional<Situacion> leer(LocalDate aLaFecha) {
        Objects.requireNonNull(
                aLaFecha, "Toda cifra indica su fecha de calculo (RNF-075, regla 9)");
        DocumentoIdentidad documento = CiudadanoContext.actual();

        Optional<ContribuyenteAcreditado> encontrado = acreditacion.de(documento);
        if (encontrado.isEmpty()) {
            // Aqui no figura. No se lee nada mas y no se deja rastro: ver el javadoc.
            return Optional.empty();
        }
        ContribuyenteAcreditado contribuyente = encontrado.get();

        List<ObligacionPublica> obligaciones =
                deuda.deTodoElContribuyente(contribuyente.id(), aLaFecha);
        List<PredioDelContribuyente> suyos = predios.de(contribuyente.id(), aLaFecha);

        registrarElAcceso(contribuyente, aLaFecha, obligaciones.size(), suyos.size());

        return Optional.of(
                new Situacion(
                        contribuyente,
                        ConsultaUnificada.ResumenDeSaldos.de(obligaciones, aLaFecha),
                        List.copyOf(obligaciones),
                        List.copyOf(suyos)));
    }

    /**
     * La fila de la bitacora de <b>esta</b> municipalidad, en la misma transaccion que la lectura.
     *
     * <p>El usuario no se pasa: sale de {@code OrigenContext}, que para una peticion del portal es
     * la cuenta del ciudadano en su realm. En el enrolamiento de ventanilla esa cuenta se
     * <b>deriva</b> de su documento —{@code dni-70123456}, el tipo y el numero (ADR-0020 §6,
     * #415)—, asi que la bitacora identifica al ciudadano por su documento; y ese documento ya esta
     * en el padron de esta municipalidad, de modo que la fila no publica aqui nada que aqui no se
     * supiera.
     *
     * <p>El tipo va delante y no es adorno: {@code CE 12345678} y {@code DNI 12345678} son dos
     * personas distintas, y con la cuenta llamada solo por el numero serian la misma fila de
     * bitacora.
     */
    private void registrarElAcceso(
            ContribuyenteAcreditado contribuyente,
            LocalDate aLaFecha,
            int obligaciones,
            int predios) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                // Del reloj inyectado: la particion de la bitacora es el ejercicio
                                // del ACTO, y consultar en 2027 la deuda al 31/12/2026 es un acto
                                // de 2027.
                                LocalDate.now(reloj),
                                TABLA_AUDITADA,
                                "contribuyente=" + contribuyente.codigo(),
                                Operacion.ACCESO,
                                Observacion.de(
                                        "Consulta del contribuyente sobre su propia situacion desde"
                                                + " el portal, al "
                                                + aLaFecha
                                                + " (#57, ADR-0020)"))
                        // Solo cifras y la fecha: aqui no entra texto del usuario, asi que no hay
                        // comilla que pueda romper el cast a jsonb.
                        .con(
                                null,
                                "{\"aLaFecha\":\""
                                        + aLaFecha
                                        + "\",\"obligaciones\":"
                                        + obligaciones
                                        + ",\"predios\":"
                                        + predios
                                        + "}"));
    }

    /**
     * Lo que hay de esta persona en una municipalidad.
     *
     * <p>Sin ubigeo ni nombre de municipalidad: los pone {@link ConsultaDelCiudadano} al emparejar
     * el resultado con la municipalidad de la que salio. Esta rama no sabe donde esta, y es
     * deliberado.
     *
     * @param contribuyente quien es aqui, con su codigo y si sigue de alta
     * @param resumen las cinco cifras del resumen de saldos, a la fecha de corte
     * @param obligaciones las obligaciones con saldo, sin paginar: para una persona nunca son
     *     muchas, y el portal responde en una sola ida y vuelta
     * @param predios los predios de los que es titular, con <b>su</b> porcentaje y sin nombrar a
     *     ningun copropietario (ADR-0019)
     */
    public record Situacion(
            ContribuyenteAcreditado contribuyente,
            ConsultaUnificada.ResumenDeSaldos resumen,
            List<ObligacionPublica> obligaciones,
            List<PredioDelContribuyente> predios) {

        public Situacion {
            Objects.requireNonNull(contribuyente, "La situacion es de un contribuyente concreto");
            Objects.requireNonNull(resumen, "La situacion necesita su resumen de saldos");
            obligaciones = List.copyOf(obligaciones);
            predios = List.copyOf(predios);
        }
    }
}
