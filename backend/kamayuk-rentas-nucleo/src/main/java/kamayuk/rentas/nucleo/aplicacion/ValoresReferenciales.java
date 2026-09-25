package kamayuk.rentas.nucleo.aplicacion;

import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.MarcaYModelo;
import kamayuk.rentas.nucleo.dominio.ValorReferencial;
import kamayuk.rentas.nucleo.dominio.ValorReferencialRepository;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
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
     *
     * <p><b>Se acota por la categoria del vehiculo</b> (#360), que es parte de la identidad de la
     * fila del anexo. Y como el filtro es por igualdad, una categoria que el anexo no conoce —el
     * padron escrito con otro vocabulario, «M1» del reglamento de vehiculos en vez de «A1» del
     * anexo— no casaria con ninguna fila y saldria como «el cuadro no trae el vehiculo». No lo es:
     * lo que falta corregir esta en el padron, no en la tabla del MEF, y por eso se para con {@link
     * CategoriaFueraDelCuadro} nombrando las categorias que si publica.
     *
     * <p>El vocabulario se pregunta solo cuando no hubo fila: si la hubo, la categoria es del anexo
     * por construccion, y el calculo por contribuyente no paga una consulta mas por vehiculo.
     *
     * @throws ValorReferencialRepository.ValorReferencialAmbiguo si el vehiculo no tiene categoria
     *     y el anexo publica su modelo en varias con cifras distintas
     * @throws CategoriaFueraDelCuadro si la categoria del vehiculo no es una de las del anexo
     */
    @Transactional(readOnly = true)
    public Optional<ValorReferencial> de(Vehiculo vehiculo, Ejercicio ejercicio) {
        IdentificadorDeConjunto conjunto = parametros.conjuntoVigenteEn(ejercicio);
        String categoria = vehiculo.categoria();
        Optional<ValorReferencial> valor =
                repositorio.buscar(
                        conjunto,
                        vehiculo.marca(),
                        vehiculo.modelo(),
                        vehiculo.anioFabricacion().valor(),
                        categoria);
        if (valor.isEmpty() && categoria != null) {
            List<String> delCuadro = repositorio.categorias(conjunto);
            if (!delCuadro.contains(categoria)) {
                throw new CategoriaFueraDelCuadro(vehiculo, categoria, ejercicio, delCuadro);
            }
        }
        return valor;
    }

    /** Marcas y modelos del ejercicio: el catalogo que la pantalla ofrece para elegir. */
    @Transactional(readOnly = true)
    public List<MarcaYModelo> catalogoDe(Ejercicio ejercicio) {
        return repositorio.catalogo(parametros.conjuntoVigenteEn(ejercicio));
    }

    /**
     * La categoria del vehiculo en el padron no es una de las que publica el cuadro del ejercicio
     * (#360).
     *
     * <p>Es su propio rechazo y no {@code SinValorReferencial}: aquel dice que la tabla del MEF no
     * trae el vehiculo, y este que el padron lo describe con otro vocabulario. Confundirlos manda a
     * buscar en el sitio equivocado.
     */
    public static final class CategoriaFueraDelCuadro extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        CategoriaFueraDelCuadro(
                Vehiculo vehiculo, String categoria, Ejercicio ejercicio, List<String> delCuadro) {
            super(
                    "La categoria '"
                            + categoria
                            + "' del vehiculo "
                            + vehiculo.placa()
                            + " no es una de las que publica el cuadro de valores referenciales"
                            + " del ejercicio "
                            + ejercicio
                            + " ("
                            + String.join(", ", delCuadro)
                            + "): corrija la categoria en el padron. Sin ella no se puede elegir"
                            + " la fila del anexo");
        }
    }
}
