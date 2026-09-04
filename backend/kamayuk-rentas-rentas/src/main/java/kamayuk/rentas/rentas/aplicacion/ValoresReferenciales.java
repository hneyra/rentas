package kamayuk.rentas.rentas.aplicacion;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.rentas.dominio.MarcaYModelo;
import kamayuk.rentas.rentas.dominio.ValorReferencial;
import kamayuk.rentas.rentas.dominio.ValorReferencialRepository;
import kamayuk.rentas.rentas.dominio.Vehiculo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lee la tabla de valores referenciales del <b>conjunto sellado</b> que rige el ejercicio.
 *
 * <p>Los dos pasos —traducir el ejercicio a un conjunto, y leer el conjunto— estan separados a
 * proposito. El primero lo hace {@code parametros}, que es quien sabe que significa «sellado» y
 * cual es la version vigente; el segundo se hace siempre por identificador. Si este servicio
 * consultara la tabla por ejercicio, un ejercicio con dos versiones selladas devolveria la vigente
 * hoy en vez de la que uso la determinacion, y el recalculo daria otra cifra sin ningun error de
 * por medio (ARQ-09 §3).
 *
 * <p><b>Aqui no se calcula el impuesto.</b> Se devuelve el valor referencial, que es un dato; los
 * tramos, la alicuota y el minimo siguen bloqueados por D-02.
 */
@Service
public class ValoresReferenciales {

    private final ValorReferencialRepository repositorio;
    private final LectorDeParametros parametros;

    public ValoresReferenciales(
            ValorReferencialRepository repositorio, LectorDeParametros parametros) {
        this.repositorio = repositorio;
        this.parametros = parametros;
    }

    /**
     * El valor referencial que le corresponde al vehiculo en ese ejercicio, si la tabla lo trae.
     */
    @Transactional(readOnly = true)
    public Optional<ValorReferencial> de(Vehiculo vehiculo, Ejercicio ejercicio) {
        IdentificadorDeConjunto conjunto = parametros.conjuntoVigenteEn(ejercicio);
        return repositorio.buscar(
                conjunto, vehiculo.marca(), vehiculo.modelo(), vehiculo.anioFabricacion().valor());
    }

    /** Marcas y modelos del ejercicio: el catalogo que la pantalla ofrece para elegir. */
    @Transactional(readOnly = true)
    public List<MarcaYModelo> catalogoDe(Ejercicio ejercicio) {
        return repositorio.catalogo(parametros.conjuntoVigenteEn(ejercicio));
    }
}
