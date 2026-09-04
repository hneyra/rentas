package kamayuk.rentas.coactiva.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.dominio.AdmisionEnCoactiva;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.InformeDeImportacion;
import kamayuk.rentas.coactiva.dominio.MotivoDeRechazo;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.PlantillaDeNumeroDeExpediente;
import kamayuk.rentas.coactiva.dominio.ValorDelExpediente;
import kamayuk.rentas.coactiva.dominio.ValorRechazado;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.ValorParaCoactiva;
import kamayuk.rentas.valores.ValoresEnCoactiva;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Importa a coactiva los valores exigibles de un contribuyente y abre su expediente (#40, RF-100).
 *
 * <h2>Empieza donde termina el pase</h2>
 *
 * <p>#39 dejo el valor con su movimiento {@code PCO}: notificado, con el plazo vencido y con la
 * diligencia que lo sustenta copiada en la fila del pase. Esta importacion no vuelve a comprobar
 * nada de eso —seria escribir por segunda vez una regla que ya esta escrita y verificada— sino que
 * exige el pase. El criterio exacto y su porque estan en {@link AdmisionEnCoactiva}.
 *
 * <h2>Informe por valor, no «3 de 7»</h2>
 *
 * <p>Lo que no entra se rechaza <b>con su motivo</b> (RF-100). Y el rechazo se decide <b>antes</b>
 * de escribir nada: es una funcion pura sobre la situacion del valor, de modo que ninguna fila
 * rechazada necesita que la base falle para saberse rechazada. Eso importa mas de lo que parece:
 * dentro de una transaccion de PostgreSQL, una sentencia que falla deja la transaccion abortada, y
 * un importador que confiara en el choque de clave unica para clasificar filas no podria continuar
 * despues del primer choque.
 *
 * <h2>Una sola transaccion, y es deliberado</h2>
 *
 * <p>Al reves que {@code ImportarFichas} —donde cada fila abre la suya—, aqui las escrituras son
 * <b>un solo acto</b>: el expediente, su apertura y sus valores. Un expediente numerado sin
 * valores, o un valor marcado como importado sin carpeta que lo agrupe, no son estados a medias
 * aceptables: son un procedimiento coactivo invalido.
 *
 * <p>La consecuencia es que dos importaciones simultaneas del mismo valor no producen dos
 * expedientes: la segunda choca contra {@code expediente_valor_unico_uq} y <b>se deshace
 * entera</b>, correlativo incluido. Reintentar entonces devuelve el rechazo explicado, porque la
 * primera ya dejo el valor dentro.
 */
@Service
public class ImportarValoresACoactiva {

    private final ExpedienteRepository expedientes;
    private final MovimientoDelExpedienteRepository movimientos;
    private final ValoresEnCoactiva valores;
    private final Auditoria auditoria;
    private final Clock reloj;

    public ImportarValoresACoactiva(
            ExpedienteRepository expedientes,
            MovimientoDelExpedienteRepository movimientos,
            ValoresEnCoactiva valores,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.valores = valores;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /** Importa a la fecha de hoy, con la plantilla de numeracion vigente. */
    @Transactional
    public InformeDeImportacion importar(Peticion peticion, Observacion observacion) {
        return importar(
                peticion,
                LocalDate.now(reloj),
                PlantillaDeNumeroDeExpediente.POR_OMISION,
                observacion);
    }

    /**
     * Importa a una fecha explicita.
     *
     * @param fecha a que dia se mira la situacion de cada valor (regla 9) y con que dia nace el
     *     expediente. Entra como argumento y no sale del reloj para que una importacion se pueda
     *     registrar con la fecha en que la resolucion lo dispuso
     * @param plantilla como se compone el numero; se recibe para que cerrar D-09 sea pasar otra y
     *     no tocar este codigo (ver {@link PlantillaDeNumeroDeExpediente})
     * @throws SinValoresPedidos si la peticion no nombra ningun valor y el contribuyente no tiene
     *     ninguno
     */
    @Transactional
    public InformeDeImportacion importar(
            Peticion peticion,
            LocalDate fecha,
            PlantillaDeNumeroDeExpediente plantilla,
            Observacion observacion) {

        List<ValorParaCoactiva> delContribuyente =
                valores.delContribuyente(peticion.contribuyenteId(), fecha);
        List<Candidato> candidatos = candidatos(peticion, delContribuyente, fecha);
        if (candidatos.isEmpty()) {
            throw new SinValoresPedidos(peticion.contribuyenteId());
        }

        Set<Long> yaImportados =
                expedientes.yaEnUnExpediente(
                        candidatos.stream()
                                .map(Candidato::valor)
                                .filter(java.util.Objects::nonNull)
                                .map(ValorParaCoactiva::id)
                                .toList());

        List<ValorParaCoactiva> admitidos = new ArrayList<>();
        List<ValorRechazado> rechazados = new ArrayList<>();
        for (Candidato candidato : candidatos) {
            ValorParaCoactiva valor = candidato.valor();
            if (valor == null) {
                rechazados.add(new ValorRechazado(candidato.numero(), candidato.motivoDirecto()));
                continue;
            }
            Optional<MotivoDeRechazo> motivo =
                    AdmisionEnCoactiva.rechazo(
                            valor.situacion(),
                            valor.conPaseACoactiva(),
                            yaImportados.contains(valor.id()));
            if (motivo.isPresent()) {
                rechazados.add(new ValorRechazado(valor.numero(), motivo.get()));
            } else {
                admitidos.add(valor);
            }
        }

        if (admitidos.isEmpty()) {
            // Ni expediente ni numero: un expediente vacio es un procedimiento sin deuda que
            // seguir, y su correlativo seria un hueco en el ejercicio que nadie podria explicar.
            return InformeDeImportacion.sinNadaQueImportar(rechazados);
        }

        Ejercicio ejercicio = Ejercicio.de(fecha);
        long correlativo = expedientes.siguienteCorrelativo(ejercicio);
        Instant ahora = reloj.instant();

        ExpedienteCoactivo abierto =
                expedientes.abrir(
                        new ExpedienteCoactivo(
                                null,
                                plantilla.componer(ejercicio, correlativo),
                                ejercicio,
                                correlativo,
                                peticion.contribuyenteId(),
                                peticion.ejecutor(),
                                peticion.auxiliar(),
                                fecha,
                                peticion.asunto(),
                                peticion.direccionReferencial(),
                                ahora,
                                null,
                                observacion));

        movimientos.registrar(
                MovimientoDelExpediente.apertura(
                        abierto.identificador(),
                        fecha,
                        "Importacion de "
                                + admitidos.size()
                                + " valor(es) a cobranza coactiva (RF-100)",
                        ahora,
                        observacion));

        List<ValorDelExpediente> importados = new ArrayList<>();
        for (ValorParaCoactiva valor : admitidos) {
            importados.add(expedientes.importar(abierto.identificador(), valor.id(), fecha));
            // La respuesta que #39 dejo anunciada: ACO cierra el ciclo que PCO abrio, y lo escribe
            // coactiva porque es coactiva quien ahora tiene el expediente que responde.
            valores.aceptarEnCoactiva(valor.id(), fecha, observacion);
        }

        auditar(abierto, admitidos, rechazados, fecha, observacion);
        return new InformeDeImportacion(abierto, importados, rechazados);
    }

    // ------------------------------------------------------------------

    /**
     * Que valores se van a examinar.
     *
     * <p>Sin numeros pedidos, todos los del contribuyente: la pantalla lista «los valores
     * pendientes» y quien opera marca los que quiere. Con numeros, exactamente esos —y los que no
     * aparezcan entre los suyos se rechazan diciendo si no existen o si son de otro—, porque
     * ignorar en silencio un numero tecleado dejaria a quien opera creyendo que lo importo.
     */
    private List<Candidato> candidatos(
            Peticion peticion, List<ValorParaCoactiva> delContribuyente, LocalDate fecha) {

        if (peticion.numerosDeValor().isEmpty()) {
            return delContribuyente.stream().map(Candidato::de).toList();
        }

        Map<String, ValorParaCoactiva> porNumero = new LinkedHashMap<>();
        for (ValorParaCoactiva valor : delContribuyente) {
            porNumero.put(valor.numero().toUpperCase(Locale.ROOT), valor);
        }

        List<Candidato> candidatos = new ArrayList<>();
        for (String pedido : peticion.numerosDeValor()) {
            String numero = pedido.strip().toUpperCase(Locale.ROOT);
            ValorParaCoactiva suyo = porNumero.get(numero);
            if (suyo != null) {
                candidatos.add(Candidato.de(suyo));
            } else {
                candidatos.add(
                        new Candidato(
                                numero,
                                null,
                                valores.porNumero(numero, fecha).isPresent()
                                        ? MotivoDeRechazo.DE_OTRO_CONTRIBUYENTE
                                        : MotivoDeRechazo.NO_EXISTE));
            }
        }
        return candidatos;
    }

    private void auditar(
            ExpedienteCoactivo expediente,
            List<ValorParaCoactiva> admitidos,
            List<ValorRechazado> rechazados,
            LocalDate fecha,
            Observacion observacion) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                "expediente_coactivo",
                                String.valueOf(expediente.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(expediente, admitidos, rechazados)));
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(
            ExpedienteCoactivo expediente,
            List<ValorParaCoactiva> admitidos,
            List<ValorRechazado> rechazados) {
        return "{\"expediente\":\""
                + expediente.numero()
                + "\",\"importados\":"
                + admitidos.size()
                + ",\"rechazados\":"
                + rechazados.size()
                + "}";
    }

    /** Un valor pedido, ya resuelto contra el padron de valores. */
    private record Candidato(
            String numero, @Nullable ValorParaCoactiva valor, MotivoDeRechazo motivoDirecto) {

        static Candidato de(ValorParaCoactiva valor) {
            // El motivo directo no se usa cuando hay valor: lo decide AdmisionEnCoactiva.
            return new Candidato(valor.numero(), valor, MotivoDeRechazo.NO_EXISTE);
        }
    }

    /**
     * Lo que la pantalla {@code importacion_valores} manda.
     *
     * @param contribuyenteId el obligado; ya resuelto por quien llama (ARQ-01 §4 regla 2)
     * @param numerosDeValor que valores se importan; vacia significa «todos los que se puedan»
     * @param ejecutor el ejecutor coactivo que se hara cargo
     * @param auxiliar el auxiliar coactivo, si se designa
     * @param asunto el asunto de la caratula
     * @param direccionReferencial donde notificar al obligado, si difiere del domicilio fiscal
     */
    public record Peticion(
            long contribuyenteId,
            List<String> numerosDeValor,
            String ejecutor,
            @Nullable String auxiliar,
            @Nullable String asunto,
            @Nullable String direccionReferencial) {

        public Peticion {
            numerosDeValor = List.copyOf(numerosDeValor);
        }
    }

    /** No hay nada que importar: ni se pidieron valores ni el contribuyente tiene ninguno. */
    public static final class SinValoresPedidos extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinValoresPedidos(long contribuyenteId) {
            super(
                    "El contribuyente "
                            + contribuyenteId
                            + " no tiene ningun valor emitido, y la peticion no nombra ninguno:"
                            + " no hay nada que importar a coactiva");
        }
    }
}
