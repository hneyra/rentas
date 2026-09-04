package kamayuk.rentas.catastro.infraestructura.web;

import java.util.List;
import kamayuk.rentas.catastro.dominio.ActividadEconomica;
import kamayuk.rentas.catastro.dominio.BienComun;
import kamayuk.rentas.catastro.dominio.Colindante;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.DetalleDeBienesComunes;
import kamayuk.rentas.catastro.dominio.DetalleDeLaFicha;
import kamayuk.rentas.catastro.dominio.DetalleEconomico;
import kamayuk.rentas.catastro.dominio.DetalleRural;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.OtraInstalacion;
import kamayuk.rentas.catastro.dominio.ParticipacionComun;
import kamayuk.rentas.catastro.dominio.TierraRural;
import kamayuk.rentas.catastro.dominio.VersionDeLaFicha;
import kamayuk.rentas.dominio.AreaM2;
import org.jspecify.annotations.Nullable;

/**
 * Una version de la ficha, tal como sale por HTTP.
 *
 * <p>Publica la <b>version</b> y su <b>vigencia</b>, no solo los datos: quien lee una ficha tiene
 * que poder decir cual de todas esta viendo y desde cuando rige. Una pantalla que muestra el area
 * sin decir de que version es no permite explicar por que la determinacion salio distinta.
 *
 * <p>Las construcciones salen con sus <b>categorias</b>, nunca con importes: cuanto vale cada
 * categoria es D-02a y vive en datos versionados (regla 5). Lo mismo con los grupos de tierra —van
 * en hectareas, sin arancel— y con los bienes comunes.
 *
 * <p>El {@code historico} viaja solo cuando se pide con {@code ?historico=true}: son todas las
 * versiones de la ficha, y la pantalla que solo pinta la vigente no tiene por que pagarlas. Nulo
 * significa «no lo pediste»; una lista vacia significaria «no hay ninguna», que no puede pasar.
 *
 * <p>Los tres bloques de detalle son <b>nulos salvo el que toca</b>. Una ficha rural no publica un
 * bloque economico vacio: quien lea el JSON tiene que poder distinguir «este predio no declara
 * actividad» de «esta ficha no es de las que la declaran».
 *
 * <h2>Lo que el recurso publica y antes recortaba (#290)</h2>
 *
 * <p>La cabecera lleva {@code frontis}, {@code condicionPropiedad} y {@code tipoEdificacion}; las
 * construcciones, su {@code porcentajeConstruido}; y la ficha entera, sus <b>obras
 * complementarias</b> ({@code instalaciones}). Estaban en el dominio y en la base desde {@code V1}
 * —se guardaban al inscribir la ficha y se copiaban al versionar— pero no salian por HTTP, asi que
 * la pantalla que las declaraba no podia volver a verlas. Publicar lo que ya se guarda no es
 * modelado nuevo: es dejar de perderlo en el ultimo tramo.
 *
 * <p>{@code frontis} sale con su unidad —{@code "12.50 ML"}— por lo mismo que la superficie rural:
 * son metros lineales, y un numero suelto invita a leerlos como metros cuadrados.
 *
 * <p><b>Las areas van tipadas y las medidas no, y la diferencia no es un descuido</b> (#607). Un
 * {@code AreaM2} —{@code areaTerreno}, {@code areaConstruida}, {@code areaOcupada}, {@code
 * areaComunTotal}, el area de un bien comun— lo escribe el serializador registrado en {@code
 * ConfiguracionDeJson}: la cifra sola, {@code "360.00"}, y la unidad la pone la cabecera de la
 * columna. Una {@link kamayuk.rentas.dominio.Medida} —{@code frontis}, la {@code cantidad} de una
 * obra complementaria, las hectareas del bloque rural— sale con su unidad dentro, {@code "12.50
 * ML"} o {@code "12.5000 HA"}, porque ahi la unidad <b>es</b> parte del dato: el arancel rural es
 * por hectarea, y quien lea metros calcularia diez mil veces de menos.
 *
 * <p><b>Sigue sin salir un solo importe.</b> Ni valor unitario, ni arancel, ni valor de la obra
 * complementaria, ni autovaluo: son D-02a/D-11 y viven en datos versionados (regla 5). Lo que se
 * publica es lo que el tecnico midio y clasifico.
 */
public record FichaResource(
        long id,
        long predioId,
        String tipo,
        int version,
        AreaM2 areaTerreno,
        String uso,
        @Nullable String frontis,
        @Nullable String condicionPropiedad,
        @Nullable String tipoEdificacion,
        String vigenciaDesde,
        @Nullable String vigenciaHasta,
        boolean vigente,
        String origen,
        String documentoOrigen,
        String observacion,
        @Nullable String denominacion,
        List<ConstruccionResource> construcciones,
        List<InstalacionResource> instalaciones,
        @Nullable EconomicoResource economico,
        @Nullable BienesComunesResource bienesComunes,
        @Nullable RuralResource rural,
        @Nullable List<VersionResource> historico) {

    public static FichaResource de(FichaCatastral ficha) {
        return construir(ficha, null);
    }

    /** La misma ficha con su historico, cuando la peticion lo pide (RF-006). */
    public static FichaResource con(FichaCatastral ficha, List<VersionDeLaFicha> versiones) {
        return construir(ficha, versiones.stream().map(VersionResource::de).toList());
    }

    private static FichaResource construir(
            FichaCatastral ficha, @Nullable List<VersionResource> historico) {
        DetalleDeLaFicha detalle = ficha.detalle();
        return new FichaResource(
                ficha.id() == null ? 0L : ficha.id(),
                ficha.predioId(),
                ficha.tipo().name(),
                ficha.version(),
                ficha.areaTerreno(),
                ficha.uso(),
                ficha.frontis() == null ? null : ficha.frontis().toString(),
                ficha.condicionPropiedad(),
                ficha.tipoEdificacion(),
                ficha.vigenciaDesde().toString(),
                ficha.vigenciaHasta() == null ? null : ficha.vigenciaHasta().toString(),
                ficha.estaVigente(),
                ficha.origen().name(),
                ficha.documentoOrigen(),
                ficha.observacion().texto(),
                ficha.denominacion(),
                ficha.construcciones().stream().map(ConstruccionResource::de).toList(),
                ficha.instalaciones().stream().map(InstalacionResource::de).toList(),
                detalle instanceof DetalleEconomico economico
                        ? EconomicoResource.de(economico)
                        : null,
                detalle instanceof DetalleDeBienesComunes comunes
                        ? BienesComunesResource.de(comunes)
                        : null,
                detalle instanceof DetalleRural rural ? RuralResource.de(rural) : null,
                historico);
    }

    /**
     * Una fila del historico: <b>que rigio, cuando, quien lo escribio y por que</b>.
     *
     * <p>La observacion es la mitad util. Un diff dice que el area paso de 120 a 180; solo la
     * observacion dice que fue una fiscalizacion de campo y no un error de tecleo, y es lo que se
     * lee en voz alta cuando el contribuyente pregunta por que le subio el recibo.
     */
    public record VersionResource(
            long id,
            int version,
            AreaM2 areaTerreno,
            String uso,
            String vigenciaDesde,
            @Nullable String vigenciaHasta,
            boolean vigente,
            String origen,
            String documentoOrigen,
            String observacion,
            String usuario,
            String registradaEn) {

        public static VersionResource de(VersionDeLaFicha version) {
            return new VersionResource(
                    version.id(),
                    version.version(),
                    version.areaTerreno(),
                    version.uso(),
                    version.vigenciaDesde().toString(),
                    version.vigenciaHasta() == null ? null : version.vigenciaHasta().toString(),
                    version.estaVigente(),
                    version.origen().name(),
                    version.documentoOrigen(),
                    version.observacion().texto(),
                    version.usuario(),
                    version.registradaEn().toString());
        }
    }

    /**
     * Lo construido en un piso: medidas y categorias, cero importes.
     *
     * <p>{@code porcentajeConstruido} es cuanto del piso esta efectivamente construido —una obra a
     * medias, un piso que se levanto solo en parte—. Sale con su signo, {@code "60.00 %"}, igual
     * que los demas porcentajes del sistema. Nulo significa que la ficha no lo declara, que es
     * distinto de declarar cero.
     */
    public record ConstruccionResource(
            long id,
            String piso,
            AreaM2 areaConstruida,
            @Nullable Integer anioConstruccion,
            @Nullable String material,
            @Nullable String estadoConservacion,
            String categorias,
            @Nullable String porcentajeConstruido) {

        public static ConstruccionResource de(Construccion construccion) {
            return new ConstruccionResource(
                    construccion.id() == null ? 0L : construccion.id(),
                    construccion.piso(),
                    construccion.areaConstruida(),
                    construccion.anioConstruccion() == null
                            ? null
                            : construccion.anioConstruccion().valor(),
                    construccion.material() == null ? null : construccion.material().name(),
                    construccion.estadoConservacion() == null
                            ? null
                            : construccion.estadoConservacion().name(),
                    construccion.categorias().toString(),
                    construccion.porcentajeConstruido() == null
                            ? null
                            : construccion.porcentajeConstruido().toString());
        }
    }

    /**
     * Una obra complementaria: cerco, piscina, tanque, pavimento (#290).
     *
     * <p>Lleva <b>que es</b>, <b>cuanto</b> y <b>en que unidad</b>. La cantidad viaja con su unidad
     * dentro —{@code "30.00 ML"}— porque «30» no significa lo mismo en metros lineales que en
     * unidades, y ademas por separado en {@code unidad}, que es lo que una grilla pinta en su
     * columna sin tener que partir una cadena.
     *
     * <p><b>Sin su valor.</b> Cuanto vale la obra sale de un valor unitario, el incremento del 5 %,
     * la depreciacion y un factor de oficializacion que ni siquiera tiene fuente identificada
     * (D-11): todo eso es dato normativo y no sale de aqui (regla 5).
     */
    public record InstalacionResource(
            long id,
            String descripcion,
            String unidad,
            String cantidad,
            @Nullable Integer anioConstruccion,
            @Nullable String estadoConservacion) {

        public static InstalacionResource de(OtraInstalacion instalacion) {
            return new InstalacionResource(
                    instalacion.id() == null ? 0L : instalacion.id(),
                    instalacion.descripcion(),
                    instalacion.cantidad().unidad(),
                    instalacion.cantidad().toString(),
                    instalacion.anioConstruccion() == null
                            ? null
                            : instalacion.anioConstruccion().valor(),
                    instalacion.estadoConservacion() == null
                            ? null
                            : instalacion.estadoConservacion().name());
        }
    }

    /** Lo que se hace en la unidad y con que autorizaciones (RF-002). */
    public record EconomicoResource(
            List<ActividadResource> actividades,
            @Nullable String informacionComplementaria,
            int sinLicencia) {

        public static EconomicoResource de(DetalleEconomico detalle) {
            return new EconomicoResource(
                    detalle.actividades().stream().map(ActividadResource::de).toList(),
                    detalle.informacionComplementaria(),
                    detalle.sinLicencia().size());
        }
    }

    /**
     * Una actividad, con su licencia por numero.
     *
     * <p>{@code licenciaNumero} nulo no es un dato que falte: es el hallazgo. La pantalla lo pinta
     * distinto y fiscalizacion sale de ahi.
     *
     * <p>{@code anuncioFecha} acompana al numero de la autorizacion del anuncio —el dominio no deja
     * que una viaje sin la otra— y {@code vigenciaDesde} dice desde cuando se declara la actividad:
     * sin ella, «este local no tiene licencia» no se puede fechar, y una fiscalizacion sin fecha no
     * se sostiene (#290, regla 9).
     */
    public record ActividadResource(
            long id,
            String conductor,
            @Nullable String nombreComercial,
            @Nullable String ciiu,
            @Nullable AreaM2 areaOcupada,
            @Nullable String licenciaNumero,
            @Nullable String licenciaFecha,
            @Nullable String anuncioNumero,
            @Nullable String anuncioFecha,
            @Nullable String vigenciaDesde) {

        public static ActividadResource de(ActividadEconomica actividad) {
            return new ActividadResource(
                    actividad.id() == null ? 0L : actividad.id(),
                    actividad.conductor(),
                    actividad.nombreComercial(),
                    actividad.ciiu(),
                    actividad.areaOcupada(),
                    actividad.licenciaNumero(),
                    actividad.licenciaFecha() == null ? null : actividad.licenciaFecha().toString(),
                    actividad.anuncioNumero(),
                    actividad.anuncioFecha() == null ? null : actividad.anuncioFecha().toString(),
                    actividad.vigenciaDesde() == null
                            ? null
                            : actividad.vigenciaDesde().toString());
        }
    }

    /** Las areas comunes de la edificacion y su reparto (RF-003). */
    public record BienesComunesResource(
            List<BienResource> bienes,
            List<ParticipacionResource> participaciones,
            AreaM2 areaComunTotal) {

        public static BienesComunesResource de(DetalleDeBienesComunes detalle) {
            return new BienesComunesResource(
                    detalle.bienes().stream().map(BienResource::de).toList(),
                    detalle.participaciones().stream().map(ParticipacionResource::de).toList(),
                    detalle.areaComunTotal());
        }
    }

    /**
     * Un area comun con su antiguedad (#290): el bien comun se valoriza como una construccion mas,
     * y de que ano es decide su depreciacion. Publicarlo sin el ano deja la fila sin la mitad de lo
     * que la explica.
     */
    public record BienResource(
            long id,
            String descripcion,
            AreaM2 area,
            @Nullable String material,
            @Nullable String estadoConservacion,
            @Nullable Integer anioConstruccion) {

        public static BienResource de(BienComun bien) {
            return new BienResource(
                    bien.id() == null ? 0L : bien.id(),
                    bien.descripcion(),
                    bien.area(),
                    bien.material() == null ? null : bien.material().name(),
                    bien.estadoConservacion() == null ? null : bien.estadoConservacion().name(),
                    bien.anioConstruccion() == null ? null : bien.anioConstruccion().valor());
        }
    }

    public record ParticipacionResource(long predioId, String porcentaje) {

        public static ParticipacionResource de(ParticipacionComun participacion) {
            return new ParticipacionResource(
                    participacion.predioId(), participacion.porcentaje().toString());
        }
    }

    /**
     * Los grupos de tierra y los colindantes (RF-004).
     *
     * <p>La superficie sale con su unidad —{@code "12.5000 HA"}— y no como numero suelto: el
     * arancel rural es por hectarea, y un cliente que interprete metros calcularia diez mil veces
     * de menos.
     */
    public record RuralResource(
            List<TierraResource> tierras,
            List<ColindanteResource> colindantes,
            String hectareasTotales) {

        public static RuralResource de(DetalleRural detalle) {
            return new RuralResource(
                    detalle.tierras().stream().map(TierraResource::de).toList(),
                    detalle.colindantes().stream().map(ColindanteResource::de).toList(),
                    detalle.hectareasTotales().toString());
        }
    }

    /**
     * Un grupo de tierra, con la superficie que le toca de las <b>areas comunes</b> del predio
     * rustico cuando la ficha la declara (#290).
     *
     * <p>Las dos van en hectareas y con su unidad dentro. {@code hectareasComunes} nulo es «esta
     * ficha no reparte area comun», que no es lo mismo que repartir cero.
     */
    public record TierraResource(
            long id,
            String clasificacion,
            @Nullable String calidadAgrologica,
            String riego,
            String hectareas,
            @Nullable String hectareasComunes) {

        public static TierraResource de(TierraRural tierra) {
            return new TierraResource(
                    tierra.id() == null ? 0L : tierra.id(),
                    tierra.clasificacion(),
                    tierra.calidadAgrologica(),
                    tierra.riego().name(),
                    tierra.hectareas().toString(),
                    tierra.hectareasComunes() == null
                            ? null
                            : tierra.hectareasComunes().toString());
        }
    }

    public record ColindanteResource(String orientacion, String descripcion) {

        public static ColindanteResource de(Colindante colindante) {
            return new ColindanteResource(
                    colindante.orientacion().name(), colindante.descripcion());
        }
    }
}
