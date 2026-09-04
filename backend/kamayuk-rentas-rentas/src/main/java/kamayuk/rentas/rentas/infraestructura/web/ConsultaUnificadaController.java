package kamayuk.rentas.rentas.infraestructura.web;

import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.rentas.aplicacion.ConsultaUnificada;
import kamayuk.rentas.web.Api;
import kamayuk.rentas.web.ParametrosDePaginacion;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code consulta_unificada}: {@code GET /api/v1/consultas/unificada?contribuyente={codigo}}
 * (RF-046, #25).
 *
 * <p>La ficha consolidada de un contribuyente: su resumen de saldos, sus deudas pendientes, sus
 * pagos, sus altas y bajas, sus fraccionamientos, sus valores emitidos y sus declaraciones juradas,
 * en una sola respuesta y leidas en una sola transaccion.
 *
 * <p>Vive en {@code rentas} y no en {@code cuentacorriente} —que es el contexto que mas secciones
 * aporta— porque ARQ-01 §4 regla 2 no lo permite: «cuentacorriente no conoce a nadie», y esta
 * pantalla necesita ademas a {@code tesoreria} y a {@code valores}. El razonamiento completo esta
 * en {@link ConsultaUnificada}.
 *
 * <h2>Los filtros que el contrato declara</h2>
 *
 * <p>Dos, y los dos se resuelven:
 *
 * <ul>
 *   <li>{@code contribuyente} es <b>obligatorio</b>: una ficha unificada sin contribuyente no es
 *       nada. Un codigo que no existe en esta municipalidad da <b>404</b>, no una ficha vacia (ver
 *       {@link ConsultaUnificada#de}).
 *   <li>{@code impresion} filtra por tributo —PREDIAL, ARBITRIOS o los dos—. Se resuelve de verdad
 *       en vez de aceptarse y descartarse: ignorarlo <b>ampliaria</b> el resultado, y le enseñaria
 *       arbitrios a quien cree estar viendo solo predial. Un valor que no sea ninguno de los tres
 *       se rechaza con 422 y con el motivo, igual que {@code consulta_valores} rechaza «RECLAMADO».
 * </ul>
 *
 * <h2>Lo que el contrato NO declara, y por que no se inventa</h2>
 *
 * <p>Cada pestaña del prototipo dibuja ademas sus propios filtros —año y cuota en las deudas, rango
 * de fechas y numero de recibo en los pagos, tipo y año en los valores, estado en los
 * fraccionamientos—. Ninguno viaja en la ruta de esta operacion, y ninguno se agrega: cada una de
 * esas pestañas <b>ya tiene su endpoint</b>, con sus filtros implementados —{@code
 * /consultas/deuda}, {@code /consultas/pagos}, {@code /consultas/altas-bajas}, {@code
 * /consultas/valores}, {@code /tesoreria/convenios}—. Repetirlos aqui serian cinco juegos de
 * filtros escritos dos veces, y el segundo es el que se queda atras.
 *
 * <p>{@code ordenarPor} y {@code direccion} se aceptan —el contrato los declara— y <b>no se
 * propagan</b>: seis rejillas de seis tablas distintas no comparten columnas, y un unico campo de
 * orden dejaria cinco fallando. Cada seccion sale con su orden natural, que es el que su pestaña
 * pinta. {@code pagina} y {@code tamano} si se aplican, iguales para todas.
 */
@RestController
@RequestMapping(Api.RAIZ + "/consultas/unificada")
@RequiereAcceso(acceso = "consulta_unificada", privilegio = Privilegio.LECTURA)
public class ConsultaUnificadaController {

    /**
     * Cada seccion se ordena por su propia columna dentro del caso de uso; este valor solo existe
     * porque {@link ParametrosDePaginacion#aPaginacion} exige uno, y nunca llega a un {@code ORDER
     * BY}.
     */
    private static final String ORDEN_POR_OMISION = "ejercicio";

    private final ConsultaUnificada consulta;

    public ConsultaUnificadaController(ConsultaUnificada consulta) {
        this.consulta = consulta;
    }

    /**
     * No lleva {@code @Transactional}: lo lleva {@link ConsultaUnificada#de}, que es donde las seis
     * secciones tienen que compartir <b>una</b> transaccion —y con ella un solo {@code SET LOCAL} y
     * un solo instante de lectura—. Ponerlo tambien aqui no haria daño, pero dejaria en duda cual
     * de los dos es el que importa.
     */
    @GetMapping
    public ConsultaUnificadaResource ficha(
            @RequestParam String contribuyente,
            @RequestParam(required = false) @Nullable String impresion,
            ParametrosDePaginacion parametros) {

        if (contribuyente.isBlank()) {
            throw new IllegalArgumentException(
                    "contribuyente es obligatorio para la consulta unificada");
        }

        ConsultaUnificada.Criterio criterio =
                new ConsultaUnificada.Criterio(
                        contribuyente,
                        // Hoy, del reloj inyectado y no de LocalDate.now() (regla 6). SIN
                        // parametro de fecha de corte: el contrato de esta operacion no lo
                        // declara, y agregarlo por comodidad seria publicar una entrada que
                        // ninguna pantalla sabe mandar. Quien necesite reproducir la deuda de
                        // un dia pasado tiene `GET /consultas/deuda?fechaDeCorte=…`, que si lo
                        // declara; lo que RNF-075 exige aqui —que toda cifra diga a que fecha
                        // esta— lo cumple cada ImporteActualizado de la respuesta.
                        consulta.hoy(),
                        ConsultaUnificada.Alcance.de(impresion));

        return ConsultaUnificadaResource.de(
                consulta.de(criterio, parametros.aPaginacion(ORDEN_POR_OMISION)));
    }
}
