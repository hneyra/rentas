package kamayuk.rentas.catastro.infraestructura.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.catastro.aplicacion.InscribirFicha;
import kamayuk.rentas.catastro.dominio.ActividadEconomica;
import kamayuk.rentas.catastro.dominio.BienComun;
import kamayuk.rentas.catastro.dominio.CategoriasConstructivas;
import kamayuk.rentas.catastro.dominio.Colindante;
import kamayuk.rentas.catastro.dominio.CondicionDeTitularidad;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.DetalleDeBienesComunes;
import kamayuk.rentas.catastro.dominio.DetalleDeLaFicha;
import kamayuk.rentas.catastro.dominio.DetalleEconomico;
import kamayuk.rentas.catastro.dominio.DetalleRural;
import kamayuk.rentas.catastro.dominio.EstadoDeConservacion;
import kamayuk.rentas.catastro.dominio.MaterialEstructural;
import kamayuk.rentas.catastro.dominio.Orientacion;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.OtraInstalacion;
import kamayuk.rentas.catastro.dominio.ParticipacionComun;
import kamayuk.rentas.catastro.dominio.Riego;
import kamayuk.rentas.catastro.dominio.TierraRural;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Medida;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que el cuerpo de una escritura de ficha declara, y como se lee.
 *
 * <p>Vive aparte de los dos controladores porque los dos declaran lo mismo: el alta de una ficha
 * (POST, {@link FichaController}) y su actualizacion versionada (PUT, {@link
 * ActualizacionController}) llevan las mismas construcciones, las mismas obras complementarias y el
 * mismo bloque de detalle. Escribirlo dos veces garantizaria que un dia acepten cosas distintas.
 *
 * <h2>Lista blanca, y ningun importe</h2>
 *
 * <p>Lo que no esta aqui no entra, aunque llegue en el JSON. Y lo que hay son <b>categorias, areas,
 * superficies y porcentajes</b>: ni un valor unitario, ni un arancel, ni un autovaluo (regla 5,
 * D-02a). Lo que el tecnico midio y clasifico no cambia cuando cambia el cuadro de valores, y por
 * eso recalcular 2027 en 2037 sigue siendo posible.
 *
 * <h2>Nulo es «no lo mando»; presente es «esto es»</h2>
 *
 * <p>Las listas son {@code @Nullable} a proposito: en la actualizacion, una lista ausente significa
 * «lo mismo que tenia la version vigente» y una lista <b>presente aunque vacia</b> significa
 * «ninguna». Confundir las dos vacia las construcciones, las actividades o los grupos de tierra de
 * un predio sin que ningun {@code DELETE} aparezca en el diff, que es exactamente el modo de fallo
 * que el versionado existe para evitar. En el alta no hay version anterior, asi que las dos cosas
 * son lo mismo y una lista ausente es una lista vacia.
 *
 * <p><b>El bloque de detalle se declara entero.</b> Presente, reemplaza al que la version vigente
 * tenia: una lista ausente <i>dentro</i> de un bloque presente es una lista vacia, no una copia. La
 * unidad de declaracion es el bloque, y decirlo asi evita la unica alternativa —dos niveles de
 * semantica trivaluada— que nadie podria explicar en una pantalla.
 */
public final class DeclaracionDeFicha {

    private DeclaracionDeFicha() {}

    // ── Los bloques del cuerpo ─────────────────────────────────────────

    /** Lo construido en un piso: medidas y categorias. Ningun importe (regla 5). */
    public record ConstruccionDeclarada(
            @Nullable String piso,
            @Nullable String areaConstruida,
            @Nullable Integer anioConstruccion,
            @Nullable String material,
            @Nullable String estadoConservacion,
            @Nullable String categoriaMuros,
            @Nullable String categoriaTechos,
            @Nullable String categoriaPisos,
            @Nullable String categoriaPuertas,
            @Nullable String categoriaRevestimientos,
            @Nullable String categoriaBanios,
            @Nullable String categoriaInstalaciones) {}

    /**
     * Una obra complementaria: cerco, piscina, tanque, pavimento.
     *
     * <p>La cantidad viaja <b>con su unidad</b> porque «12» no significa lo mismo en metros
     * cuadrados que en unidades, y de ese metrado sale un importe (NEG-05 §RT-005).
     */
    public record InstalacionDeclarada(
            @Nullable String descripcion,
            @Nullable String cantidad,
            @Nullable String unidad,
            @Nullable Integer anioConstruccion,
            @Nullable String estadoConservacion) {}

    /** El detalle de la ficha economica (RF-002). */
    public record EconomicoDeclarado(
            @Nullable List<ActividadDeclarada> actividades,
            @Nullable String informacionComplementaria) {}

    /** Una actividad economica, con su licencia <b>por numero</b> (catastro no depende de ella). */
    public record ActividadDeclarada(
            @Nullable String conductor,
            @Nullable String nombreComercial,
            @Nullable String ciiu,
            @Nullable String areaOcupada,
            @Nullable String licenciaNumero,
            @Nullable String licenciaFecha,
            @Nullable String anuncioNumero,
            @Nullable String anuncioFecha) {}

    /** El detalle de la ficha de bienes comunes (RF-003). */
    public record BienesComunesDeclarados(
            @Nullable List<BienDeclarado> bienes,
            @Nullable List<ParticipacionDeclarada> participaciones) {}

    /** Un area comun de la edificacion. Se valoriza como una construccion, y eso es D-02a. */
    public record BienDeclarado(
            @Nullable String descripcion,
            @Nullable String area,
            @Nullable String material,
            @Nullable String estadoConservacion,
            @Nullable Integer anioConstruccion) {}

    /**
     * Cuanto de lo comun le toca a una unidad.
     *
     * <p>La unidad entra por el {@code predioId} que la lectura ya publica en {@code
     * FichaResource.ParticipacionResource}, y no por su codigo de referencia catastral: la
     * escritura habla el mismo idioma que la lectura, y resolver aqui un codigo por unidad seria
     * una consulta por participacion <b>fuera de la transaccion</b> —sin {@code SET LOCAL}, y por
     * tanto sin RLS—, que es el defecto que la marcha blanca destapo en {@code GET /catastro/vias}.
     */
    public record ParticipacionDeclarada(@Nullable Long predioId, @Nullable String porcentaje) {}

    /** El detalle de la ficha rural (RF-004). */
    public record RuralDeclarado(
            @Nullable List<TierraDeclarada> tierras,
            @Nullable List<ColindanteDeclarado> colindantes) {}

    /**
     * Un grupo de tierra. La superficie va en <b>hectareas</b>: el arancel rural es por hectarea.
     */
    public record TierraDeclarada(
            @Nullable String clasificacion,
            @Nullable String calidadAgrologica,
            @Nullable String riego,
            @Nullable String hectareas,
            @Nullable String hectareasComunes) {}

    /** Con quien linda el predio rustico por una orientacion. */
    public record ColindanteDeclarado(@Nullable String orientacion, @Nullable String descripcion) {}

    /** El titular inicial del predio, por su codigo del padron. */
    public record TitularDeclarado(
            @Nullable String codigoContribuyente,
            @Nullable String condicion,
            @Nullable String porcentaje,
            @Nullable String documentoOrigen) {}

    // ── Como se lee ────────────────────────────────────────────────────

    /**
     * La observacion del usuario, obligatoria en toda escritura (regla 10, RNF-052).
     *
     * <p>Sin ella la peticion es {@code 422} y no se guarda nada: ni la ficha, ni el predio, ni la
     * titularidad.
     */
    public static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda modificacion exige la observacion del usuario: sin ella no se guarda");
        }
        try {
            return Observacion.de(texto);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    public static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    public static @Nullable String vacioANulo(@Nullable String valor) {
        return valor == null || valor.isBlank() ? null : valor.strip();
    }

    /**
     * El codigo de referencia catastral, con su composicion validada tramo por tramo.
     *
     * <p>Vive aqui porque lo analizan los tres controladores de ficha —el alta, la actualizacion y
     * la baja del predio— y un codigo mal compuesto tiene que responder lo mismo en los tres.
     *
     * <p>Valida <b>sin tocar la base</b>. Desde #486 la usa tambien la lectura: la consulta del
     * predio vive en {@code ConsultaDeLaFichaVigente}, dentro de su transaccion.
     */
    public static CodigoReferenciaCatastral referenciaDe(String codigo) {
        try {
            return CodigoReferenciaCatastral.de(codigo);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalido));
        }
    }

    /**
     * Nulo significa «lo mismo que tenia»; una lista presente, aunque vacia, reemplaza.
     *
     * <p>Esta es la linea que separa «no toque las construcciones» de «este predio ya no tiene
     * ninguna».
     */
    public static @Nullable List<Construccion> construccionesDe(
            @Nullable List<ConstruccionDeclarada> declaradas) {
        if (declaradas == null) {
            return null;
        }
        List<Construccion> construcciones = new ArrayList<>();
        for (ConstruccionDeclarada declarada : declaradas) {
            construcciones.add(
                    new Construccion(
                            null,
                            null,
                            exigir(declarada.piso(), "piso"),
                            areaDe(declarada.areaConstruida(), "areaConstruida"),
                            ejercicioDe(declarada.anioConstruccion()),
                            valorDe(MaterialEstructural.class, declarada.material(), "material"),
                            valorDe(
                                    EstadoDeConservacion.class,
                                    declarada.estadoConservacion(),
                                    "estadoConservacion"),
                            categoriasDe(declarada),
                            null));
        }
        return List.copyOf(construcciones);
    }

    /** Igual que las construcciones: nulo copia, lista presente reemplaza. */
    public static @Nullable List<OtraInstalacion> instalacionesDe(
            @Nullable List<InstalacionDeclarada> declaradas) {
        if (declaradas == null) {
            return null;
        }
        List<OtraInstalacion> instalaciones = new ArrayList<>();
        for (InstalacionDeclarada declarada : declaradas) {
            instalaciones.add(
                    new OtraInstalacion(
                            null,
                            null,
                            exigir(declarada.descripcion(), "descripcion"),
                            medidaDe(declarada.cantidad(), declarada.unidad()),
                            ejercicioDe(declarada.anioConstruccion()),
                            valorDe(
                                    EstadoDeConservacion.class,
                                    declarada.estadoConservacion(),
                                    "estadoConservacion")));
        }
        return List.copyOf(instalaciones);
    }

    /**
     * El detalle propio del tipo de la ficha, o nulo si el cuerpo no lo declara.
     *
     * <p><b>Un bloque que no es del tipo se rechaza, no se ignora.</b> Ignorarlo seria lo comodo y
     * lo peor: el tecnico declara actividades economicas en una ficha rural, el sistema responde
     * {@code 201}, y lo declarado no esta en ningun sitio. El constructor de {@code FichaCatastral}
     * tambien lo rechaza, pero solo si el detalle llega hasta el; esta comprobacion es la que
     * impide que se pierda por el camino.
     */
    public static @Nullable DetalleDeLaFicha detalleDe(
            TipoFicha tipo,
            @Nullable EconomicoDeclarado economico,
            @Nullable BienesComunesDeclarados bienesComunes,
            @Nullable RuralDeclarado rural) {

        exigirDelTipo(tipo, TipoFicha.ECONOMICA, economico, "economico");
        exigirDelTipo(tipo, TipoFicha.BIENES_COMUNES, bienesComunes, "bienesComunes");
        exigirDelTipo(tipo, TipoFicha.RURAL, rural, "rural");

        if (economico != null) {
            return new DetalleEconomico(
                    actividadesDe(economico.actividades()),
                    vacioANulo(economico.informacionComplementaria()));
        }
        if (bienesComunes != null) {
            return new DetalleDeBienesComunes(
                    bienesDe(bienesComunes.bienes()),
                    participacionesDe(bienesComunes.participaciones()));
        }
        if (rural != null) {
            return new DetalleRural(tierrasDe(rural.tierras()), colindantesDe(rural.colindantes()));
        }
        return null;
    }

    /** El titular inicial, si el cuerpo lo declara. */
    public static InscribirFicha.@Nullable DatosDelTitular titularDe(
            @Nullable TitularDeclarado declarado) {
        if (declarado == null) {
            return null;
        }
        CondicionDeTitularidad condicion =
                valorDe(CondicionDeTitularidad.class, declarado.condicion(), "condicion");
        if (condicion == null) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Falta el campo 'titular.condicion'");
        }
        return new InscribirFicha.DatosDelTitular(
                exigir(declarado.codigoContribuyente(), "titular.codigoContribuyente"),
                condicion,
                porcentajeDe(declarado.porcentaje()),
                exigir(declarado.documentoOrigen(), "titular.documentoOrigen"));
    }

    /** Sin origen declarado, el caso normal de estas pantallas: el contribuyente declara. */
    public static OrigenDeLaFicha origenDe(@Nullable String texto) {
        OrigenDeLaFicha origen = valorDe(OrigenDeLaFicha.class, texto, "origen");
        return origen == null ? OrigenDeLaFicha.DECLARACION_JURADA : origen;
    }

    public static LocalDate fechaDe(String texto, String campo) {
        try {
            return LocalDate.parse(texto);
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    public static AreaM2 areaDe(@Nullable String texto, String campo) {
        try {
            return new AreaM2(new BigDecimal(exigir(texto, campo)));
            // NumberFormatException es una IllegalArgumentException, asi que un multi-catch
            // con las dos no compila: la segunda ya cubre a la primera.
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El campo '" + campo + "' no es un area valida");
        }
    }

    /** El mensaje de una excepcion es {@code @Nullable}; aqui nunca lo es, pero decirlo cuesta. */
    public static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }

    // ------------------------------------------------------------------

    private static void exigirDelTipo(
            TipoFicha tipo, TipoFicha delBloque, @Nullable Object bloque, String nombre) {
        if (bloque != null && tipo != delBloque) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Una ficha "
                            + tipo
                            + " no lleva el bloque '"
                            + nombre
                            + "': es el detalle de una ficha "
                            + delBloque
                            + ", que es otra ficha del mismo predio");
        }
    }

    private static List<ActividadEconomica> actividadesDe(
            @Nullable List<ActividadDeclarada> declaradas) {
        if (declaradas == null) {
            return List.of();
        }
        List<ActividadEconomica> actividades = new ArrayList<>();
        for (ActividadDeclarada declarada : declaradas) {
            actividades.add(
                    new ActividadEconomica(
                            null,
                            null,
                            exigir(declarada.conductor(), "conductor"),
                            vacioANulo(declarada.nombreComercial()),
                            vacioANulo(declarada.ciiu()),
                            areaOpcionalDe(declarada.areaOcupada(), "areaOcupada"),
                            vacioANulo(declarada.licenciaNumero()),
                            fechaOpcionalDe(declarada.licenciaFecha(), "licenciaFecha"),
                            vacioANulo(declarada.anuncioNumero()),
                            fechaOpcionalDe(declarada.anuncioFecha(), "anuncioFecha"),
                            null));
        }
        return List.copyOf(actividades);
    }

    private static List<BienComun> bienesDe(@Nullable List<BienDeclarado> declarados) {
        if (declarados == null) {
            return List.of();
        }
        List<BienComun> bienes = new ArrayList<>();
        for (BienDeclarado declarado : declarados) {
            bienes.add(
                    new BienComun(
                            null,
                            null,
                            exigir(declarado.descripcion(), "descripcion"),
                            areaDe(declarado.area(), "area"),
                            valorDe(MaterialEstructural.class, declarado.material(), "material"),
                            valorDe(
                                    EstadoDeConservacion.class,
                                    declarado.estadoConservacion(),
                                    "estadoConservacion"),
                            ejercicioDe(declarado.anioConstruccion())));
        }
        return List.copyOf(bienes);
    }

    private static List<ParticipacionComun> participacionesDe(
            @Nullable List<ParticipacionDeclarada> declaradas) {
        if (declaradas == null) {
            return List.of();
        }
        List<ParticipacionComun> participaciones = new ArrayList<>();
        for (ParticipacionDeclarada declarada : declaradas) {
            Long predioId = declarada.predioId();
            if (predioId == null) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION,
                        "Falta el campo 'predioId' de una participacion: sin la unidad no hay"
                                + " reparto");
            }
            Porcentaje porcentaje = porcentajeDe(declarada.porcentaje());
            if (porcentaje == null) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION,
                        "Falta el campo 'porcentaje' de la participacion del predio " + predioId);
            }
            participaciones.add(ParticipacionComun.de(predioId, porcentaje));
        }
        return List.copyOf(participaciones);
    }

    private static List<TierraRural> tierrasDe(@Nullable List<TierraDeclarada> declaradas) {
        if (declaradas == null) {
            return List.of();
        }
        List<TierraRural> tierras = new ArrayList<>();
        for (TierraDeclarada declarada : declaradas) {
            Riego riego = valorDe(Riego.class, declarada.riego(), "riego");
            tierras.add(
                    new TierraRural(
                            null,
                            null,
                            exigir(declarada.clasificacion(), "clasificacion"),
                            vacioANulo(declarada.calidadAgrologica()),
                            riego == null ? Riego.SECANO : riego,
                            hectareasDe(exigir(declarada.hectareas(), "hectareas")),
                            declarada.hectareasComunes() == null
                                    ? null
                                    : hectareasDe(declarada.hectareasComunes())));
        }
        return List.copyOf(tierras);
    }

    private static List<Colindante> colindantesDe(@Nullable List<ColindanteDeclarado> declarados) {
        if (declarados == null) {
            return List.of();
        }
        List<Colindante> colindantes = new ArrayList<>();
        for (ColindanteDeclarado declarado : declarados) {
            Orientacion orientacion =
                    valorDe(Orientacion.class, declarado.orientacion(), "orientacion");
            if (orientacion == null) {
                throw new ProblemaDeNegocio(
                        CodigoDeError.VALIDACION, "Falta el campo 'orientacion' de un colindante");
            }
            colindantes.add(
                    Colindante.por(orientacion, exigir(declarado.descripcion(), "descripcion")));
        }
        return List.copyOf(colindantes);
    }

    /**
     * La superficie rural, siempre en hectareas.
     *
     * <p>La unidad no viaja en el cuerpo a proposito: {@code TierraRural} solo admite {@code HA}
     * —el arancel rural se publica por hectarea— y dejar elegir la unidad seria ofrecer la unica
     * eleccion equivocada. 15 000 metros leidos como hectareas valorizan diez mil veces de mas.
     */
    private static Medida hectareasDe(String cuantas) {
        try {
            return TierraRural.enHectareas(cuantas);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "La superficie en hectareas no es un numero valido: '" + cuantas + "'");
        }
    }

    private static Medida medidaDe(@Nullable String cantidad, @Nullable String unidad) {
        try {
            return Medida.de(exigir(cantidad, "cantidad"), exigir(unidad, "unidad"));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    private static CategoriasConstructivas categoriasDe(ConstruccionDeclarada declarada) {
        try {
            return new CategoriasConstructivas(
                    letra(declarada.categoriaMuros()),
                    letra(declarada.categoriaTechos()),
                    letra(declarada.categoriaPisos()),
                    letra(declarada.categoriaPuertas()),
                    letra(declarada.categoriaRevestimientos()),
                    letra(declarada.categoriaBanios()),
                    letra(declarada.categoriaInstalaciones()));
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    private static @Nullable Character letra(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String limpio = texto.strip();
        if (limpio.length() != 1) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "Una categoria es una sola letra: '" + texto + "'");
        }
        return Character.toUpperCase(limpio.charAt(0));
    }

    private static @Nullable Ejercicio ejercicioDe(@Nullable Integer anio) {
        if (anio == null) {
            return null;
        }
        try {
            return new Ejercicio(anio);
        } catch (IllegalArgumentException fueraDeRango) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(fueraDeRango));
        }
    }

    private static @Nullable Porcentaje porcentajeDe(@Nullable String texto) {
        String limpio = vacioANulo(texto);
        if (limpio == null) {
            return null;
        }
        try {
            return Porcentaje.de(limpio);
        } catch (IllegalArgumentException invalido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION, "El porcentaje no es valido: '" + texto + "'");
        }
    }

    private static @Nullable AreaM2 areaOpcionalDe(@Nullable String texto, String campo) {
        return vacioANulo(texto) == null ? null : areaDe(texto, campo);
    }

    private static @Nullable LocalDate fechaOpcionalDe(@Nullable String texto, String campo) {
        String limpio = vacioANulo(texto);
        return limpio == null ? null : fechaDe(limpio, campo);
    }

    /**
     * Un enumerado por su nombre, o nulo si no viene.
     *
     * <p>Un nombre desconocido es {@code 422} y no se traga en silencio: {@code "REGULAR "} con un
     * espacio o {@code "bueno"} en minusculas se aceptan —se normaliza—, pero {@code "MEDIO"} no
     * existe y guardarlo como nulo dejaria un estado de conservacion sin declarar que despues nadie
     * sabria distinguir del que de verdad no se midio.
     */
    private static <E extends Enum<E>> @Nullable E valorDe(
            Class<E> tipo, @Nullable String texto, String campo) {
        String limpio = vacioANulo(texto);
        if (limpio == null) {
            return null;
        }
        try {
            return Enum.valueOf(tipo, limpio.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' no admite el valor '" + texto + "'");
        }
    }
}
