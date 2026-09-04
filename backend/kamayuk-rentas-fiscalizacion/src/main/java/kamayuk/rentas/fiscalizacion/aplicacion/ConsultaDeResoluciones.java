package kamayuk.rentas.fiscalizacion.aplicacion;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.fiscalizacion.dominio.LineaDeLiquidacion;
import kamayuk.rentas.fiscalizacion.dominio.Liquidacion;
import kamayuk.rentas.fiscalizacion.dominio.LiquidacionRepository;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacion;
import kamayuk.rentas.fiscalizacion.dominio.ResolucionDeDeterminacionRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La resolucion de determinacion de fiscalizacion por su numero, con lo que la explica (#52,
 * RF-057; pantalla {@code resolucion_determinacion_fisc}).
 *
 * <p>Devuelve la resolucion <b>con la liquidacion y su detalle</b>, no la fila desnuda: el cuadro
 * que la pantalla pinta —Ejercicio, Determinado, Declarado, Diferencia— es el contraste de la
 * liquidacion, y pedirlo en dos llamadas obligaria a la pantalla a saber que la resolucion tiene
 * una liquidacion detras.
 *
 * <p>{@code @Transactional(readOnly = true)}: sin transaccion no hay {@code SET LOCAL}, y sin el la
 * politica RLS falla en vez de devolver filas. Es lo que la marcha blanca de #290 destapo con
 * {@code GET /catastro/vias}.
 */
@Service
public class ConsultaDeResoluciones {

    private final ResolucionDeDeterminacionRepository resoluciones;
    private final LiquidacionRepository liquidaciones;
    private final DirectorioDeContribuyentes contribuyentes;
    private final EmitirDocumento documentos;

    public ConsultaDeResoluciones(
            ResolucionDeDeterminacionRepository resoluciones,
            LiquidacionRepository liquidaciones,
            DirectorioDeContribuyentes contribuyentes,
            EmitirDocumento documentos) {
        this.resoluciones = resoluciones;
        this.liquidaciones = liquidaciones;
        this.contribuyentes = contribuyentes;
        this.documentos = documentos;
    }

    /** Una resolucion por su numero, con la liquidacion que la sustenta. */
    @Transactional(readOnly = true)
    public Optional<ResolucionConsultada> porNumero(String numero) {
        return resoluciones.porNumero(numero).map(this::componer);
    }

    /**
     * La misma resolucion como documento descargable (#593, RF-057, RF-132).
     *
     * <h2>Se sirve el papel guardado, no uno recompuesto</h2>
     *
     * <p>La resolucion <b>ya se emitio</b>: {@code TransferirARentas} la numero y guardo su modelo
     * y su resumen en el registro de documentos, en la misma transaccion que versiono la ficha y
     * asento los cargos. Lo que se entrega aqui son esos datos guardados, dibujados en el formato
     * que se pida.
     *
     * <p>Recomponer el modelo con datos vivos habria sido mas facil y estaria mal: el domicilio de
     * notificacion cambia, la ficha se versiona otra vez y el padron sigue moviendose, de modo que
     * el papel que se descarga en 2030 no seria el valor que se notifico en 2026 —y es ese valor,
     * no otro, el que arranca el plazo del art. 137 para reclamar—.
     *
     * <p><b>No registra nada</b> (#593, AC 2): ver {@link EmitirDocumento#copia}, que es donde esa
     * decision esta razonada y donde vive la comprobacion de que el papel sale igual que cuando se
     * emitio.
     *
     * @return el documento, o vacio si no hay ninguna resolucion con ese numero
     */
    @Transactional(readOnly = true)
    public Optional<CopiaDeLaResolucion> copiaDe(String numero, FormatoDeDocumento formato) {
        return resoluciones.porNumero(numero).map(resolucion -> dibujar(resolucion, formato));
    }

    private CopiaDeLaResolucion dibujar(
            ResolucionDeDeterminacion resolucion, FormatoDeDocumento formato) {
        // El ejercicio con que se numero el documento sale de la FECHA DE LA RESOLUCION, que es la
        // misma con la que la transferencia lo emitio (`Ejercicio.de(peticion.fecha())`). Con el
        // reloj se resolveria el ejercicio de hoy, y una resolucion del ano pasado dejaria de
        // encontrar su propio papel — el defecto de #24 y #366, aqui aplicado a la numeracion.
        Ejercicio ejercicio = Ejercicio.de(resolucion.fecha());
        EmitirDocumento.Emision emision =
                documentos
                        .copia(
                                TransferirARentas.TIPO_DE_DOCUMENTO,
                                ejercicio,
                                resolucion.numero(),
                                formato)
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "La resolucion "
                                                        + resolucion.numero()
                                                        + " no encuentra su documento "
                                                        + TransferirARentas.TIPO_DE_DOCUMENTO
                                                        + " del ejercicio "
                                                        + ejercicio.valor()
                                                        + ": la resolucion y su papel se emiten en"
                                                        + " la misma transaccion y la foranea de"
                                                        + " V49 lo sostiene"));
        return new CopiaDeLaResolucion(resolucion.numero(), formato, emision.contenido());
    }

    /** Las transferencias de un contribuyente, de la mas reciente a la primera. */
    @Transactional(readOnly = true)
    public List<ResolucionConsultada> deContribuyente(long contribuyenteId) {
        return resoluciones.deContribuyente(contribuyenteId).stream().map(this::componer).toList();
    }

    private ResolucionConsultada componer(ResolucionDeDeterminacion resolucion) {
        Liquidacion liquidacion =
                liquidaciones
                        .findById(resolucion.liquidacionId())
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "La resolucion "
                                                        + resolucion.numero()
                                                        + " referencia una liquidacion que no"
                                                        + " existe: la foranea de V49 lo impide"));
        return new ResolucionConsultada(
                resolucion,
                liquidacion,
                liquidaciones.lineasDe(liquidacion.identificador()),
                contribuyentes
                        .porIds(java.util.Set.of(resolucion.contribuyenteId()))
                        .get(resolucion.contribuyenteId()));
    }

    /**
     * Una resolucion con todo lo que su pantalla necesita.
     *
     * @param resolucion la fila registrada
     * @param liquidacion el resultado que transfirio
     * @param lineas el contraste, una linea por ejercicio
     * @param contribuyente quien es el obligado; nulo si el padron ya no lo tiene
     */
    public record ResolucionConsultada(
            ResolucionDeDeterminacion resolucion,
            Liquidacion liquidacion,
            List<LineaDeLiquidacion> lineas,
            @Nullable ResumenDeContribuyente contribuyente) {

        public ResolucionConsultada {
            Objects.requireNonNull(resolucion, "La consulta es de una resolucion");
            Objects.requireNonNull(liquidacion, "La resolucion siempre trae su liquidacion");
            lineas = List.copyOf(lineas);
        }
    }

    /**
     * El papel de una resolucion, listo para entregar.
     *
     * <p>Lleva el formato <b>pedido</b> y no el de la emision: quien emitio en PDF puede pedir la
     * misma resolucion en hoja de calculo, y el nombre del archivo tiene que decir lo que el cuerpo
     * trae. Nombrarlo con la extension de la emision daria un {@code .pdf} con un XLS dentro.
     *
     * @param numero el numero de la resolucion, que es el de su documento
     * @param formato en el que se pidio
     * @param contenido los bytes
     */
    public record CopiaDeLaResolucion(String numero, FormatoDeDocumento formato, byte[] contenido) {

        public String nombreDeArchivo() {
            return formato.nombreDeArchivo(numero);
        }
    }
}
