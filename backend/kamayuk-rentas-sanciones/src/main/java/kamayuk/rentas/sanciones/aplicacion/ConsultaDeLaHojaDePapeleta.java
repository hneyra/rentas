package kamayuk.rentas.sanciones.aplicacion;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccion;
import kamayuk.rentas.sanciones.dominio.CodigoInfraccionRepository;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La hoja informativa de una papeleta de tránsito (#396, RF-068).
 *
 * <p>Es lo que la pantalla {@code transito_papeleta_reporte} llama «hoja informativa que resume la
 * información relevante de una papeleta»: el acta con su desglose, el código del catálogo que la
 * sustenta y a quién se le cobra. Tres registros distintos, compuestos aquí y no en la interfaz,
 * porque componerlos allí serían tres peticiones por hoja y una tercera oportunidad de que la hoja
 * y la pantalla dijeran cosas distintas.
 *
 * <h2>Existe por el {@code @Transactional}, además de por el reparto</h2>
 *
 * <p>Una consulta sin transacción no lleva {@code SET LOCAL}, y sin él la política RLS no puede
 * evaluar {@code current_setting('app.municipalidad_id')}: la consulta <b>falla</b>. Es el defecto
 * que la marcha blanca de seguridad destapó en {@code GET /catastro/vias}.
 *
 * <h2>Una papeleta que no existe no da una hoja vacía</h2>
 *
 * <p>Lanza {@link RegistrarDescargo.PapeletaInexistente}, que el controlador traduce a 404. Una
 * hoja con todos los campos en blanco es indistinguible de una papeleta sin datos, y quien la
 * imprimiera creería tener el acta de algo.
 *
 * <h2>Ninguna cifra se recalcula</h2>
 *
 * <p>Los seis importes son los del acta, congelados al registrar la papeleta, y por eso viajan con
 * la <b>fecha de la infracción</b> y no con la de hoy. Lo que se debe hoy —con sus intereses— es
 * otra cifra, la del libro, y esta hoja no la pinta: ponerla aquí obligaría a decir a qué fecha, y
 * entonces la hoja dejaría de ser reproducible.
 */
@Service
public class ConsultaDeLaHojaDePapeleta {

    private final PapeletaRepository papeletas;
    private final CodigoInfraccionRepository codigos;
    private final DirectorioDeContribuyentes directorio;

    public ConsultaDeLaHojaDePapeleta(
            PapeletaRepository papeletas,
            CodigoInfraccionRepository codigos,
            DirectorioDeContribuyentes directorio) {
        this.papeletas = papeletas;
        this.codigos = codigos;
        this.directorio = directorio;
    }

    /**
     * La hoja de la papeleta de tránsito con ese número.
     *
     * @param numero el número impreso en el acta
     * @param emitidaEl el día en que se emite la hoja; con él se resuelve el domicilio (regla 9)
     * @throws RegistrarDescargo.PapeletaInexistente si no hay ninguna con ese número
     */
    @Transactional(readOnly = true)
    public Hoja de(String numero, LocalDate emitidaEl) {
        Objects.requireNonNull(numero, "La hoja necesita el numero de la papeleta");
        Objects.requireNonNull(emitidaEl, "Toda cifra indica su fecha (RNF-075, regla 9)");

        Papeleta papeleta =
                papeletas
                        .porNumero(Familia.TRANSITO, numero)
                        .orElseThrow(
                                () ->
                                        new RegistrarDescargo.PapeletaInexistente(
                                                Familia.TRANSITO, numero));

        CodigoInfraccion codigo = codigos.findById(papeleta.codigoInfraccionId()).orElse(null);
        ResumenDeContribuyente obligado =
                directorio
                        .porIds(java.util.Set.of(papeleta.obligadoId()))
                        .get(papeleta.obligadoId());
        String domicilio =
                directorio.domicilioFiscalDe(papeleta.obligadoId(), emitidaEl).orElse(null);

        return new Hoja(papeleta, codigo, obligado, domicilio, emitidaEl);
    }

    /**
     * La hoja compuesta.
     *
     * @param papeleta el acta, con su desglose congelado
     * @param codigo la versión del catálogo que la sustenta; nula si el código ya no existe —el
     *     catálogo se versiona y una fila se puede haber perdido en una migración, y la hoja lo
     *     dice con «—» en vez de fallar
     * @param obligado a quién se le cobra; nulo si el contribuyente se dio de baja
     * @param domicilioDelObligado su domicilio fiscal vigente al día de la emisión
     * @param emitidaEl el día en que se emite
     */
    public record Hoja(
            Papeleta papeleta,
            @Nullable CodigoInfraccion codigo,
            @Nullable ResumenDeContribuyente obligado,
            @Nullable String domicilioDelObligado,
            LocalDate emitidaEl) {

        /** La descripción de la infracción, o vacío si el código ya no está en el catálogo. */
        public Optional<String> descripcionDeLaInfraccion() {
            return codigo == null ? Optional.empty() : Optional.of(codigo.descripcion());
        }
    }
}
