package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.AnularActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.aplicacion.ConsultaDeActas;
import kamayuk.rentas.fiscalizacion.aplicacion.LiquidarFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.CriterioDeActas;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ParametrosDePaginacion;
import kamayuk.rentas.web.ProblemaDeNegocio;
import kamayuk.rentas.web.RespuestaPaginada;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Las actas de inspección levantadas: {@code GET /api/v1/fiscalizacion/actas} (#599).
 *
 * <h2>Por qué no existía hasta ahora</h2>
 *
 * <p>Un acta se registraba —{@code POST /fiscalizacion/predial/actas} y {@code POST
 * /fiscalizacion/vehicular}— y no se podía volver a leer. #546 se negó a publicar esta lectura y
 * dejó escrito el motivo: el cuerpo del {@code POST} tenía <b>nueve</b> campos contra los
 * veintitrés controles y las siete filas de contraste declarado/verificado que la pantalla del
 * manual dibuja, así que un listado habría publicado esa misma foto incompleta. Lo que faltaba no
 * era por dónde leer, era <b>dónde guardar</b>, y en concreto el <b>uso hallado</b>: hoy lo anota
 * el acta ({@code acta_fiscalizacion.uso_hallado}, V76) y con él {@link
 * kamayuk.rentas.fiscalizacion.dominio.Hallazgo} tiene su quinto valor.
 *
 * <h2>Una lista para las dos familias, y una razon</h2>
 *
 * <p>El acta predial y la vehicular comparten tabla y ciclo de vida (V4), comparten tipo de dominio
 * y comparten {@link ActaFiscalizacionResource}; publicar dos listados sería mantener dos copias de
 * la misma consulta. Cuál es cuál lo dice cuál de {@code predioId} y {@code vehiculoId} trae valor.
 *
 * <p>De ahí el {@code oTambien}: la lectura la necesitan por igual las dos pantallas que escriben
 * actas, y exigir sólo {@code fisc_predial} dejaría a un perfil de fiscalización vehicular
 * <b>registrando actas que no puede volver a ver</b>. Está censado en {@code
 * AccesosCompartidosTest}, que es lo que impide que la lista crezca sin que el diff lo diga.
 *
 * <h2>Un solo filtro, y el motivo de que no haya más</h2>
 *
 * <p>{@code programa}, y está porque lo pide el <b>embudo del programa</b> (#546, AC 10): sus
 * cuatro etapas son «Programados», «Inspeccionados», «Con liquidación» y «Notificadas», y la única
 * que no tenía de dónde salir era la segunda —cuántas actas tiene el programa—. Se llena con el
 * {@code totalElementos} de esta operación acotada al programa, <b>no con una suma</b>: {@code
 * visitado} viaja fila a fila en la muestra y sumarlo en la interfaz recompondría una cifra
 * (RNF-083) sobre la página que se haya pedido.
 *
 * <p>Las dos pantallas del acta no dibujan <b>ningún</b> filtro —su catálogo no declara ni filtros
 * ni tabla—, así que no hay ninguno más que derivar del prototipo. Publicar el predio, el hallazgo
 * o el estado sería inventar promesas que ninguna pantalla hace, que es lo que #431, #432 y #544
 * tuvieron que retirar después.
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion/actas")
@RequiereAcceso(
        acceso = "fisc_predial",
        oTambien = "fisc_vehicular",
        privilegio = Privilegio.LECTURA)
public class ActasController {

    /** El orden por omisión: la fecha de la visita, que es como se recorre una jornada de campo. */
    private static final String ORDEN_POR_OMISION = "fechaVisita";

    private final ConsultaDeActas consulta;
    private final AnularActaFiscalizacion anulacion;

    public ActasController(ConsultaDeActas consulta, AnularActaFiscalizacion anulacion) {
        this.consulta = consulta;
        this.anulacion = anulacion;
    }

    @GetMapping
    public RespuestaPaginada<ActaFiscalizacionResource> actas(
            @RequestParam(required = false) @Nullable String programa,
            ParametrosDePaginacion paginacion) {

        return RespuestaPaginada.de(
                consulta.buscar(
                        new CriterioDeActas(programaOpcional(programa)),
                        paginacion.aPaginacion(ORDEN_POR_OMISION)),
                ActaFiscalizacionResource::de);
    }

    /**
     * Deja sin efecto una visita: {@code POST /fiscalizacion/actas/{id}/anulacion} (#214).
     *
     * <p>Es la <b>única</b> escritura que mueve el estado de un acta, y hasta #214 no existía: los
     * cinco valores de {@code EstadoDeActa} eran uno alcanzable y cuatro que nadie escribía, de
     * modo que las tres consultas que descartan lo anulado no descartaban nada.
     *
     * <p>{@code MODIFICACION} y no {@code REGISTRO}: no nace ninguna fila, se mueve una columna de
     * una que ya existe. Y lleva el mismo {@code oTambien} que la lectura de arriba, por lo mismo:
     * un perfil de fiscalización vehicular que puede levantar un acta tiene que poder anularla.
     *
     * <p>El cuerpo es la observación (regla 10) y la fecha del acto, que es la del día en que se
     * anula y no la de su registro (regla 9). La fecha viaja porque la anulación puede registrarse
     * después del acto —lo mismo que {@code fecha_anulacion} resuelve en {@code pago_recibido}
     * (V10)—, y sin ella el asiento diría el día en que alguien tecleó.
     *
     * <p>{@code 201} y no {@code 200} por lo mismo que los actos de {@code
     * DeclaracionJuradaController}: lo que se crea es el <b>acto</b>, que queda en {@code
     * auditoria} con su antes y su después.
     */
    @PostMapping("/{id}/anulacion")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiereAcceso(
            acceso = "fisc_predial",
            oTambien = "fisc_vehicular",
            privilegio = Privilegio.MODIFICACION)
    public ActaFiscalizacionResource anular(
            @PathVariable long id, @RequestBody PeticionDeAnulacion peticion) {
        try {
            return ActaFiscalizacionResource.de(
                    anulacion.anular(
                            id, fechaDe(peticion.fecha()), Observacion.de(peticion.observacion())));
        } catch (LiquidarFiscalizacion.ActaInexistente noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (AnularActaFiscalizacion.ActaConLiquidacionViva
                | ActaFiscalizacion.TransicionIlegal conflicto) {
            // 409 y no 422: la peticion es correcta, lo que no admite el acto es la situacion en
            // que esta el acta. La interfaz distingue las dos para saber si reintentar sirve.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(conflicto));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    /**
     * El cuerpo de la anulacion: por que se anula (regla 10) y el dia del acto (regla 9).
     *
     * <p>No lleva nada mas. Que se anula lo dice la ruta, y lo que el fiscalizador midio no se toca
     * —desde V19 el privilegio ni siquiera lo permite—.
     */
    public record PeticionDeAnulacion(@Nullable String observacion, @Nullable String fecha) {}

    private static LocalDate fechaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Falta el campo 'fecha': es el dia en que se anula el acta, y no el dia en que"
                            + " alguien lo teclea (regla 9)");
        }
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La fecha va en formato ISO (AAAA-MM-DD): '" + texto + "'");
        }
    }

    private static String mensajeDe(RuntimeException problema) {
        String mensaje = problema.getMessage();
        return mensaje == null ? problema.getClass().getSimpleName() : mensaje;
    }

    private static @Nullable Long programaOpcional(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            long valor = Long.parseLong(texto.strip());
            if (valor < 1) {
                throw new NumberFormatException(texto);
            }
            return valor;
        } catch (NumberFormatException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El programa se identifica por su numero interno: '" + texto + "'");
        }
    }
}
