package kamayuk.rentas.fiscalizacion.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.fiscalizacion.aplicacion.CerrarProgramaFiscalizacion;
import kamayuk.rentas.fiscalizacion.dominio.ProgramaFiscalizacion;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cierre de un programa de fiscalización: {@code POST /api/v1/fiscalizacion/programas/{id}/cierre}
 * (#341).
 *
 * <p>Es la <b>única</b> escritura que mueve el estado de un programa, y hasta #341 no existía: el
 * programa nacía {@code ABIERTO} y ahí se quedaba, de modo que la exclusión de #481 retenía para
 * siempre a todo predio que hubiera sorteado. Por qué es un acto y no una derivación está en {@link
 * CerrarProgramaFiscalizacion}.
 *
 * <p>Controlador propio y no un método más de {@link ProgramasController}, por lo mismo que {@link
 * MuestraController} y {@code AnulacionDePapeletaController}: cuelga del programa pero es otro
 * acto, con su cuerpo y sus respuestas.
 *
 * <p>{@code MODIFICACION} y no {@code REGISTRO}: no nace ninguna fila, se mueve una columna de una
 * que ya existe. Sobre {@code fisc_programa}, que es la pantalla que administra los programas.
 *
 * <p>El cuerpo es la observación (regla 10) y la fecha del acto, que es la del día en que se cierra
 * y no la de su registro (regla 9). {@code 201} y no {@code 200} por lo mismo que los demás actos
 * de este contrato: lo que se crea es el <b>acto</b>, que queda en {@code auditoria} con su antes y
 * su después.
 */
@RestController
@RequestMapping(Api.RAIZ + "/fiscalizacion/programas")
@RequiereAcceso(acceso = "fisc_programa", privilegio = Privilegio.MODIFICACION)
public class CierreDelProgramaController {

    private final CerrarProgramaFiscalizacion cierre;

    public CierreDelProgramaController(CerrarProgramaFiscalizacion cierre) {
        this.cierre = cierre;
    }

    @PostMapping("/{id}/cierre")
    @ResponseStatus(HttpStatus.CREATED)
    public ProgramaResource cerrar(@PathVariable long id, @RequestBody PeticionDeCierre peticion) {
        Observacion observacion = observacionDe(peticion.observacion());
        LocalDate fecha = fechaDe(peticion.fecha());

        try {
            return ProgramaResource.de(cierre.cerrar(id, fecha, observacion));
        } catch (CerrarProgramaFiscalizacion.ProgramaInexistente noEsta) {
            throw new ProblemaDeNegocio(CodigoDeError.NO_ENCONTRADO, mensajeDe(noEsta));
        } catch (ProgramaFiscalizacion.TransicionIlegal yaCerrado) {
            // 409 y no 422: la peticion esta bien escrita, lo que no admite el acto es el estado
            // en que esta el programa. La interfaz distingue las dos para saber si reintentar
            // sirve.
            throw new ProblemaDeNegocio(CodigoDeError.CONFLICTO, mensajeDe(yaCerrado));
        }
    }

    // ------------------------------------------------------------------

    /**
     * El cuerpo del cierre: por qué se cierra (regla 10) y el día del acto (regla 9).
     *
     * <p>No lleva nada más. Que se cierra lo dice la ruta, y lo que el programa declaró —código,
     * ejercicio, sector, criterio— no se toca: desde {@code V30} el privilegio ni siquiera lo
     * permite.
     */
    public record PeticionDeCierre(@Nullable String observacion, @Nullable String fecha) {}

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
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Falta el campo 'fecha': es el dia en que se cierra el programa, y no el dia en"
                            + " que alguien lo teclea (regla 9)");
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
}
