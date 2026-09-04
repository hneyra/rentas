package kamayuk.rentas.rentas.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.rentas.dominio.beneficios.AcogimientoSimulado;
import kamayuk.rentas.rentas.dominio.beneficios.CampaniaDeBeneficio;
import kamayuk.rentas.rentas.dominio.beneficios.DesgloseAcogido;
import kamayuk.rentas.rentas.dominio.beneficios.SimulacionDeBeneficio;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@code consulta_deudas_beneficio}: que quedaria por pagar si esta deuda se acogiera a una campana
 * de beneficio (#72, RF-107).
 *
 * <h2>Por que vive en {@code rentas}</h2>
 *
 * <p>Por lo mismo que {@link ConsultaUnificada} (#25): la pantalla que mas datos agrega es de
 * {@code cuentacorriente} —la deuda— pero no puede vivir alli, porque «cuentacorriente no conoce a
 * nadie» (ARQ-01 §4 regla 2) y esta consulta necesita ademas al padron y a los parametros. {@code
 * rentas} es ademas el contexto <b>de</b> los beneficios: es quien los registra (#27, RF-107) y
 * quien publica {@code BeneficiosDelContribuyente}.
 *
 * <h2>Simular no es acoger, y por eso no escribe nada</h2>
 *
 * <p>Ningun asiento, ninguna condonacion, ningun recibo. Lo que devuelve es una <b>hipotesis</b>:
 * cuanto se ahorraria quien se acogiera hoy. Condonar de verdad mueve deuda del libro con su motivo
 * y su observacion (regla 10, RNF-051) y lo hara quien tenga la ordenanza firmada; hasta entonces,
 * esta pantalla no habilita ninguna escritura.
 *
 * <h2>Este caso de uso NO abre transaccion, y es deliberado</h2>
 *
 * <p>Es exactamente el reparto que #54 encontro en {@code ResumenAnualDeLicencias}, leido al reves
 * del de #25. Aqui <b>no se toca ningun repositorio propio</b>: las tres lecturas van por puertos
 * de otros contextos —{@link DirectorioDeContribuyentes}, {@link ConsultaDeDeudaPublica} y {@link
 * CampaniasDeBeneficioParametrizadas}—, y cada uno trae su propia transaccion y con ella su {@code
 * SET LOCAL}. Anadir un {@code @Transactional} aqui no aportaria aislamiento y romperia algo
 * concreto: {@code LectorDeParametrosSellados} lanza {@code EjercicioSinSellar} cuando el ejercicio
 * no tiene conjunto sellado —que es <b>hoy, en todas las municipalidades</b>—, y una excepcion
 * lanzada dentro de una transaccion que participa en la del anfitrion la marca
 * <i>rollback-only</i>: la consulta entera reventaria con {@code UnexpectedRollbackException} al
 * confirmar, aunque {@link CampaniasDeBeneficioParametrizadas} la capture. La pantalla no se
 * dibujaria nunca.
 *
 * <p>Lo que hace que esto sea seguro y no un descuido es que <b>no hay ninguna consulta propia que
 * quedara sin {@code SET LOCAL}</b>. El dia que esta clase lea una tabla por su cuenta, necesitara
 * la transaccion —y entonces habra que sacar la lectura de parametros fuera de ella—.
 */
@Service
public class SimularAcogimiento {

    /** Las dos columnas por las que esta rejilla se deja ordenar. Ver {@link #ordenar}. */
    private static final Set<String> ORDEN_ADMITIDO = Set.of("ejercicio", "tributo");

    private final DirectorioDeContribuyentes padron;
    private final ConsultaDeDeudaPublica deuda;
    private final CampaniasDeBeneficioParametrizadas campanias;
    private final Clock reloj;

    public SimularAcogimiento(
            DirectorioDeContribuyentes padron,
            ConsultaDeDeudaPublica deuda,
            CampaniasDeBeneficioParametrizadas campanias,
            Clock reloj) {
        this.padron = padron;
        this.deuda = deuda;
        this.campanias = campanias;
        this.reloj = reloj;
    }

    /** La fecha de hoy, del reloj inyectado y no de {@code LocalDate.now()} (regla 6). */
    public LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    /**
     * La simulacion, a la fecha de corte del criterio.
     *
     * <p><b>Un codigo que no existe es 404</b>, no una simulacion vacia: mismo criterio que {@link
     * ConsultaUnificada#de} y que la constancia de no adeudo. Decir «no se ahorraria nada» sobre
     * alguien que no esta en el padron de esta municipalidad seria afirmar algo falso sobre una
     * persona concreta.
     *
     * @throws ProblemaDeNegocio {@code NO_ENCONTRADO} si el codigo no identifica a nadie
     * @throws CampaniasDeBeneficioParametrizadas.CampaniaSinParametrizar si se pide simular contra
     *     una campana que el conjunto sellado no publica
     */
    public Simulacion de(Criterio criterio, Paginacion paginacion) {
        Objects.requireNonNull(criterio, "La simulacion es de un contribuyente concreto");
        Objects.requireNonNull(paginacion, "Sin paginacion no hay orden garantizado");

        ResumenDeContribuyente contribuyente =
                padron.porCodigo(criterio.codigoContribuyente())
                        .orElseThrow(
                                () ->
                                        new ProblemaDeNegocio(
                                                CodigoDeError.NO_ENCONTRADO,
                                                "No hay ningun contribuyente con el codigo "
                                                        + criterio.codigoContribuyente()
                                                        + " en esta municipalidad"));

        List<ObligacionPublica> todas =
                deuda.deTodoElContribuyente(contribuyente.id(), criterio.aLaFecha());

        // «Deuda total» es TODA la del contribuyente; «acogida» es la que la consulta selecciono.
        // Que sean dos cifras y no una es el punto de la pantalla: se ve cuanto se deja fuera al
        // acotar. Ninguna se compone en la interfaz (RNF-083).
        Dinero total = Dinero.CERO;
        List<ObligacionPublica> seleccionadas = new ArrayList<>();
        for (ObligacionPublica obligacion : todas) {
            total = total.mas(obligacion.total());
            if (criterio.incluye(obligacion)) {
                seleccionadas.add(obligacion);
            }
        }
        ordenar(seleccionadas, paginacion);

        List<DesgloseAcogido> desgloses = new ArrayList<>();
        for (ObligacionPublica obligacion : seleccionadas) {
            desgloses.add(
                    new DesgloseAcogido(
                            obligacion.insoluto(),
                            obligacion.reajuste(),
                            obligacion.interes(),
                            obligacion.gasto()));
        }

        CampaniasDeBeneficioParametrizadas.Vigentes vigentes =
                campanias.aLaFechaDe(criterio.aLaFecha());
        List<CampaniaDeBeneficio> publicadas = vigentes.publicadas();

        CampaniaDeBeneficio elegida =
                criterio.campania() == null ? null : vigentes.exigir(criterio.campania());
        AcogimientoSimulado acogimiento =
                elegida == null ? null : SimulacionDeBeneficio.de(desgloses, elegida);

        return new Simulacion(
                contribuyente,
                padron.domicilioFiscalDe(contribuyente.id(), criterio.aLaFecha()).orElse(null),
                criterio.aLaFecha(),
                total,
                SimulacionDeBeneficio.acogida(desgloses),
                desgloses.size(),
                elegida,
                acogimiento,
                publicadas,
                pagina(seleccionadas, paginacion),
                estadoDe(elegida, publicadas, vigentes));
    }

    /**
     * Ordena la seleccion por lo que pidio el cliente, dentro de una lista blanca.
     *
     * <p>Dos columnas y no cualquiera: la lista se ordena <b>en memoria</b> —no hay {@code ORDER
     * BY} que inyectar— pero un {@code ordenarPor} que no se reconoce no se puede ignorar en
     * silencio. La rejilla dibujaria la flecha de orden sobre una columna y mostraria otro orden,
     * que es la clase de mentira barata que nadie revisa. Se rechaza con 422, igual que hace {@code
     * ConsultarDeuda}.
     *
     * <p>Por omision, el ejercicio mas reciente primero: como se lee un listado de deuda en
     * ventanilla.
     */
    private static void ordenar(List<ObligacionPublica> seleccionadas, Paginacion paginacion) {
        if (!ORDEN_ADMITIDO.contains(paginacion.ordenarPor())) {
            throw new IllegalArgumentException(
                    "consulta_deudas_beneficio no admite ordenar por '"
                            + paginacion.ordenarPor()
                            + "'. Se admite: "
                            + ORDEN_ADMITIDO);
        }
        Comparator<ObligacionPublica> primario =
                "tributo".equals(paginacion.ordenarPor())
                        ? Comparator.comparing(ObligacionPublica::tributo)
                        : Comparator.comparing((ObligacionPublica o) -> o.ejercicio().valor());
        if (paginacion.direccion() == Paginacion.Direccion.DESCENDENTE) {
            primario = primario.reversed();
        }
        seleccionadas.sort(
                primario.thenComparing((ObligacionPublica o) -> o.ejercicio().valor())
                        .thenComparing(ObligacionPublica::tributo));
    }

    /**
     * La frase que explica lo que se ve, <b>redactada por el servidor</b> (RNF-080, RNF-083).
     *
     * <p>La escribe quien tiene las cifras, por lo mismo que {@code estadoDeLaConsulta} de la ficha
     * unificada: el dia que la cifra y su explicacion discrepen, tienen que salir del mismo sitio.
     * Y hace falta: una pantalla de acogimiento con la deuda intacta y ningun descuento es
     * indistinguible de una campana que descuenta cero.
     */
    private static String estadoDe(
            @Nullable CampaniaDeBeneficio elegida,
            List<CampaniaDeBeneficio> publicadas,
            CampaniasDeBeneficioParametrizadas.Vigentes vigentes) {
        if (elegida != null) {
            return "Acogimiento simulado a «"
                    + elegida.nombre()
                    + "»: "
                    + elegida.alicuota()
                    + " sobre "
                    + elegida.base().etiqueta()
                    + ". Es una simulación: no modifica la deuda registrada.";
        }
        if (publicadas.isEmpty()) {
            return "No hay ninguna campaña de beneficio publicada para el ejercicio "
                    + vigentes.ejercicio()
                    + ": la deuda se muestra sin acogimiento.";
        }
        return "Sin campaña elegida: la deuda se muestra sin acogimiento. Hay "
                + publicadas.size()
                + " campaña(s) publicada(s) para el ejercicio "
                + vigentes.ejercicio()
                + ".";
    }

    /**
     * Recorta la lista ya ordenada a la pagina pedida.
     *
     * <p>En memoria y no en SQL, igual que {@link ConsultaUnificada}: {@link
     * ConsultaDeDeudaPublica#deTodoElContribuyente} devuelve la lista completa —para un
     * contribuyente nunca es larga— y la simulacion necesita <b>todas</b> las obligaciones
     * seleccionadas para sumar. Sumar sobre la pagina devuelta daria un ahorro que cambia al pasar
     * de pagina, que es el defecto que #25 documenta en su resumen de saldos.
     */
    private static <T> Pagina<T> pagina(List<T> todas, Paginacion paginacion) {
        int desde = Math.min(paginacion.desplazamiento(), todas.size());
        int hasta = Math.min(desde + paginacion.tamano(), todas.size());
        return Pagina.de(List.copyOf(todas.subList(desde, hasta)), paginacion, todas.size());
    }

    /**
     * Que se simula.
     *
     * @param codigoContribuyente de quien es la deuda; obligatorio
     * @param aLaFecha la fecha de corte con la que se calcula la deuda (regla 9)
     * @param tributo el tributo al que se acota la seleccion, o nulo para no acotarla
     * @param campania la campana a la que se simula el acogimiento, o nulo para no simular ninguna
     */
    public record Criterio(
            String codigoContribuyente,
            LocalDate aLaFecha,
            @Nullable String tributo,
            @Nullable String campania) {

        public Criterio {
            Objects.requireNonNull(
                    codigoContribuyente, "La simulacion es de un contribuyente concreto");
            Objects.requireNonNull(aLaFecha, "Toda cifra de deuda indica su fecha (RNF-075)");
            if (codigoContribuyente.isBlank()) {
                throw new IllegalArgumentException(
                        "contribuyente es obligatorio: simular el acogimiento del padron entero no"
                                + " es una consulta de ventanilla");
            }
        }

        /** Si la obligacion entra en lo acogido. Sin filtro entran todas. */
        boolean incluye(ObligacionPublica obligacion) {
            return tributo == null || tributo.equals(obligacion.tributo());
        }
    }

    /**
     * Lo que la pantalla dibuja.
     *
     * @param contribuyente de quien es
     * @param domicilioFiscal el vigente a la fecha de corte, o nulo si no hay ninguno registrado
     * @param aLaFecha la fecha de corte de todas las cifras
     * @param deudaTotal toda la deuda del contribuyente, sin acotar
     * @param deudaAcogida la parte que la consulta selecciono
     * @param registrosAcogidos cuantas obligaciones son
     * @param campania la campana elegida, o nulo si no se eligio ninguna
     * @param acogimiento lo que la campana produce; nulo si no hay campana elegida
     * @param campaniasPublicadas las que el conjunto sellado publica; vacia si no hay ninguna
     * @param obligaciones la pagina de obligaciones seleccionadas
     * @param estadoDeLaSimulacion la frase que explica lo anterior, redactada por el servidor
     */
    public record Simulacion(
            ResumenDeContribuyente contribuyente,
            @Nullable String domicilioFiscal,
            LocalDate aLaFecha,
            Dinero deudaTotal,
            Dinero deudaAcogida,
            int registrosAcogidos,
            @Nullable CampaniaDeBeneficio campania,
            @Nullable AcogimientoSimulado acogimiento,
            List<CampaniaDeBeneficio> campaniasPublicadas,
            Pagina<ObligacionPublica> obligaciones,
            String estadoDeLaSimulacion) {}
}
