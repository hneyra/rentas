package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.documentos.Campo;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.documentos.PuntoDeFirma;
import kamayuk.rentas.documentos.Tabla;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.ProfesionalDelFue;
import kamayuk.rentas.licencias.dominio.ProyectoDelFue;
import kamayuk.rentas.licencias.dominio.TerrenoDelFue;
import kamayuk.rentas.licencias.dominio.ValorizacionDeObra;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import org.jspecify.annotations.Nullable;

/**
 * Lo que se imprime en una licencia de edificacion y en sus resoluciones (#48, RF-113, RF-132).
 *
 * <h2>Donde iria la cifra que no hay, va una raya</h2>
 *
 * <p>La licencia lleva la valorizacion de obra. Cuando el cuadro de valores unitarios del conjunto
 * sellado no la permite calcular —que es lo que pasa mientras D-02a siga abierta— el papel imprime
 * {@link ValorizacionDelFue.Resultado#SIN_CIFRA} <b>y el motivo</b>, en vez de un cero. Un cero en
 * ese renglon se lee como «la obra no vale nada» y sirve de base para liquidar mal el derecho; una
 * raya con su explicacion se lee como lo que es.
 *
 * <h2>Por que un modelo y no una plantilla de texto</h2>
 *
 * <p>{@link ModeloDeDocumento} es lo que hace que el PDF, la hoja de calculo y el texto enriquecido
 * digan lo mismo, y es lo que {@code EmitirDocumento} <b>guarda</b>: una reimpresion de 2034 vuelve
 * a dibujar estos datos y no vuelve a leer el cuadro de valores unitarios, que para entonces sera
 * otro.
 *
 * <h2>D-05: la firma digital</h2>
 *
 * <p>La licencia sale <b>sin firma digital</b> y es imprimible igual. El regimen de firma es la
 * decision D-05 y sigue abierta; donde entra ya esta resuelto, y es {@link PuntoDeFirma}.
 */
final class ModeloDelFue {

    private ModeloDelFue() {}

    /** El titulo del papel de la licencia. */
    static final String TITULO = "Licencia de edificacion";

    /** El modelo de la licencia de edificacion. */
    static ModeloDeDocumento deLaLicencia(
            FueDeEdificacion fue,
            String numeroDeLicencia,
            LocalDate fechaDeEmision,
            VigenciaDeLaLicencia vigencia,
            String solicitante,
            String codigoDelSolicitante,
            TerrenoDelFue terreno,
            ProyectoDelFue proyecto,
            List<ProfesionalDelFue> profesionales,
            List<EstructuraDelProyecto> estructuras,
            ValorizacionDelFue.Resultado valorizacion,
            String numeroDeRecibo) {

        List<Campo> cabecera = new ArrayList<>();
        cabecera.add(Campo.de("Numero de licencia", numeroDeLicencia));
        cabecera.add(Campo.de("Expediente", fue.expediente()));
        cabecera.add(Campo.de("Tipo de tramite", fue.tipoTramite().etiqueta()));
        cabecera.add(Campo.de("Obra", fue.tipoObra().etiqueta()));
        cabecera.add(
                Campo.de(
                        "Modalidad de aprobacion",
                        fue.modalidad().name() + " — " + fue.modalidad().etiqueta()));
        cabecera.add(Campo.de("Solicitante", solicitante));
        cabecera.add(Campo.de("Codigo de contribuyente", codigoDelSolicitante));
        cabecera.add(
                Campo.de(
                        "Representante legal",
                        fue.representante() == null ? "" : fue.representante().nombre()));
        cabecera.add(Campo.de("Direccion del terreno", terreno.direccion()));
        cabecera.add(
                Campo.de(
                        "Manzana / lote",
                        texto(terreno.manzana()) + " / " + texto(terreno.lote())));
        cabecera.add(
                Campo.de("Area del terreno (m2)", terreno.areaTerreno().valor().toPlainString()));
        cabecera.add(Campo.de("Zonificacion", texto(terreno.zonificacion())));
        cabecera.add(Campo.de("Uso de la edificacion", proyecto.uso()));
        cabecera.add(Campo.de("Numero de pisos", String.valueOf(proyecto.numeroPisos())));
        cabecera.add(
                Campo.de(
                        "Area techada total (m2)", proyecto.areaTechada().valor().toPlainString()));
        cabecera.add(
                Campo.de(
                        "Plazo de ejecucion (meses)",
                        proyecto.plazoEnMeses() == null
                                ? ""
                                : String.valueOf(proyecto.plazoEnMeses())));
        cabecera.add(Campo.de("Fecha de emision", fechaDeEmision.toString()));
        cabecera.add(Campo.de("Vigencia", "del " + vigencia.desde() + " al " + vigencia.hasta()));
        cabecera.add(Campo.de("Valor de obra (S/)", valorDeObra(valorizacion)));
        cabecera.add(Campo.de("Recibo del derecho de tramite", numeroDeRecibo));

        return new ModeloDeDocumento(
                TITULO,
                numeroDeLicencia,
                fechaDeEmision,
                cabecera,
                List.of(
                        tablaDeProfesionales(profesionales),
                        tablaDeValorizacion(estructuras, valorizacion)),
                pieDeLaLicencia(valorizacion),
                null,
                null);
    }

    /** El modelo de la resolucion que revalida la licencia (AC 4). */
    static ModeloDeDocumento deLaRevalidacion(
            FueDeEdificacion original,
            String numeroDeLicencia,
            String solicitante,
            List<VigenciaDeLaLicencia> vigencias,
            LocalDate fecha,
            String numeroDeRecibo) {

        List<Campo> cabecera =
                List.of(
                        Campo.de("Licencia revalidada", numeroDeLicencia),
                        Campo.de("Expediente de origen", original.expediente()),
                        Campo.de("Solicitante", solicitante),
                        Campo.de("Fecha de la revalidacion", fecha.toString()),
                        Campo.de("Recibo del derecho de tramite", numeroDeRecibo));

        List<List<String>> filas = new ArrayList<>();
        for (VigenciaDeLaLicencia vigencia : vigencias) {
            filas.add(
                    List.of(
                            String.valueOf(vigencia.orden()),
                            vigencia.desde().toString(),
                            vigencia.hasta().toString()));
        }

        return new ModeloDeDocumento(
                "Resolucion de revalidacion de licencia de edificacion",
                numeroDeLicencia,
                fecha,
                cabecera,
                List.of(
                        Tabla.de(
                                "Vigencias de la licencia",
                                List.of("Tramo", "Desde", "Hasta"),
                                filas)),
                List.of(
                        "Ley 29090 — Ley de regulacion de habilitaciones urbanas y de"
                                + " edificaciones.",
                        "La revalidacion NO sustituye la licencia: prorroga su plazo. Los dos"
                                + " tramos de vigencia quedan en el expediente, cada uno con el"
                                + " acto que lo concedio.",
                        "",
                        "_______________________________",
                        "        Gerencia municipal",
                        "",
                        "Documento sin firma digital: el regimen de firma de resoluciones es la"
                                + " decision D-05, abierta."),
                null,
                null);
    }

    // ------------------------------------------------------------------

    private static String valorDeObra(ValorizacionDelFue.Resultado valorizacion) {
        ValorizacionDeObra.Valorizacion obra = valorizacion.valorizacion();
        return obra == null
                ? ValorizacionDelFue.Resultado.SIN_CIFRA
                : obra.total().valor().toPlainString();
    }

    private static Tabla tablaDeProfesionales(List<ProfesionalDelFue> profesionales) {
        List<List<String>> filas = new ArrayList<>();
        for (ProfesionalDelFue profesional : profesionales) {
            filas.add(
                    List.of(
                            profesional.tipo().etiqueta(),
                            profesional.nombre(),
                            texto(profesional.colegio()),
                            texto(profesional.colegiatura())));
        }
        return Tabla.de(
                "Profesionales responsables",
                List.of("Rol", "Nombre", "Colegio", "Colegiatura"),
                filas);
    }

    /**
     * La valorizacion por pisos y estructuras.
     *
     * <p>Cuando no se pudo calcular, la tabla sale con la estructura declarada y una raya en la
     * columna del importe: lo que el administrado declaro <b>si</b> se conoce, y esconder la tabla
     * entera por falta del cuadro haria parecer que tampoco declaro nada.
     */
    private static Tabla tablaDeValorizacion(
            List<EstructuraDelProyecto> estructuras, ValorizacionDelFue.Resultado valorizacion) {

        List<List<String>> filas = new ArrayList<>();
        ValorizacionDeObra.Valorizacion obra = valorizacion.valorizacion();
        if (obra == null) {
            for (EstructuraDelProyecto estructura : estructuras) {
                filas.add(
                        List.of(
                                String.valueOf(estructura.piso()),
                                estructura.partida().etiqueta(),
                                String.valueOf(estructura.categoria()),
                                estructura.area().valor().toPlainString(),
                                ValorizacionDelFue.Resultado.SIN_CIFRA));
            }
        } else {
            for (ValorizacionDeObra.LineaValorizada linea : obra.lineas()) {
                filas.add(
                        List.of(
                                String.valueOf(linea.piso()),
                                linea.partida().etiqueta(),
                                String.valueOf(linea.categoria()),
                                linea.area().valor().toPlainString(),
                                linea.importe().valor().toPlainString()));
            }
        }
        return Tabla.de(
                "Valorizacion de obra por pisos y estructuras",
                List.of("Piso", "Partida", "Categoria", "Area m2", "Valor S/"),
                filas);
    }

    private static List<String> pieDeLaLicencia(ValorizacionDelFue.Resultado valorizacion) {
        List<String> pie = new ArrayList<>();
        pie.add("Ley 29090 — Ley de regulacion de habilitaciones urbanas y de edificaciones.");
        pie.add("La presente licencia debe exhibirse en la obra durante su ejecucion.");
        String motivo = valorizacion.motivo();
        if (motivo != null) {
            pie.add(
                    "Valor de obra no valorizado ("
                            + ValorizacionDelFue.Resultado.SIN_CIFRA
                            + "): "
                            + motivo);
        }
        pie.add("");
        pie.add("_______________________________");
        pie.add("        Gerencia municipal");
        pie.add("");
        pie.add(
                "Documento sin firma digital: el regimen de firma de resoluciones es la decision"
                        + " D-05, abierta.");
        return List.copyOf(pie);
    }

    private static String texto(@Nullable String valor) {
        return valor == null ? "" : valor;
    }
}
