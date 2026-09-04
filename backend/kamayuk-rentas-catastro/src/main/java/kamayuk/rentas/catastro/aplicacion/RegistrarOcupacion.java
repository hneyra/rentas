package kamayuk.rentas.catastro.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.CondicionDeTitularidad;
import kamayuk.rentas.catastro.dominio.Inquilino;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.Titularidad;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Quien es dueno del predio y quien lo ocupa (#490, RF-005).
 *
 * <p>La titularidad es <b>la operacion entre un contribuyente y un predio</b>: quien es dueno, de
 * que porcion y desde cuando. Hasta aqui se podia leer ({@code GET
 * /catastro/predios/{predioId}/titulares}, #366) y transferir ({@code POST
 * /rentas/transferencias/predio}, #29), pero <b>el primer titular no se podia registrar</b>: solo
 * se puede transferir lo que ya tiene dueno, y lo unico que daba el primero era la siembra o
 * inscribir la ficha con su bloque de titular.
 *
 * <h2>La suma de cuotas la vigila la base, no este codigo</h2>
 *
 * <p>Que las cuotas vigentes no pasen del 100 % lo sostiene un <b>disparador diferido</b>, y eso no
 * es un detalle de implementacion: si fuera inmediato, una transferencia legitima —cerrar una cuota
 * y abrir otra en la misma transaccion— seria imposible, porque entre las dos operaciones el total
 * pasa de 100 a proposito. Se midio en #16.
 *
 * <p>La consecuencia para esta clase es que <b>no comprueba la suma</b>: escribe dentro de su
 * transaccion y deja que el disparador hable al confirmar. Un {@code if} aqui adelantaria el
 * rechazo unos milisegundos y, lo que importa, tendria que decidir que hacer con la ventana en la
 * que el total pasa de 100 legitimamente — que es exactamente lo que el disparador diferido existe
 * para no tener que decidir.
 *
 * <p><b>Y no reabre D-12</b> ([ADR-0019]): cuando la titularidad no llega al 100 %, se determina
 * solo la porcion con titular identificado y el resto queda como senal de fiscalizacion. Aqui se
 * registra lo que hay; no se exige que sume.
 *
 * <p>El titular entra por <b>codigo de contribuyente</b> —lo que el tecnico tiene delante—, y
 * resolverlo es una consulta: va dentro de la transaccion, como todo lo demas (#486).
 *
 * <p>Ningun metodo recibe el identificador de municipalidad (regla 2), y todos exigen la
 * observacion del usuario (regla 10).
 */
@Service
public class RegistrarOcupacion {

    private final CatastroRepository catastro;
    private final DirectorioDeContribuyentes padron;
    private final RegistrarPredio predios;

    public RegistrarOcupacion(
            CatastroRepository catastro,
            DirectorioDeContribuyentes padron,
            RegistrarPredio predios) {
        this.catastro = catastro;
        this.padron = padron;
        this.predios = predios;
    }

    /**
     * Registra una cuota de titularidad: el primer titular, o uno mas de una copropiedad.
     *
     * <p>Declarar una copropiedad es registrar dos o mas cuotas que sumen 100 %. Hasta aqui solo se
     * producia con una transferencia parcial, y la siembra de demostracion no podia declararla —una
     * segunda fila del mismo predio se rechazaba, y con razon: no habia por donde declarar la
     * primera como parcial—.
     */
    @Transactional
    public Titularidad registrarTitular(
            long predioId, DatosDelTitular datos, LocalDate desde, Observacion observacion) {

        Predio predio =
                catastro.predio(predioId).orElseThrow(() -> new PredioInexistente(predioId));
        if (!predio.estaActivo()) {
            throw new PredioRetirado(predioId);
        }

        ResumenDeContribuyente contribuyente =
                padron.porCodigo(datos.codigoContribuyente())
                        .orElseThrow(
                                () ->
                                        new InscribirFicha.ReferenciaInexistente(
                                                "contribuyente", datos.codigoContribuyente()));

        Titularidad nueva =
                datos.condicion().esPorElTotal()
                        ? Titularidad.unico(
                                predioId, contribuyente.id(), desde, datos.documentoOrigen())
                        : Titularidad.parcial(
                                predioId,
                                contribuyente.id(),
                                datos.condicion(),
                                porcentajeExigido(datos),
                                desde,
                                datos.documentoOrigen());

        return predios.registrarTitularidad(nueva, observacion);
    }

    /** Alta de un inquilino: el manual lo registra para la cobranza de arbitrios (#31). */
    @Transactional
    public Inquilino registrarInquilino(
            long predioId, DatosDelInquilino datos, Observacion observacion) {

        Predio predio =
                catastro.predio(predioId).orElseThrow(() -> new PredioInexistente(predioId));
        if (!predio.estaActivo()) {
            throw new PredioRetirado(predioId);
        }

        ResumenDeContribuyente contribuyente =
                padron.porCodigo(datos.codigoContribuyente())
                        .orElseThrow(
                                () ->
                                        new InscribirFicha.ReferenciaInexistente(
                                                "contribuyente", datos.codigoContribuyente()));

        Inquilino nuevo =
                new Inquilino(
                        null,
                        predioId,
                        contribuyente.id(),
                        datos.uso(),
                        datos.desde(),
                        null,
                        datos.documentoOrigen());

        return predios.registrarInquilino(nuevo, observacion);
    }

    /**
     * Deja de ocupar el predio. <b>No se borra</b> (regla 4, RNF-051): una determinacion de
     * arbitrios anterior pudo apoyarse en el, y explicarla exige que la fila siga ahi.
     *
     * <p>Uno ya cerrado no se vuelve a cerrar: es {@link OcupacionInexistente}, que es lo que es
     * —no hay tal ocupacion abierta que terminar—.
     */
    @Transactional
    public Inquilino finalizarInquilino(
            long predioId, long inquilinoId, LocalDate hasta, Observacion observacion) {

        Inquilino existente =
                catastro.inquilino(inquilinoId)
                        .filter(inquilino -> inquilino.predioId() == predioId)
                        .filter(inquilino -> inquilino.vigenciaHasta() == null)
                        .orElseThrow(() -> new OcupacionInexistente(predioId, inquilinoId));

        return predios.finalizarInquilino(existente, hasta, observacion);
    }

    // ------------------------------------------------------------------

    private static Porcentaje porcentajeExigido(DatosDelTitular datos) {
        Porcentaje porcentaje = datos.porcentaje();
        if (porcentaje == null) {
            throw new IllegalArgumentException(
                    "Un titular "
                            + datos.condicion()
                            + " necesita su porcentaje: solo el propietario unico lo es por el"
                            + " total");
        }
        return porcentaje;
    }

    /**
     * El titular que se declara. Entra por <b>codigo de contribuyente</b> y no por identificador
     * interno: es lo que el tecnico tiene delante, y resolverlo es parte de la transaccion.
     *
     * <p>Es la misma forma que {@link InscribirFicha.DatosDelTitular}, y se declara aparte a
     * proposito: aquella describe el titular que <b>viene dentro del alta de una ficha</b>, y esta
     * el de un acto propio. Compartir el record ataria dos actos que pueden divergir.
     */
    public record DatosDelTitular(
            String codigoContribuyente,
            CondicionDeTitularidad condicion,
            @Nullable Porcentaje porcentaje,
            String documentoOrigen) {

        public DatosDelTitular {
            Objects.requireNonNull(codigoContribuyente, "El titular entra por su codigo");
            Objects.requireNonNull(condicion, "La titularidad necesita su condicion");
            Objects.requireNonNull(documentoOrigen, "La titularidad necesita su documento");
        }
    }

    /** El inquilino que se declara. */
    public record DatosDelInquilino(
            String codigoContribuyente,
            @Nullable String uso,
            LocalDate desde,
            String documentoOrigen) {

        public DatosDelInquilino {
            Objects.requireNonNull(codigoContribuyente, "El inquilino entra por su codigo");
            Objects.requireNonNull(desde, "El inquilino necesita desde cuando ocupa el predio");
            Objects.requireNonNull(documentoOrigen, "El inquilino necesita su documento");
        }
    }

    /** No hay ningun predio con ese identificador en esta municipalidad. */
    public static final class PredioInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioInexistente(long predioId) {
            super("No hay ningun predio con identificador " + predioId + " en esta municipalidad");
        }
    }

    /** El predio existe pero esta retirado del padron: reactivarlo es otro acto. */
    public static final class PredioRetirado extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioRetirado(long predioId) {
            super(
                    "El predio "
                            + predioId
                            + " esta dado de baja; declararle titular u ocupante afirmaria algo"
                            + " sobre una unidad que el padron da por retirada");
        }
    }

    /** No hay ninguna ocupacion abierta con ese identificador en ese predio. */
    public static final class OcupacionInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        OcupacionInexistente(long predioId, long inquilinoId) {
            super(
                    "El predio "
                            + predioId
                            + " no tiene ninguna ocupacion abierta con identificador "
                            + inquilinoId);
        }
    }
}
