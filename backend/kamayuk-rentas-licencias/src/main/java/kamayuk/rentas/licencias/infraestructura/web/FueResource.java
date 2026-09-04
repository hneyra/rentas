package kamayuk.rentas.licencias.infraestructura.web;

import java.time.LocalDate;
import java.util.List;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.licencias.aplicacion.ConsultaDeFue;
import kamayuk.rentas.licencias.aplicacion.ValorizacionDelFue;
import kamayuk.rentas.licencias.dominio.EstructuraDelProyecto;
import kamayuk.rentas.licencias.dominio.FueDeEdificacion;
import kamayuk.rentas.licencias.dominio.MovimientoDeEdificacion;
import kamayuk.rentas.licencias.dominio.ProfesionalDelFue;
import kamayuk.rentas.licencias.dominio.RequisitoDelFue;
import kamayuk.rentas.licencias.dominio.SeccionDelFue;
import kamayuk.rentas.licencias.dominio.TerrenoDelFue;
import kamayuk.rentas.licencias.dominio.ValorizacionDeObra;
import kamayuk.rentas.licencias.dominio.VigenciaDeLaLicencia;
import kamayuk.rentas.web.ImporteActualizado;
import org.jspecify.annotations.Nullable;

/**
 * Un expediente del FUE tal como sale por HTTP (#48, RF-113).
 *
 * <p>Los nombres de los campos son los de la pantalla {@code fue_edificacion}: {@code
 * nroExpediente}, {@code nroLicencia}, {@code nombreContribuyente}, {@code modalidad}. No son los
 * de la tabla ni los del dominio: el contrato lo fija el prototipo.
 *
 * <p><b>{@code estadoALaFecha} viaja siempre</b>: el estado de una licencia de edificacion depende
 * del dia, asi que una respuesta que dijera «VENCIDA» sin decir a que fecha seria una respuesta que
 * manana significa otra cosa (regla 9, RNF-075).
 *
 * <p><b>La unica cifra que puede viajar es el valor de obra</b>, y viaja como {@link
 * ImporteActualizado} —con su fecha— o no viaja: cuando el cuadro sellado no permite calcularla, el
 * campo es nulo y {@code valorDeObraNoDisponible} dice por que, nombrando la llave que falta. Un
 * cero ahi se leeria como «la obra no vale nada» (AC 2 de #48; las cifras las espera #197).
 *
 * <p><b>El area viaja tipada</b> (#607). Se escribia a mano con {@code valor().toPlainString()}:
 * daba la cifra buena, pero era una segunda convencion para lo mismo, y de tener dos salio que
 * catastro compusiera con {@code toString()} y publicara «360.00 m2» del mismo predio que aqui sale
 * «360.00». Ahora la escribe el serializador que {@code ConfiguracionDeJson} registra para {@code
 * AreaM2}, que es un solo sitio; la unidad la sigue poniendo el nombre del campo, no el dato.
 */
public record FueResource(
        String nroExpediente,
        LocalDate fechaDeclaracion,
        @Nullable String nroLicencia,
        String est,
        String estado,
        LocalDate estadoALaFecha,
        String contribuyente,
        String nombreContribuyente,
        String tipoTramite,
        String obra,
        String modalidad,
        @Nullable String revision,
        @Nullable String nroExpedienteAnterior,
        boolean solicitanteEsPropietario,
        @Nullable RepresentanteResource representanteLegal,
        @Nullable TerrenoResource terreno,
        @Nullable ProyectoResource proyecto,
        List<EstructuraResource> valorizacion,
        @Nullable ImporteActualizado valorDeObra,
        @Nullable String valorDeObraNoDisponible,
        @Nullable String llaveQueFalta,
        List<ProfesionalResource> profesionales,
        List<RequisitoResource> documentos,
        List<MovimientoResource> historial,
        List<VigenciaResource> vigencias,
        List<String> seccionesFaltantes,
        boolean completo) {

    /** La fila de la grilla: sin secciones, sin valorizacion y sin historial. */
    public static FueResource de(ConsultaDeFue.FueEnConsulta fila) {
        FueDeEdificacion fue = fila.fue();
        return new FueResource(
                fue.expediente(),
                fue.fechaDeclaracion(),
                fila.numeroDeLicencia(),
                fila.estado().inicial(),
                fila.estado().name(),
                fila.aLaFecha(),
                fila.codigoDelSolicitante(),
                fila.nombreDelSolicitante(),
                fue.tipoTramite().name(),
                fue.tipoObra().name(),
                fue.modalidad().name(),
                fue.revision() == null ? null : fue.revision().name(),
                fue.expedienteAnterior(),
                fue.solicitantePropietario(),
                RepresentanteResource.de(fue),
                fila.terreno() == null ? null : TerrenoResource.de(fila.terreno()),
                null,
                List.of(),
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false);
    }

    /** La ficha completa: las cinco secciones, el historial, las vigencias y la valorizacion. */
    public static FueResource de(ConsultaDeFue.FichaDelFue ficha) {
        ConsultaDeFue.FueEnConsulta fila = ficha.fila();
        FueDeEdificacion fue = fila.fue();
        ValorizacionDelFue.Resultado valorizacion = ficha.valorizacion();
        ValorizacionDeObra.Valorizacion obra = valorizacion.valorizacion();

        return new FueResource(
                fue.expediente(),
                fue.fechaDeclaracion(),
                fila.numeroDeLicencia(),
                fila.estado().inicial(),
                fila.estado().name(),
                fila.aLaFecha(),
                fila.codigoDelSolicitante(),
                fila.nombreDelSolicitante(),
                fue.tipoTramite().name(),
                fue.tipoObra().name(),
                fue.modalidad().name(),
                fue.revision() == null ? null : fue.revision().name(),
                fue.expedienteAnterior(),
                fue.solicitantePropietario(),
                RepresentanteResource.de(fue),
                ficha.terreno() == null ? null : TerrenoResource.de(ficha.terreno()),
                ficha.proyecto() == null ? null : ProyectoResource.de(ficha.proyecto()),
                ficha.estructuras().stream().map(EstructuraResource::de).toList(),
                // La cifra va con su fecha o no va: el ejercicio del conjunto sellado no basta,
                // porque la pantalla imprime una hoja y esa hoja tiene que decir de que dia es.
                obra == null ? null : new ImporteActualizado(obra.total(), fila.aLaFecha()),
                valorizacion.motivo(),
                valorizacion.llaveQueFalta(),
                ficha.profesionales().stream().map(ProfesionalResource::de).toList(),
                ficha.requisitos().stream().map(RequisitoResource::de).toList(),
                ficha.historial().stream().map(MovimientoResource::de).toList(),
                ficha.vigencias().stream().map(VigenciaResource::de).toList(),
                ficha.seccionesFaltantes().stream().map(SeccionDelFue::name).toList(),
                ficha.estaCompleto());
    }

    /** El representante legal, cuando el FUE lo declara. */
    public record RepresentanteResource(
            String dni, String nombre, String partidaRegistral, @Nullable LocalDate vigenciaPoder) {

        static @Nullable RepresentanteResource de(FueDeEdificacion fue) {
            var representante = fue.representante();
            return representante == null
                    ? null
                    : new RepresentanteResource(
                            representante.documento(),
                            representante.nombre(),
                            representante.partidaRegistral(),
                            representante.vigenciaDelPoder());
        }
    }

    /** Los datos urbanos. */
    public record TerrenoResource(
            int version,
            @Nullable String codCatastral,
            String direccion,
            @Nullable String mz,
            @Nullable String lt,
            AreaM2 areaDelTerrenoM,
            @Nullable String zonificacion,
            @Nullable String partidaRegistral,
            @Nullable String frenteM,
            @Nullable String fondoM) {

        static TerrenoResource de(TerrenoDelFue terreno) {
            return new TerrenoResource(
                    terreno.version(),
                    terreno.codigoCatastral(),
                    terreno.direccion(),
                    terreno.manzana(),
                    terreno.lote(),
                    terreno.areaTerreno(),
                    terreno.zonificacion(),
                    terreno.partidaRegistral(),
                    terreno.frente() == null ? null : terreno.frente().magnitud().toPlainString(),
                    terreno.fondo() == null ? null : terreno.fondo().magnitud().toPlainString());
        }
    }

    /** Las caracteristicas del proyecto. Sin ninguna cifra de dinero: ver la clase. */
    public record ProyectoResource(
            int version,
            String usoDeLaEdificacion,
            int nDePisos,
            AreaM2 areaTechadaTotalM,
            @Nullable AreaM2 areaLibreM,
            @Nullable Integer nDeEstacionamientos,
            @Nullable Integer plazoDeEjecucionMeses) {

        static ProyectoResource de(kamayuk.rentas.licencias.dominio.ProyectoDelFue proyecto) {
            return new ProyectoResource(
                    proyecto.version(),
                    proyecto.uso(),
                    proyecto.numeroPisos(),
                    proyecto.areaTechada(),
                    proyecto.areaLibre(),
                    proyecto.estacionamientos(),
                    proyecto.plazoEnMeses());
        }
    }

    /**
     * Una linea declarada de la valorizacion.
     *
     * <p><b>Sin importe.</b> El importe de cada linea es un producto contra el cuadro de #17, y
     * viaja solo dentro del total de la ficha —una vez, con su fecha—: repetirlo por linea seria
     * exponer veintiuna cifras sin fecha en el mismo cuerpo.
     */
    public record EstructuraResource(int piso, String partida, String categoria, AreaM2 areaM) {

        static EstructuraResource de(EstructuraDelProyecto estructura) {
            return new EstructuraResource(
                    estructura.piso(),
                    estructura.partida().name(),
                    String.valueOf(estructura.categoria()),
                    estructura.area());
        }
    }

    /** Un proyectista o el responsable de obra. */
    public record ProfesionalResource(
            String tipo, String nombre, @Nullable String colegio, @Nullable String colegiatura) {

        static ProfesionalResource de(ProfesionalDelFue profesional) {
            return new ProfesionalResource(
                    profesional.tipo().name(),
                    profesional.nombre(),
                    profesional.colegio(),
                    profesional.colegiatura());
        }
    }

    /** Un documento adjunto declarado. */
    public record RequisitoResource(
            String requisito, boolean presentado, @Nullable Integer folios) {

        static RequisitoResource de(RequisitoDelFue requisito) {
            return new RequisitoResource(
                    requisito.requisito(), requisito.presentado(), requisito.folios());
        }
    }

    /** Un movimiento del historial. */
    public record MovimientoResource(
            String tipo,
            LocalDate fecha,
            @Nullable String nroLicencia,
            @Nullable String motivo,
            String resolucion,
            String observacion) {

        static MovimientoResource de(MovimientoDeEdificacion movimiento) {
            return new MovimientoResource(
                    movimiento.tipo().name(),
                    movimiento.fecha(),
                    movimiento.numeroLicencia(),
                    movimiento.motivo(),
                    movimiento.documentoNumero(),
                    movimiento.observacion().texto());
        }
    }

    /** Un tramo de vigencia, con el acto que lo concedio (AC 4). */
    public record VigenciaResource(int tramo, LocalDate desde, LocalDate hasta) {

        static VigenciaResource de(VigenciaDeLaLicencia vigencia) {
            return new VigenciaResource(vigencia.orden(), vigencia.desde(), vigencia.hasta());
        }
    }
}
