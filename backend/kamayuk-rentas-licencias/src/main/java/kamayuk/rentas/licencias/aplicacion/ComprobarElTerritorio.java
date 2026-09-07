package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import kamayuk.rentas.catastro.ItseDelPredio;
import kamayuk.rentas.catastro.RiesgoDelPredio;
import kamayuk.rentas.catastro.RiesgoYItseDelPredio;
import kamayuk.rentas.catastro.ZonaDelPredio;
import kamayuk.rentas.catastro.ZonificacionDelPredio;
import kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro;
import kamayuk.rentas.licencias.dominio.CompatibilidadConLaZona;
import kamayuk.rentas.licencias.dominio.ComprobacionDelTerritorio;
import kamayuk.rentas.licencias.dominio.RespuestaDelTerritorio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Le pregunta al territorio lo que hace falta para autorizar un establecimiento (#43, AC-2 y AC-4).
 *
 * <h2>Por que las TRES consultas van juntas y en un solo sitio</h2>
 *
 * <p>Porque la decision es una: si el lote esta sobre riesgo no mitigable no hay certificado que
 * valga, y si lo esta sobre riesgo mitigable el certificado es justo lo que decide (#9). Repartir
 * las preguntas entre quien emite y quien renueva dejaria que una de las dos autorizara sobre media
 * respuesta, que es la forma de fallo que {@link RiesgoYItseDelPredio} existe para impedir.
 *
 * <h2>Y por que la ausencia NO se traduce a un valor</h2>
 *
 * <p>Las tres excepciones que {@code catastro} distingue llegan aqui como tres {@link
 * RespuestaDelTerritorio} distintas, y ninguna se convierte en «no hay riesgo» ni en «zona
 * compatible». {@link ComprobacionDelTerritorio} lo sostiene por construccion: no deja afirmar un
 * hecho que no vino. Quien decide es {@link EmitirLicenciaDeFuncionamiento}, y decide con las
 * cuatro respuestas delante.
 *
 * <p><b>No abre transaccion y no escribe nada.</b> Son tres lecturas HTTP a otro sistema; meterlas
 * dentro de la transaccion que despues escribe la licencia dejaria una conexion de la base abierta
 * durante tres viajes de red, y una de ellas que fallara marcaria la transaccion del anfitrion como
 * <i>rollback-only</i> (la leccion de #54 y #72).
 */
@Service
public class ComprobarElTerritorio {

    private final ZonificacionDelPredio zonificacion;
    private final RiesgoYItseDelPredio riesgoYItse;

    public ComprobarElTerritorio(
            ZonificacionDelPredio zonificacion, RiesgoYItseDelPredio riesgoYItse) {
        this.zonificacion = zonificacion;
        this.riesgoYItse = riesgoYItse;
    }

    /**
     * Pregunta por el predio a esa fecha y compone el resultado.
     *
     * @param predioId el establecimiento; {@code null} si la solicitud no lo declara
     * @param aLaFecha el dia de la emision — entra como argumento y no del reloj (regla 6), y viaja
     *     a las tres rutas: un plan se sustituye por otro y un certificado vence
     * @param zonasCompatiblesDelGiro el texto libre de {@code ciiu.zonificacion_compatible} del
     *     giro PRINCIPAL, que es el que decide (ver {@code LicenciaDeFuncionamiento})
     */
    public ComprobacionDelTerritorio de(
            @Nullable Long predioId, LocalDate aLaFecha, @Nullable String zonasCompatiblesDelGiro) {

        if (predioId == null) {
            return ComprobacionDelTerritorio.sinPredio(aLaFecha);
        }

        StringBuilder motivo = new StringBuilder();

        RespuestaDelTerritorio queDijoLaZona;
        String codigoDeZona = null;
        String ordenanza = null;
        try {
            ZonaDelPredio zona = zonificacion.zonaDe(predioId, aLaFecha);
            queDijoLaZona = RespuestaDelTerritorio.RESPONDIO;
            codigoDeZona = zona.codigo();
            ordenanza = zona.ordenanza();
        } catch (ClienteHttpDeCatastro.NoConstaEnCatastro noConsta) {
            queDijoLaZona = RespuestaDelTerritorio.NO_CONSTA;
            anotar(motivo, "zona: no consta (" + noConsta.codigo() + ")");
        } catch (ClienteHttpDeCatastro.CatastroInalcanzable noSePudo) {
            queDijoLaZona = RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR;
            anotar(motivo, "zona: no se pudo preguntar a `catastro`");
        }

        RespuestaDelTerritorio queDijoElRiesgo;
        boolean noMitigable = false;
        try {
            RiesgoDelPredio riesgo = riesgoYItse.riesgoDe(predioId, aLaFecha);
            queDijoElRiesgo = RespuestaDelTerritorio.RESPONDIO;
            noMitigable = riesgo.hayRiesgoNoMitigable();
        } catch (ClienteHttpDeCatastro.NoConstaEnCatastro noConsta) {
            queDijoElRiesgo = RespuestaDelTerritorio.NO_CONSTA;
            anotar(motivo, "riesgo: no consta (" + noConsta.codigo() + ")");
        } catch (ClienteHttpDeCatastro.CatastroInalcanzable noSePudo) {
            queDijoElRiesgo = RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR;
            anotar(motivo, "riesgo: no se pudo preguntar a `catastro`");
        }

        RespuestaDelTerritorio queDijoElItse;
        int vigentes = 0;
        try {
            ItseDelPredio itse = riesgoYItse.itseVigenteEn(predioId, aLaFecha);
            queDijoElItse = RespuestaDelTerritorio.RESPONDIO;
            vigentes = itse.vigentes().size();
        } catch (ClienteHttpDeCatastro.NoConstaEnCatastro noConsta) {
            queDijoElItse = RespuestaDelTerritorio.NO_CONSTA;
            anotar(motivo, "ITSE: no consta (" + noConsta.codigo() + ")");
        } catch (ClienteHttpDeCatastro.CatastroInalcanzable noSePudo) {
            queDijoElItse = RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR;
            anotar(motivo, "ITSE: no se pudo preguntar a `catastro`");
        }

        CompatibilidadConLaZona compatibilidad =
                CompatibilidadConLaZona.evaluar(codigoDeZona, zonasCompatiblesDelGiro);
        if (compatibilidad == CompatibilidadConLaZona.NO_SE_PUEDE_DECIDIR
                && queDijoLaZona == RespuestaDelTerritorio.RESPONDIO) {
            anotar(
                    motivo,
                    "el giro principal no declara zonas compatibles en el catalogo CIIU (D-02b)");
        }
        if (compatibilidad == CompatibilidadConLaZona.INCOMPATIBLE) {
            anotar(motivo, "el giro principal no cabe en la zona " + codigoDeZona);
        }
        if (noMitigable) {
            anotar(motivo, "el lote cruza una zona de riesgo NO MITIGABLE");
        }

        return new ComprobacionDelTerritorio(
                aLaFecha,
                queDijoLaZona,
                codigoDeZona,
                ordenanza,
                queDijoElRiesgo,
                noMitigable,
                queDijoElItse,
                vigentes,
                compatibilidad,
                motivo.isEmpty() ? null : motivo.toString());
    }

    private static void anotar(StringBuilder motivo, String linea) {
        if (!motivo.isEmpty()) {
            motivo.append("; ");
        }
        motivo.append(linea);
    }
}
