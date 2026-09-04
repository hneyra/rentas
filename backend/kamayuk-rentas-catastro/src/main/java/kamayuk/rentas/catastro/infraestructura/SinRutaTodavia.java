package kamayuk.rentas.catastro.infraestructura;

import java.time.LocalDate;
import java.util.Optional;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.CuotaDeTitularidad;
import kamayuk.rentas.catastro.GestorDeTitularidad;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.catastro.LectorDeFichasEconomicas;
import kamayuk.rentas.catastro.TransferenciaDeFiscalizacion;
import kamayuk.rentas.catastro.VersionTransferida;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Los SIETE puertos de {@code catastro} que todavia no tienen ruta que los conteste (P5C).
 *
 * <h2>Por que existe esta clase, y por que no devuelve vacio</h2>
 *
 * <p>`V6` retiro de esta base las quince tablas de {@code catastro}. Los puertos siguen siendo el
 * contrato —no se tocaron, y por eso las veintisiete clases que los consumen no cambiaron— pero las
 * rutas que ADR-0030 fija para esta frontera <b>todavia no las publica `catastro`</b>: sus
 * controladores sirven hoy la grilla de fichas, el listado de predios, el resumen predial y las
 * escrituras de titularidad e inquilinos.
 *
 * <p>La alternativa a esta clase era que cada uno devolviera <b>vacio</b>, y eso es exactamente lo
 * que no se puede hacer: una lista vacia de predios se lee como «este contribuyente no tiene
 * ninguno» y un {@code Optional.empty()} como «este predio no tiene ficha». Las dos son respuestas
 * plausibles y falsas — la determinacion predial saldria con la base a cero y ninguna cifra
 * pareceria mal. Es el criterio de #48 con la licencia que salia con «valor de obra 0,00», y el que
 * el propio {@code LectorDeValoresUnitarios} ya llevaba escrito: «no devuelve vacio y no devuelve
 * ceros».
 *
 * <p><b>Y esto NO es una regresion que introduzca P5C: es la que P5C hace visible.</b> Mientras las
 * tablas seguian aqui, `rentas` era dueno de un catastro que ya vivia en otro repositorio, y la
 * frontera era mentira. Lo que falta esta declarado en el entregable de la etapa.
 *
 * <p>Son tres clases y no una porque los tres puertos declaran {@code de(long, LocalDate)} y en
 * Java eso es la misma firma borrada. La division no es de diseno: la impone el lenguaje, y la
 * conviene decir para que nadie las junte «por orden».
 *
 * <p>El dia que `catastro` publique cada ruta, lo que se hace es mover ese metodo a un adaptador
 * como {@link FichasDelPadronHttp} y quitarlo de aqui. <b>Esta clase encoge</b>; es la lista de
 * trabajo pendiente de esta frontera, escrita donde se ejecuta.
 */
@Component
public class SinRutaTodavia
        implements LectorDeFichas,
                LectorDeFichasEconomicas,
                LectorDeCaracteristicas,
                GestorDeTitularidad,
                TransferenciaDeFiscalizacion {

    @Override
    public Optional<Long> fichaVigenteEn(long predioId, LocalDate fecha) {
        throw falta(
                "la ficha vigente del predio " + predioId,
                "GET catastro/api/v1/predios/{id}/caracteristicas");
    }

    @Override
    public Optional<AreaM2> areaDeLaVersion(long fichaId) {
        throw falta(
                "el area de la version de ficha " + fichaId,
                "GET catastro/api/v1/predios/{id}/caracteristicas");
    }

    @Override
    public Optional<Long> fichaEconomicaVigenteEn(long predioId, LocalDate fecha) {
        throw falta(
                "la ficha economica vigente del predio " + predioId,
                "GET catastro/api/v1/predios/{id}/caracteristicas");
    }

    @Override
    public Optional<CaracteristicasDelPredio> de(long predioId, LocalDate fecha) {
        throw falta(
                "las caracteristicas del predio " + predioId,
                "GET catastro/api/v1/predios/{id}/caracteristicas");
    }

    @Override
    public Optional<CuotaDeTitularidad> vigenteDe(
            long predioId, long contribuyenteId, LocalDate fecha) {
        throw falta(
                "la cuota de titularidad del predio " + predioId,
                "GET catastro/api/v1/predios/{id}/titulares");
    }

    @Override
    public CuotaDeTitularidad transferir(
            long titularidadAnteriorId,
            long adquirienteId,
            Porcentaje porcentajeTransferido,
            LocalDate fecha,
            String documentoOrigen,
            Observacion observacion) {
        throw falta(
                "transferir la titularidad " + titularidadAnteriorId,
                "POST catastro/api/v1/predios/{id}/titularidad");
    }

    @Override
    public VersionTransferida inscribirLoHallado(
            long predioId,
            LocalDate desde,
            String documentoOrigen,
            @Nullable AreaM2 areaHallada,
            @Nullable String usoHallado,
            Observacion observacion) {
        throw falta(
                "inscribir lo hallado en el predio " + predioId,
                "POST catastro/api/v1/predios/{id}/transferencia-fiscal");
    }

    private static ClienteHttpDeCatastro.SinRutaEnCatastro falta(
            String que, String operacionQueLoServiria) {
        return new ClienteHttpDeCatastro.SinRutaEnCatastro(que, operacionQueLoServiria);
    }
}
