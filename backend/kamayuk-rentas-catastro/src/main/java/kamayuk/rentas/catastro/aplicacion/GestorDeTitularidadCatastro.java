package kamayuk.rentas.catastro.aplicacion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.catastro.CuotaDeTitularidad;
import kamayuk.rentas.catastro.GestorDeTitularidad;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.CondicionDeTitularidad;
import kamayuk.rentas.catastro.dominio.Titularidad;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementacion de {@link GestorDeTitularidad}: reutiliza {@link RegistrarPredio#transferir} para
 * el cierre y la apertura, y le agrega lo que un contexto externo no puede resolver por su cuenta
 * —el remanente de una transferencia parcial, y la condicion de la titularidad nueva—.
 */
@Service
public class GestorDeTitularidadCatastro implements GestorDeTitularidad {

    private final CatastroRepository repositorio;
    private final RegistrarPredio registrarPredio;

    public GestorDeTitularidadCatastro(
            CatastroRepository repositorio, RegistrarPredio registrarPredio) {
        this.repositorio = repositorio;
        this.registrarPredio = registrarPredio;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CuotaDeTitularidad> vigenteDe(
            long predioId, long contribuyenteId, LocalDate fecha) {
        return repositorio.titularesDe(predioId, fecha).stream()
                .filter(titular -> titular.contribuyenteId() == contribuyenteId)
                .findFirst()
                .map(GestorDeTitularidadCatastro::cuotaDe);
    }

    @Override
    @Transactional
    public CuotaDeTitularidad transferir(
            long titularidadAnteriorId,
            long adquirienteId,
            Porcentaje porcentajeTransferido,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion) {

        Titularidad anterior =
                repositorio
                        .titularidad(titularidadAnteriorId)
                        .orElseThrow(() -> new TitularidadInexistente(titularidadAnteriorId));
        if (!anterior.estaVigente()) {
            throw new IllegalStateException(
                    "La titularidad "
                            + titularidadAnteriorId
                            + " ya no esta vigente: no se puede transferir dos veces la misma"
                            + " cuota");
        }
        if (porcentajeTransferido.valor().compareTo(anterior.porcentaje().valor()) > 0) {
            throw new IllegalArgumentException(
                    "No se puede transferir "
                            + porcentajeTransferido
                            + ": la titularidad "
                            + titularidadAnteriorId
                            + " solo tiene "
                            + anterior.porcentaje());
        }

        Titularidad nuevaDelAdquiriente =
                titularidadNueva(
                        anterior.predioId(),
                        adquirienteId,
                        porcentajeTransferido,
                        fecha,
                        documentoOrigen);
        Titularidad abierta =
                registrarPredio.transferir(anterior, nuevaDelAdquiriente, observacion);

        BigDecimal remanente =
                anterior.porcentaje().valor().subtract(porcentajeTransferido.valor());
        if (remanente.signum() > 0) {
            CondicionDeTitularidad condicionDelRemanente =
                    anterior.condicion() == CondicionDeTitularidad.PROPIETARIO_UNICO
                            ? CondicionDeTitularidad.COPROPIETARIO
                            : anterior.condicion();
            Titularidad residual =
                    Titularidad.parcial(
                            anterior.predioId(),
                            anterior.contribuyenteId(),
                            condicionDelRemanente,
                            new Porcentaje(remanente),
                            fecha,
                            documentoOrigen);
            registrarPredio.registrarTitularidad(residual, observacion);
        }

        return cuotaDe(abierta);
    }

    private static Titularidad titularidadNueva(
            long predioId,
            long contribuyenteId,
            Porcentaje porcentaje,
            LocalDate fecha,
            String documentoOrigen) {
        return porcentaje.esTotal()
                ? Titularidad.unico(predioId, contribuyenteId, fecha, documentoOrigen)
                : Titularidad.parcial(
                        predioId,
                        contribuyenteId,
                        CondicionDeTitularidad.COPROPIETARIO,
                        porcentaje,
                        fecha,
                        documentoOrigen);
    }

    private static CuotaDeTitularidad cuotaDe(Titularidad titularidad) {
        return new CuotaDeTitularidad(
                java.util.Objects.requireNonNull(
                        titularidad.id(), "Una titularidad leida de la base tiene id"),
                titularidad.predioId(),
                titularidad.contribuyenteId(),
                titularidad.porcentaje());
    }

    /** No hay ninguna titularidad con ese identificador, o es de otra municipalidad. */
    public static final class TitularidadInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        TitularidadInexistente(long id) {
            super("No hay ninguna titularidad con identificador " + id + " en esta municipalidad");
        }
    }
}
