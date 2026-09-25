package kamayuk.rentas.licencias.aplicacion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.licencias.dominio.Ciiu;
import kamayuk.rentas.licencias.dominio.CiiuRepository;
import kamayuk.rentas.licencias.dominio.GiroDeLaLicencia;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los giros de una solicitud de licencia, resueltos contra el catalogo CIIU (#44, #450).
 *
 * <p>Es la lectura de la base que {@link EmitirLicenciaDeFuncionamiento} necesita <b>antes</b> de
 * preguntar a {@code catastro}: la zona compatible que decide es la del giro principal, y esa sale
 * del catalogo. Vive en su propia clase, con su propia transaccion de solo lectura, porque desde
 * #450 la emision no abre ninguna mientras pregunta a los vecinos: el catalogo esta bajo RLS, y sin
 * transaccion no hay {@code SET LOCAL} que la politica pueda evaluar.
 */
@Service
public class GirosDeLaSolicitud {

    private final CiiuRepository catalogo;

    public GirosDeLaSolicitud(CiiuRepository catalogo) {
        this.catalogo = catalogo;
    }

    /**
     * Los giros pedidos, resueltos contra el catalogo, y el principal entero.
     *
     * <p>Se leen <b>todos de una vez</b> y se comprueban aqui, antes de escribir nada: un giro que
     * no existe tiene que producir «ese giro no esta en el catalogo» y no un fallo de clave foranea
     * a mitad de la insercion, que no dice cual de los tres era.
     *
     * @throws EmitirLicenciaDeFuncionamiento.GiroDesconocido si alguno no esta activo
     */
    @Transactional(readOnly = true)
    public Resueltos resolver(EmitirLicenciaDeFuncionamiento.Solicitud solicitud) {
        Set<String> codigos = new LinkedHashSet<>();
        for (String codigo : solicitud.girosCiiu()) {
            codigos.add(codigo.strip().toUpperCase(java.util.Locale.ROOT));
        }
        if (!codigos.contains(solicitud.giroPrincipal())) {
            codigos.add(solicitud.giroPrincipal());
        }

        List<GiroDeLaLicencia> giros = new ArrayList<>(codigos.size());
        Ciiu principal = null;
        for (String codigo : codigos) {
            Ciiu giro =
                    catalogo.porCodigo(codigo)
                            .orElseThrow(
                                    () ->
                                            new EmitirLicenciaDeFuncionamiento.GiroDesconocido(
                                                    codigo));
            if (!giro.activo()) {
                throw new EmitirLicenciaDeFuncionamiento.GiroDesconocido(codigo);
            }
            boolean esElPrincipal = codigo.equals(solicitud.giroPrincipal());
            if (esElPrincipal) {
                principal = giro;
            }
            giros.add(
                    new GiroDeLaLicencia(
                            giro.identificador(),
                            giro.codigo(),
                            giro.descripcion(),
                            esElPrincipal,
                            true));
        }
        return new Resueltos(
                giros,
                Objects.requireNonNull(principal, "El principal siempre esta entre los codigos"));
    }

    /**
     * Los giros de la licencia y el principal con su fila del catalogo.
     *
     * @param giros los que se autorizan, con el principal marcado
     * @param principal el que decide la zona y el riesgo de la ITSE (ver {@code
     *     LicenciaDeFuncionamiento})
     */
    public record Resueltos(List<GiroDeLaLicencia> giros, Ciiu principal) {

        public Resueltos {
            giros = List.copyOf(giros);
            Objects.requireNonNull(principal, "principal");
        }
    }
}
