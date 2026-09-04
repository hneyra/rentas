package kamayuk.rentas.carga;

import kamayuk.rentas.documentos.RegimenDeLaInstalacion;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La guarda de las cargas que siembran datos <b>inventados</b>: si la municipalidad en curso no
 * esta marcada como de demostracion, la carga no corre.
 *
 * <h2>Por que hace falta una guarda y no basta con no ejecutarlo</h2>
 *
 * <p>Un cargador de demostracion se dispara con una propiedad, y las propiedades se copian. El
 * guion que siembra la marcha blanca y el que carga el catalogo real de una municipalidad son el
 * mismo formato de Job con distintas variables de entorno; equivocarse de {@code
 * --municipalidad-id} es un error de un digito. Sin esta comprobacion, ese digito mete ocho
 * contribuyentes ficticios y diez predios inventados en el padron de una municipalidad que ya
 * opera, con su codigo de contribuyente ocupado y su codigo de referencia catastral consumido; y
 * como aqui no se borra nada (RNF-051), deshacerlo es dar de baja fila a fila.
 *
 * <p>La marca no es configuracion: es {@code municipalidad.es_demostracion}, la misma fila que
 * decide si un documento sale marcado (#122, ADR-0007). Un cargador no puede afirmar que la
 * instalacion es de prueba; tiene que preguntarlo donde ya esta escrito.
 *
 * <h2>Por que la pregunta va dentro de una transaccion</h2>
 *
 * <p>{@link RegimenDeLaInstalacion} resuelve la municipalidad con {@code
 * current_setting('app.municipalidad_id')}, el parametro de sesion que fija el {@code SET LOCAL} de
 * la transaccion. Preguntado fuera de una, no responde por la municipalidad equivocada: falla. En
 * el perfil {@code batch} no hay filtro HTTP que abra nada, asi que la transaccion la tiene que
 * abrir esta clase —de solo lectura— y por eso es un {@code @Service} y no un metodo del propio
 * cargador, donde la llamada a si mismo no pasaria por el proxy.
 */
@Service
public class SoloEnDemostracion {

    private final RegimenDeLaInstalacion regimen;

    public SoloEnDemostracion(RegimenDeLaInstalacion regimen) {
        this.regimen = regimen;
    }

    /**
     * Deja seguir solo si la municipalidad en curso es de demostracion.
     *
     * @param queSeIbaASembrar lo que la carga iba a escribir, para que el mensaje diga que se
     *     detuvo y no solo que algo fallo
     * @throws NoEsInstalacionDeDemostracion si la municipalidad en curso no lo es
     */
    @Transactional(readOnly = true)
    public void exigirlo(String queSeIbaASembrar) {
        if (!regimen.esDeDemostracion()) {
            throw new NoEsInstalacionDeDemostracion(queSeIbaASembrar);
        }
    }

    /** Se pidio sembrar datos ficticios contra una municipalidad que no es de demostracion. */
    public static final class NoEsInstalacionDeDemostracion extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        NoEsInstalacionDeDemostracion(String queSeIbaASembrar) {
            super(
                    "La municipalidad en curso no esta marcada como de demostracion"
                            + " (municipalidad.es_demostracion), asi que no se siembra "
                            + queSeIbaASembrar
                            + ": son datos inventados, y aqui no se borra nada (RNF-051)");
        }
    }
}
