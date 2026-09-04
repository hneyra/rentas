package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Ciiu;
import kamayuk.rentas.licencias.dominio.CiiuRepository;
import kamayuk.rentas.licencias.dominio.CriterioDeCiiu;
import kamayuk.rentas.licencias.dominio.RiesgoItse;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El catalogo CIIU de giros: consultarlo y extenderlo (#44, RF-112).
 *
 * <h2>Extensible por el usuario, que es literalmente el requisito</h2>
 *
 * <p>RF-112 dice «mantener el catalogo CIIU, extensible por el usuario». Lo que se publica aqui es
 * el alta —la extension—, y {@code ciiu.extendido} deja constancia de que ese giro lo agrego la
 * municipalidad y no venia en la clasificacion publicada. Esa distincion importa el dia que la CIIU
 * oficial se cargue: sin ella, la carga no sabria que filas puede tocar.
 *
 * <p><b>La carga inicial de la CIIU rev. 4 no esta aqui</b>, y es decision. Ver {@link Ciiu}: es
 * una transcripcion normativa sin fuente verificada en este repositorio, y el precedente del
 * proyecto para los datos normativos es no inventarlos. El catalogo nace vacio y la municipalidad
 * lo puebla; una licencia con un giro inventado autoriza una actividad que nadie clasifico.
 */
@Service
public class MantenerCatalogoCiiu {

    private final CiiuRepository catalogo;
    private final Auditoria auditoria;
    private final Clock reloj;

    public MantenerCatalogoCiiu(CiiuRepository catalogo, Auditoria auditoria, Clock reloj) {
        this.catalogo = catalogo;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /** El catalogo, paginado. */
    @Transactional(readOnly = true)
    public Pagina<Ciiu> listar(CriterioDeCiiu criterio, Paginacion paginacion) {
        return catalogo.buscar(criterio, paginacion);
    }

    /**
     * Da de alta un giro.
     *
     * <p>Nace <b>activo</b>: el {@code activo} de la peticion se ignora, igual que hace {@code
     * RegistrarSector} con el suyo. Darlo de alta ya retirado seria un alta y una baja en un solo
     * acto, con la auditoria diciendo solo ALTA.
     *
     * <p>Nace tambien <b>extendido</b>: todo lo que se registra por esta via lo agrego la
     * municipalidad. La carga de la clasificacion oficial, cuando exista, sera otro camino y
     * marcara sus filas como no extendidas.
     *
     * @param observacion por que se agrega (regla 10, RNF-052)
     * @throws CiiuRepository.CodigoDuplicado si el codigo ya esta en el catalogo
     */
    @Transactional
    public Ciiu registrar(Alta alta, Observacion observacion) {
        Objects.requireNonNull(alta, "No se registra sin datos");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        Ciiu guardado =
                catalogo.registrar(
                        new Ciiu(
                                null,
                                alta.codigo(),
                                alta.descripcion(),
                                alta.seccion(),
                                alta.riesgoItse(),
                                alta.zonificacionCompatible(),
                                alta.requiereSectorial(),
                                true,
                                true,
                                reloj.instant(),
                                null,
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "ciiu",
                                String.valueOf(guardado.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardado)));
        return guardado;
    }

    private static String descripcion(Ciiu giro) {
        return "{\"codigo\":\""
                + giro.codigo()
                + "\",\"seccion\":"
                + (giro.seccion() == null ? "null" : "\"" + giro.seccion() + "\"")
                + ",\"riesgoItse\":"
                + (giro.riesgoItse() == null ? "null" : "\"" + giro.riesgoItse() + "\"")
                + ",\"requiereSectorial\":"
                + giro.requiereSectorial()
                + ",\"extendido\":"
                + giro.extendido()
                + "}";
    }

    /**
     * Lo que se pide para agregar un giro.
     *
     * @param codigo el codigo CIIU
     * @param descripcion la actividad
     * @param seccion la letra de seccion; opcional
     * @param riesgoItse el nivel de riesgo; opcional mientras la municipalidad no lo clasifique
     * @param zonificacionCompatible las zonas donde el giro cabe; opcional
     * @param requiereSectorial si necesita autorizacion sectorial
     */
    public record Alta(
            String codigo,
            String descripcion,
            @Nullable String seccion,
            @Nullable RiesgoItse riesgoItse,
            @Nullable String zonificacionCompatible,
            boolean requiereSectorial) {

        public Alta {
            Objects.requireNonNull(codigo, "El giro necesita su codigo CIIU");
            Objects.requireNonNull(descripcion, "El giro necesita su descripcion");
        }
    }
}
