package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.RegistrarActaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.Hallazgo;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Acta de inspección predial: {@code POST /api/v1/fiscalizacion/predial/actas} (RF-051, #45).
 *
 * <p>Trabaja sobre una copia: no toca ninguna fila de {@code catastro} (AC de #45). El cuerpo es
 * una <b>lista blanca</b>, mismo patrón que {@code TransferenciaPredioController}.
 *
 * <h2>El {@code GET} de actas existe desde #599, y lo que le faltaba era una columna</h2>
 *
 * <p>La lectura la sirve {@link ActasController} en {@code GET /fiscalizacion/actas}, y hasta #599
 * no existía: un acta se registraba y no se podía volver a leer —el único sitio donde asomaba era
 * {@code MuestraResource.visitado}, que dice <b>si</b> un predio de la muestra ya tiene acta y nada
 * más—.
 *
 * <p>Y no se publicó antes a propósito. #546 midió que <b>ese {@code GET} no era lo que le faltaba
 * a la pantalla</b>: el destino {@code actas} del diseño es el acta en cuatro pasos con modo campo,
 * que dibuja 23 controles y siete filas de contraste declarado/verificado, y el cuerpo de este
 * {@code POST} tenía <b>nueve</b> campos. Un listado habría publicado esa misma foto incompleta: lo
 * que faltaba no era por dónde leer, era <b>dónde guardar</b>.
 *
 * <p>Lo que #599 añadió es la columna {@code uso_hallado} (V76), o sea el <b>sexto</b> de los siete
 * contrastes y el segundo de los dos hallazgos que la fiscalización predial persigue —el otro es el
 * área—. Con ella {@link kamayuk.rentas.fiscalizacion.dominio.Hallazgo} gana su quinto valor y el
 * cuerpo pasa a diez campos.
 *
 * <p>Las otras seis filas de contraste siguen fuera, y por el mismo criterio: son estructura del
 * predio —frente, fondo, número de pisos, material, estado de conservación— y ninguna existe
 * todavía en ninguna tabla del acta; declararlas en el cuerpo sin tabla dejaría la petición
 * aceptando datos que se pierden al guardar, que es peor que no aceptarlos.
 *
 * <p><b>Y con la misma lectura se llena el embudo del programa</b> (#546, AC 10). Sus cuatro etapas
 * son «Programados», «Inspeccionados», «Con liquidación» y «Notificadas»; la primera la da el total
 * de {@code GET /programas/{id}/muestra} y las dos últimas los dos totales de {@code GET
 * /fiscalizacion/resultados}. La que no tenía de dónde salir era «Inspeccionados», y sale del
 * {@code totalElementos} de {@code GET /fiscalizacion/actas?programa=‹id›} —no de una suma—.
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion/predial/actas")
@RequiereAcceso(acceso = "fisc_predial", privilegio = Privilegio.REGISTRO)
public class ActaPredialController {

    private final RegistrarActaFiscalizacion actas;

    public ActaPredialController(RegistrarActaFiscalizacion actas) {
        this.actas = actas;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ActaFiscalizacionResource registrar(@RequestBody PeticionDeActaPredial peticion) {
        Observacion observacion = observacionDe(peticion.observacion());

        try {
            return ActaFiscalizacionResource.de(
                    actas.registrarPredial(
                            exigirId(peticion.programaId(), "programaId"),
                            exigirId(peticion.contribuyenteId(), "contribuyenteId"),
                            exigirId(peticion.predioId(), "predioId"),
                            fechaDe(peticion.fechaVisita()),
                            exigir(peticion.fiscalizador(), "fiscalizador"),
                            hallazgoDe(peticion.hallazgo()),
                            areaDe(peticion.areaHallada()),
                            vacioAnulo(peticion.usoHallado()),
                            peticion.detalle(),
                            observacion));
        } catch (RegistrarActaFiscalizacion.ProgramaInexistente
                | RegistrarActaFiscalizacion.ProgramaDeOtroTipo problema) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(problema));
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    // ------------------------------------------------------------------

    private static @Nullable Hallazgo hallazgoDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return Hallazgo.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Hallazgo desconocido: '" + texto + "'");
        }
    }

    private static @Nullable String vacioAnulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }

    private static @Nullable BigDecimal areaDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(texto.strip());
        } catch (NumberFormatException noEsNumero) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El area hallada no es un numero valido");
        }
    }

    private static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda modificacion exige la observacion del usuario: sin ella no se guarda");
        }
        try {
            return Observacion.de(texto);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    private static LocalDate fechaDe(@Nullable String texto) {
        try {
            return LocalDate.parse(exigir(texto, "fechaVisita").strip());
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "La fecha va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    private static long exigirId(@Nullable Long valor, String campo) {
        if (valor == null || valor < 1) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor;
    }

    private static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    private static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }

    /** El cuerpo de un acta predial. <b>Lista blanca</b>: lo que no está aquí no entra. */
    public record PeticionDeActaPredial(
            @Nullable String observacion,
            @Nullable Long programaId,
            @Nullable Long contribuyenteId,
            @Nullable Long predioId,
            @Nullable String fechaVisita,
            @Nullable String fiscalizador,
            @Nullable String hallazgo,
            @Nullable String areaHallada,
            @Nullable String usoHallado,
            @Nullable String detalle) {}
}
