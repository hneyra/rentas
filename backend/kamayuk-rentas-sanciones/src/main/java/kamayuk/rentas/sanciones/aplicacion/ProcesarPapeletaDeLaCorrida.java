package kamayuk.rentas.sanciones.aplicacion;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.CorridaDeValores;
import kamayuk.rentas.sanciones.dominio.CorridaDeValoresRepository;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.ItemDeCorrida;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucion;
import kamayuk.rentas.sanciones.dominio.NotificacionDeResolucionRepository;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerencia;
import kamayuk.rentas.sanciones.dominio.ResolucionDeGerenciaRepository;
import kamayuk.rentas.sanciones.dominio.TipoDeResolucionDeGerencia;
import kamayuk.rentas.valores.EmisionDeValoresDeMultas;
import kamayuk.rentas.valores.ValorDeMulta;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resuelve un candidato de una corrida masiva: le emite su resolución de multa, o dice por qué no
 * (#53, RF-066, RF-073).
 *
 * <h2>Es un {@code @Service} distinto del bucle, a propósito</h2>
 *
 * <p>Mismo patrón que {@code valores.ProcesarItemMasivo} frente a {@code GenerarCorridaMasiva}, y
 * que {@code catastro.RegistrarVia} frente a {@code ImportarVias}. {@link #procesar} lleva su
 * propio {@code @Transactional}; llamarlo desde el bucle de {@link GenerarCorridaDeValores} pasa
 * por el proxy de Spring y abre una transacción nueva por candidato. Si estuviera en la misma clase
 * que el bucle sería auto-invocación —el proxy no intercepta una llamada a {@code this}— y todos
 * los candidatos caerían en una sola transacción: la primera papeleta que fallara se llevaría por
 * delante las que ya se habían emitido.
 *
 * <h2>Emitir y marcar van juntos, y por eso no duplica</h2>
 *
 * <p>{@link CorridaDeValoresRepository#marcarGenerado} ocurre en <b>la misma transacción</b> que la
 * emisión. Si el valor se emitiera y el proceso se cortara antes de marcar, una reanudación vería
 * el candidato {@code PENDIENTE} y emitiría un segundo valor por la misma papeleta. Y si aun así
 * dos procesos llegaran a la vez, quien lo impide es {@code papeleta_valor_unico_uq} (V47), no un
 * {@code if}: el segundo choca contra el índice y su transacción entera se deshace.
 *
 * <h2>Las tres razones por las que una papeleta no procede</h2>
 *
 * <p>Se comprueban <b>a {@code fechaCriterio}</b>, la congelada de la corrida, nunca a «hoy»:
 *
 * <ol>
 *   <li>No hay resolución de gerencia que ordene la cobranza —la ordinaria en tránsito, la del
 *       procedimiento sancionador en administrativas—. Se arregla dictándola.
 *   <li>La hay, pero ninguna diligencia surtió efecto. Se arregla notificándola.
 *   <li>Surtió efecto, pero el plazo que concedió todavía corre. Se arregla esperando.
 * </ol>
 *
 * <p>Las tres se guardan como {@code NO_PROCEDE} con su motivo, y no como un fallo: un candidato al
 * que le falta la notificación no es un error del proceso, es trabajo pendiente de otra área, y
 * quien opera necesita saber cuál de las tres le tocó. Un único «no procede» le dejaría adivinando.
 */
@Service
public class ProcesarPapeletaDeLaCorrida {

    private final PapeletaRepository papeletas;
    private final ResolucionDeGerenciaRepository resoluciones;
    private final NotificacionDeResolucionRepository diligencias;
    private final EmisionDeValoresDeMultas emision;
    private final CorridaDeValoresRepository corridas;

    public ProcesarPapeletaDeLaCorrida(
            PapeletaRepository papeletas,
            ResolucionDeGerenciaRepository resoluciones,
            NotificacionDeResolucionRepository diligencias,
            EmisionDeValoresDeMultas emision,
            CorridaDeValoresRepository corridas) {
        this.papeletas = papeletas;
        this.resoluciones = resoluciones;
        this.diligencias = diligencias;
        this.emision = emision;
        this.corridas = corridas;
    }

    /** Cómo terminó de resolverse el candidato. */
    public enum Resultado {
        GENERADO,
        SIN_DEUDA,
        NO_PROCEDE
    }

    /**
     * Resuelve el candidato.
     *
     * <p>La {@link Observacion} entra <b>en la firma</b> y no solo dentro de {@code corrida}: es lo
     * que exige la regla 10 sobre todo método transaccional que escribe, y lo que deja a la vista
     * con qué observación queda auditado cada valor de la corrida.
     */
    @Transactional
    public Resultado procesar(
            CorridaDeValores corrida, ItemDeCorrida item, Observacion observacion) {

        Objects.requireNonNull(corrida, "El candidato pertenece a una corrida");
        Objects.requireNonNull(item, "No hay candidato que procesar");
        Objects.requireNonNull(observacion, "Sin observacion no se emite (regla 10, RNF-052)");

        long itemId = item.identificador();
        Papeleta papeleta =
                papeletas
                        .porId(item.papeletaId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "El candidato "
                                                        + itemId
                                                        + " apunta a una papeleta que no existe en"
                                                        + " esta municipalidad"));

        String impedimento = impedimentoDe(papeleta, corrida);
        if (impedimento != null) {
            corridas.marcarNoProcede(itemId, impedimento);
            return Resultado.NO_PROCEDE;
        }

        try {
            ValorDeMulta valor =
                    emision.emitirPorMulta(
                            papeleta.obligadoId(),
                            ObligacionDeLaPapeleta.tributoDe(papeleta.familia()),
                            Ejercicio.de(papeleta.fechaInfraccion()),
                            papeleta.familia() == Familia.ADMINISTRATIVA
                                    ? papeleta.predioId()
                                    : null,
                            papeleta.familia() == Familia.TRANSITO ? papeleta.vehiculoId() : null,
                            corrida.fechaCriterio(),
                            observacion);
            corridas.marcarGenerado(itemId, valor.id(), valor.numero());
            return Resultado.GENERADO;
        } catch (EmisionDeValoresDeMultas.SinDeudaQueFormalizar nadaQueFormalizar) {
            corridas.marcarSinDeuda(itemId);
            return Resultado.SIN_DEUDA;
        }
    }

    // ------------------------------------------------------------------

    /**
     * Por qué esta papeleta no puede formalizarse todavía, o {@code null} si sí puede.
     *
     * <p>Devuelve el texto que va a {@code papeleta_masivo_item.motivo}, y por eso dice <b>qué</b>
     * falta y no solo que falta algo.
     */
    private @Nullable String impedimentoDe(Papeleta papeleta, CorridaDeValores corrida) {
        TipoDeResolucionDeGerencia tipo = corrida.resolucionQueOrdenaLaCobranza();
        LocalDate fechaCriterio = corrida.fechaCriterio();

        Optional<ResolucionDeGerencia> ordena =
                resoluciones.dePapeleta(papeleta.identificador(), tipo);
        if (ordena.isEmpty()) {
            return "Sin "
                    + tipo.titulo().toLowerCase(Locale.ROOT)
                    + ": no hay acto que ordene la"
                    + " cobranza de esta multa";
        }

        ResolucionDeGerencia resolucion = ordena.get();
        Optional<NotificacionDeResolucion> diligencia =
                diligencias.queSurtioEfecto(resolucion.identificador());
        if (diligencia.isEmpty()) {
            return "La resolucion "
                    + resolucion.numero()
                    + " no consta notificada: sin diligencia"
                    + " que surta efecto el plazo no empieza a correr";
        }

        LocalDate exigibleDesde =
                Objects.requireNonNull(
                        diligencia.get().exigibleDesde(),
                        "Una diligencia que surtio efecto siempre trae su fecha de exigibilidad");
        if (fechaCriterio.isBefore(exigibleDesde)) {
            return "El plazo de la resolucion "
                    + resolucion.numero()
                    + " vence el "
                    + exigibleDesde
                    + "; al "
                    + fechaCriterio
                    + " todavia corre";
        }
        return null;
    }
}
