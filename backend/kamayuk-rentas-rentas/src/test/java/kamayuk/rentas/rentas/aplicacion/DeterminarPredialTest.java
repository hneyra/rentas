package kamayuk.rentas.rentas.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import kamayuk.rentas.rentas.dominio.EstadoDeDeterminacion;
import kamayuk.rentas.rentas.dominio.predial.AporteDeTramo;
import kamayuk.rentas.rentas.dominio.predial.CuotaDelPredial;
import kamayuk.rentas.rentas.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.rentas.dominio.predial.Determinacion;
import kamayuk.rentas.rentas.dominio.predial.DeterminacionPredialCalculada;
import kamayuk.rentas.rentas.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.rentas.dominio.predial.PredioEnLaBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El calculo individual del predial (#395), con dobles y sin base de datos.
 *
 * <p>Lo que este archivo defiende:
 *
 * <ul>
 *   <li><b>La base es del contribuyente</b> (NEG-05 §1): los tramos se aplican una sola vez sobre
 *       la suma ponderada, y la prueba compara contra lo que saldria calculando predio por predio
 *       —que es siempre menos, y esa diferencia no la delata ninguna cifra—.
 *   <li><b>El % de propiedad sale del padron</b>, no de la peticion: no hay campo por donde
 *       mandarlo.
 *   <li><b>Ninguna cifra tributaria se inventa</b>: sin la llave, falla nombrandola.
 *   <li><b>Simular no asienta</b>: no se inserta ninguna fila ni se audita nada.
 * </ul>
 */
@DisplayName("#395 — Determinar el predial de un contribuyente")
class DeterminarPredialTest {

    private static final Clock RELOJ =
            Clock.fixed(Instant.parse("2026-08-29T10:00:00Z"), ZoneId.of("America/Lima"));
    private static final Ejercicio EJERCICIO = new Ejercicio(2026);
    private static final Observacion PORQUE =
            Observacion.de("Emision ordinaria del ejercicio, a pedido del contribuyente");

    private DeterminacionesEnMemoria determinaciones;
    private PrediosDePrueba predios;
    private AuditoriaDePrueba auditoria;

    @BeforeEach
    void preparar() {
        determinaciones = new DeterminacionesEnMemoria();
        predios = new PrediosDePrueba();
        auditoria = new AuditoriaDePrueba();
    }

    @Test
    @DisplayName("la base es del contribuyente: los tramos corren una vez sobre la suma ponderada")
    void laBaseEsDelContribuyente() {
        // Dos predios de 100 000,00 al 100 %: base 200 000,00. Con el cuadro del articulo 13 y la
        // UIT de 2026 (5 500,00), el primer tramo llega a 82 500,00 y el segundo a 330 000,00.
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        predios.con(22L, "10002", "JR. LIMA 250", Porcentaje.total());

        DeterminacionPredialCalculada calculada =
                determinar(declarado(11L, "100000.00"), declarado(22L, "100000.00"));

        assertThat(calculada.cabecera().baseImponible()).isEqualTo(Dinero.de("200000.00"));
        // 82 500 x 0.2 % = 165.00 ; 117 500 x 0.6 % = 705.00 ; total 870.00
        assertThat(calculada.impuestoInsoluto()).isEqualTo(Dinero.de("870.00"));

        // Calculado predio por predio saldria 2 x (82 500 x 0.2 % + 17 500 x 0.6 %) = 2 x 270.00 =
        // 540.00: 330,00 menos, y ninguna cifra del recibo lo diria.
        assertThat(calculada.impuestoInsoluto()).isNotEqualTo(Dinero.de("540.00"));
    }

    @Test
    @DisplayName("#690 — se determina igual sobre una titularidad incompleta, y se DICE")
    void laTitularidadIncompletaSeDetermina() {
        // El caso de Catacaos: el contribuyente tiene el 60 % y nadie tiene el 40 % restante.
        predios.conTitularidadIncompleta(
                11L, "10001", "AV. GRAU 100", Porcentaje.de("60"), Porcentaje.de("60"));

        DeterminacionPredialCalculada calculada = determinar(declarado(11L, "100000.00"), null);

        PredioEnLaBase enLaBase = calculada.predios().get(0);
        assertThat(enLaBase.baseImponiblePredio())
                .as(
                        "se determina igual: la cifra es correcta PARA LO REGISTRADO, y no"
                                + " determinar dejaria sin emitir a un tercio del padron")
                .isEqualTo(Dinero.de("60000.00"));
        assertThat(enLaBase.titularidadCompleta())
                .as(
                        "y sale dicho: una base ponderada por una titularidad que no cubre el"
                                + " predio no se distingue de una correcta si nada la acompaña —"
                                + " quien la lee ve «60 %» y entiende que ese es el porcentaje del"
                                + " contribuyente, no que nadie tiene el otro 40 %")
                .isFalse();
        assertThat(enLaBase.porcentajeRegistradoDelPredio()).isEqualTo(Porcentaje.de("60"));
    }

    @Test
    @DisplayName("#690 — y el predio con dueño completo no dice nada, que es el contraste")
    void elPredioCompletoNoDiceNada() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.de("50"));

        PredioEnLaBase enLaBase = determinar(declarado(11L, "100000.00"), null).predios().get(0);

        assertThat(enLaBase.titularidadCompleta())
                .as(
                        "tener el 50 % de un predio con dueño completo es lo corriente: si esto"
                                + " tambien avisara, el aviso no distinguiria nada")
                .isTrue();
    }

    @Test
    @DisplayName("#659 — lo que cuesta al centimo determinar 2026 con la UIT de otro año")
    void loQueCuestaLaUitEquivocada() {
        // El caso que #659 midio contra el compose: un solo predio, 85 000,00 al 100 %.
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        // Con la UIT que toca —5 500,00, la del ejercicio 2026— el primer tramo llega a 82 500,00:
        // 82 500 x 0.2 % = 165.00 ; 2 500 x 0.6 % = 15.00 ; total 180.00
        assertThat(determinar(declarado(11L, "85000.00"), null).impuestoInsoluto())
                .isEqualTo(Dinero.de("180.00"));

        // Con la UIT de 2022 —4 600,00, que es la que sobrevivia al defecto del lector— el primer
        // tramo llega a 69 000,00: 69 000 x 0.2 % = 138.00 ; 16 000 x 0.6 % = 96.00 ; total 234.00.
        // Un 30 % de mas sobre el mismo autovaluo, a todo el padron y sin ningun error de por
        // medio; ninguna cifra del recibo lo diria. Quien elige la vigencia es
        // LectorDeParametrosSellados, y que elija la correcta lo demuestra
        // LectorDeParametrosSelladosTest contra PostgreSQL: esta prueba fija lo que esa eleccion
        // vale en soles.
        determinaciones = new DeterminacionesEnMemoria();
        List<DeterminarPredial.PredioDeclarado> uno = new ArrayList<>();
        uno.add(declarado(11L, "85000.00"));
        DeterminacionPredialCalculada conLaDeOtroAnio =
                servicioCon(
                                conjunto()
                                        .numero("UIT", null, ValorNormativo.de("4600.00"))
                                        .construir())
                        .determinar(
                                new DeterminarPredial.Peticion(
                                        EJERCICIO, "C-001", uno, "TRIMESTRAL", false),
                                PORQUE);
        assertThat(conLaDeOtroAnio.impuestoInsoluto()).isEqualTo(Dinero.de("234.00"));
    }

    @Test
    @DisplayName("el % de propiedad sale del padron y pondera el aporte de cada predio")
    void elPorcentajePondera() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.de("50"));
        predios.con(22L, "10002", "JR. LIMA 250", Porcentaje.de("25"));

        DeterminacionPredialCalculada calculada =
                determinar(declarado(11L, "100000.00"), declarado(22L, "80000.00"));

        List<PredioEnLaBase> enLaBase = calculada.predios();
        assertThat(enLaBase.get(0).baseImponiblePredio()).isEqualTo(Dinero.de("50000.00"));
        assertThat(enLaBase.get(1).baseImponiblePredio()).isEqualTo(Dinero.de("20000.00"));
        assertThat(calculada.cabecera().baseImponible()).isEqualTo(Dinero.de("70000.00"));
        // El valuo total no se pondera: es la suma de los autovaluos, y por eso no coincide con la
        // base. Recomponer una desde la otra en la interfaz daria una cifra parecida (RNF-083).
        assertThat(calculada.valuoTotal()).isEqualTo(Dinero.de("180000.00"));
    }

    @Test
    @DisplayName("la parte exonerada sale de la base y viaja en el detalle que se guarda")
    void laParteExoneradaSaleDeLaBase() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        DeterminacionPredialCalculada calculada =
                determinar(
                        new DeterminarPredial.PredioDeclarado(
                                11L, Dinero.de("100000.00"), Dinero.de("30000.00")),
                        null);

        assertThat(calculada.valuoAfecto()).isEqualTo(Dinero.de("70000.00"));
        assertThat(calculada.cabecera().baseImponible()).isEqualTo(Dinero.de("70000.00"));
        assertThat(determinaciones.detalleGuardado.get(0).valuoExonerado())
                .isEqualTo(Dinero.de("30000.00"));
    }

    @Test
    @DisplayName("un predio del contribuyente sin autovaluo declarado no se determina, se nombra")
    void sinAutovaluoNoSeDetermina() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        predios.con(22L, "10002", "JR. LIMA 250", Porcentaje.total());

        assertThatThrownBy(() -> determinar(declarado(11L, "100000.00"), null))
                .isInstanceOf(DeterminarPredial.PredioSinAutovaluo.class)
                .hasMessageContaining("10002")
                .hasMessageContaining("D-11");
    }

    @Test
    @DisplayName("un predio que no es del contribuyente se rechaza: la titularidad es del padron")
    void unPredioAjenoSeRechaza() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        assertThatThrownBy(
                        () -> determinar(declarado(11L, "100000.00"), declarado(99L, "50000.00")))
                .isInstanceOf(DeterminarPredial.PredioAjeno.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("simular no inserta ninguna fila ni deja rastro de auditoria")
    void simularNoAsienta() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        DeterminacionPredialCalculada calculada =
                servicio()
                        .determinar(
                                new DeterminarPredial.Peticion(
                                        EJERCICIO,
                                        "C-001",
                                        List.of(declarado(11L, "100000.00")),
                                        "TRIMESTRAL",
                                        true),
                                PORQUE);

        assertThat(calculada.esSimulacion()).isTrue();
        assertThat(calculada.cabecera().id()).isNull();
        assertThat(determinaciones.insertadas).isZero();
        assertThat(auditoria.registros).isEmpty();
        // Y aun asi la respuesta esta completa: los tramos, las cuotas y el conjunto que uso.
        assertThat(calculada.tramos()).isNotEmpty();
        assertThat(calculada.cuotas()).hasSize(4);
        assertThat(calculada.nombreDelConjunto()).isEqualTo("2026 v1");
    }

    @Test
    @DisplayName("asentar inserta la determinacion y la audita con la observacion del usuario")
    void asentarGuardaYAudita() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        DeterminacionPredialCalculada calculada = determinar(declarado(11L, "100000.00"), null);

        assertThat(calculada.esSimulacion()).isFalse();
        assertThat(determinaciones.insertadas).isEqualTo(1);
        assertThat(auditoria.registros).hasSize(1);
        assertThat(auditoria.registros.get(0).observacion()).isEqualTo(PORQUE);
        assertThat(calculada.cabecera().conjuntoId()).isEqualTo(77L);
    }

    @Test
    @DisplayName("toda la respuesta dice a que fecha y con que conjunto esta calculada")
    void laRespuestaDiceCuandoYConQue() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        DeterminacionPredialCalculada calculada = determinar(declarado(11L, "100000.00"), null);

        assertThat(calculada.fechaCalculo()).isEqualTo(LocalDate.parse("2026-08-29"));
        assertThat(calculada.nombreDelConjunto()).isEqualTo("2026 v1");
        assertThat(calculada.cabecera().conjuntoId()).isEqualTo(77L);
        assertThat(calculada.uit()).isEqualTo(Dinero.de("5500.00"));
    }

    @Test
    @DisplayName("el desglose de tramos y el cronograma llegan hechos, no para recomponer")
    void laMemoriaLlegaHecha() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        DeterminacionPredialCalculada calculada = determinar(declarado(11L, "100000.00"), null);

        List<AporteDeTramo> tramos = calculada.tramos();
        assertThat(tramos).hasSize(2);
        assertThat(tramos.get(0).limiteSuperior()).isEqualTo(Dinero.de("82500.00"));
        assertThat(tramos.get(0).aporte()).isEqualTo(Dinero.de("165.00"));
        assertThat(tramos.get(1).porcionGravada()).isEqualTo(Dinero.de("17500.00"));

        List<CuotaDelPredial> cuotas = calculada.cuotas();
        assertThat(cuotas).hasSize(4);
        assertThat(cuotas.get(0).vencimiento()).isEqualTo(LocalDate.parse("2026-02-27"));
        assertThat(calculada.derechoDeEmision()).isEqualTo(Dinero.de("4.50"));
        assertThat(calculada.totalAPagar())
                .isEqualTo(calculada.impuestoInsoluto().mas(Dinero.de("4.50")));
    }

    @Test
    @DisplayName("sin el derecho de emision del conjunto no se determina: no hay cifra por omision")
    void sinDerechoDeEmisionNoSeDetermina() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        DeterminarPredial servicio = servicioCon(conjuntoSinDerechoDeEmision());

        assertThatThrownBy(
                        () ->
                                servicio.determinar(
                                        new DeterminarPredial.Peticion(
                                                EJERCICIO,
                                                "C-001",
                                                List.of(declarado(11L, "100000.00")),
                                                "TRIMESTRAL",
                                                true),
                                        PORQUE))
                .isInstanceOf(ParametrosSellados.ParametroAusente.class)
                .hasMessageContaining("DERECHO_EMISION_PREDIAL");
    }

    @Test
    @DisplayName("sin predios declarados se toman los del mismo ejercicio, nunca los del anterior")
    void reutilizaLosAutovaluosDelMismoEjercicio() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
        determinaciones.sembrarDelEjercicio(
                EJERCICIO,
                7L,
                DetalleDeterminacionPredio.nuevo(
                        11L,
                        Dinero.de("100000.00"),
                        Dinero.CERO,
                        Porcentaje.total(),
                        Dinero.de("100000.00")));

        DeterminacionPredialCalculada calculada =
                servicio()
                        .determinar(
                                new DeterminarPredial.Peticion(
                                        EJERCICIO, "C-001", List.of(), "TRIMESTRAL", true),
                                PORQUE);

        assertThat(calculada.cabecera().baseImponible()).isEqualTo(Dinero.de("100000.00"));
        assertThat(calculada.predios().get(0).autovaluo()).isEqualTo(Dinero.de("100000.00"));
    }

    @Test
    @DisplayName("sin predios declarados y sin nada declarado antes, se nombra el predio que falta")
    void sinNadaDeclaradoSeNombraElPredio() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        assertThatThrownBy(
                        () ->
                                servicio()
                                        .determinar(
                                                new DeterminarPredial.Peticion(
                                                        EJERCICIO,
                                                        "C-001",
                                                        List.of(),
                                                        "TRIMESTRAL",
                                                        true),
                                                PORQUE))
                .isInstanceOf(DeterminarPredial.PredioSinAutovaluo.class)
                .hasMessageContaining("10001");
    }

    @Test
    @DisplayName("un contribuyente sin predios en el padron no tiene determinacion")
    void sinPrediosNoHayDeterminacion() {
        assertThatThrownBy(() -> determinar(declarado(11L, "100000.00"), null))
                .isInstanceOf(DeterminarPredial.SinPrediosEnElPadron.class);
    }

    @Test
    @DisplayName("un codigo que no esta en el padron de contribuyentes no se determina")
    void contribuyenteInexistente() {
        assertThatThrownBy(
                        () ->
                                servicio()
                                        .determinar(
                                                new DeterminarPredial.Peticion(
                                                        EJERCICIO,
                                                        "NO-EXISTE",
                                                        List.of(),
                                                        "TRIMESTRAL",
                                                        true),
                                                PORQUE))
                .isInstanceOf(DeterminarPredial.ContribuyenteInexistente.class);
    }

    // ---------------------------------------------------------------- utilidades

    private DeterminacionPredialCalculada determinar(
            DeterminarPredial.PredioDeclarado uno, DeterminarPredial.PredioDeclarado otro) {
        List<DeterminarPredial.PredioDeclarado> declarados = new ArrayList<>();
        declarados.add(uno);
        if (otro != null) {
            declarados.add(otro);
        }
        return servicio()
                .determinar(
                        new DeterminarPredial.Peticion(
                                EJERCICIO, "C-001", declarados, "TRIMESTRAL", false),
                        PORQUE);
    }

    private static DeterminarPredial.PredioDeclarado declarado(long predioId, String autovaluo) {
        return new DeterminarPredial.PredioDeclarado(predioId, Dinero.de(autovaluo), null);
    }

    private DeterminarPredial servicio() {
        return servicioCon(conjunto().construir());
    }

    private DeterminarPredial servicioCon(ParametrosSellados sellados) {
        LectorDeParametros lector = lector(sellados);
        return new DeterminarPredial(
                new PadronPredialDelEjercicio(determinaciones),
                predios,
                new SinCaracteristicas(),
                new DirectorioDePrueba(),
                new CuadroPredialParametrizado(lector),
                new RegistrarDeterminacionPredial(determinaciones, lector, auditoria, RELOJ),
                RELOJ);
    }

    private static ParametrosSellados.Constructor conjunto() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("UIT", null, ValorNormativo.de("5500.00"))
                .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.6"))
                .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60"))
                .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.0"))
                .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.50"))
                .texto("PREDIAL_VENCIMIENTO", "1", "2026-02-27")
                .texto("PREDIAL_VENCIMIENTO", "2", "2026-05-29")
                .texto("PREDIAL_VENCIMIENTO", "3", "2026-08-31")
                .texto("PREDIAL_VENCIMIENTO", "4", "2026-11-30")
                .numero("REDONDEO", "IMPUESTO_POR_TRAMO", ValorNormativo.de("2"))
                .texto("REDONDEO", "IMPUESTO_POR_TRAMO", "HALF_UP")
                .numero("REDONDEO", "BASE_DEL_CONTRIBUYENTE", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_DEL_CONTRIBUYENTE", "HALF_UP")
                .numero("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", ValorNormativo.de("2"))
                .texto("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", "HALF_UP")
                .numero("REDONDEO", "CUOTA", ValorNormativo.de("2"))
                .texto("REDONDEO", "CUOTA", "HALF_UP");
    }

    private static ParametrosSellados conjuntoSinDerechoDeEmision() {
        ParametrosSellados.Constructor sin =
                ParametrosSellados.de(EJERCICIO, 1)
                        .numero("UIT", null, ValorNormativo.de("5500.00"))
                        .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                        .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                        .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("1.0"))
                        .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                        .numero("REDONDEO", "IMPUESTO_POR_TRAMO", ValorNormativo.de("2"))
                        .texto("REDONDEO", "IMPUESTO_POR_TRAMO", "HALF_UP")
                        .numero("REDONDEO", "BASE_DEL_CONTRIBUYENTE", ValorNormativo.de("2"))
                        .texto("REDONDEO", "BASE_DEL_CONTRIBUYENTE", "HALF_UP")
                        .numero("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", ValorNormativo.de("2"))
                        .texto("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", "HALF_UP")
                        .numero("REDONDEO", "CUOTA", ValorNormativo.de("2"))
                        .texto("REDONDEO", "CUOTA", "HALF_UP");
        return sin.construir();
    }

    private static LectorDeParametros lector(ParametrosSellados sellados) {
        return new LectorDeParametros() {
            @Override
            public ParametrosSellados vigenteEn(Ejercicio ejercicio) {
                return sellados;
            }

            @Override
            public ParametrosSellados porConjunto(IdentificadorDeConjunto identificador) {
                return sellados;
            }

            @Override
            public IdentificadorDeConjunto conjuntoVigenteEn(Ejercicio ejercicio) {
                return IdentificadorDeConjunto.de(77L);
            }
        };
    }

    // ---------------------------------------------------------------- dobles

    private static final class PrediosDePrueba implements PrediosDelContribuyente {

        private final List<PredioDelContribuyente> suyos = new ArrayList<>();

        void con(long predioId, String codigo, String direccion, Porcentaje cuota) {
            // Sin decir otra cosa, el predio tiene dueño completo: la cuota del contribuyente ES
            // todo lo registrado.
            suyos.add(new PredioDelContribuyente(predioId, codigo, "URBANO", direccion, cuota));
        }

        /** Un predio cuyas cuotas NO cubren el predio entero (#690). */
        void conTitularidadIncompleta(
                long predioId,
                String codigo,
                String direccion,
                Porcentaje cuota,
                Porcentaje registrado) {
            suyos.add(
                    new PredioDelContribuyente(
                            predioId, codigo, "URBANO", direccion, cuota, registrado));
        }

        @Override
        public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
            return List.copyOf(suyos);
        }
    }

    /** Sin ficha catastral: el uso sale nulo, y la determinacion se hace igual. */
    private static final class SinCaracteristicas implements LectorDeCaracteristicas {
        @Override
        public Optional<CaracteristicasDelPredio> de(long predioId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    private static final class DirectorioDePrueba implements DirectorioDeContribuyentes {

        private static final ResumenDeContribuyente UNO =
                new ResumenDeContribuyente(501L, "C-001", "SUC. RUFINA MEDINA MEDINA", "03593174");

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("La determinacion no busca por texto");
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return "C-001".equals(codigo) ? Optional.of(UNO) : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> encontrados = new LinkedHashMap<>();
            if (ids.contains(UNO.id())) {
                encontrados.put(UNO.id(), UNO);
            }
            return encontrados;
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    private static final class DeterminacionesEnMemoria implements DeterminacionRepository {

        private int insertadas;
        private List<DetalleDeterminacionPredio> detalleGuardado = List.of();
        private final Map<Long, List<DetalleDeterminacionPredio>> detallePorId =
                new LinkedHashMap<>();
        private final List<Determinacion> cabeceras = new ArrayList<>();

        void sembrarDelEjercicio(
                Ejercicio ejercicio, long id, DetalleDeterminacionPredio... detalle) {
            cabeceras.add(
                    new Determinacion(
                            id,
                            ejercicio,
                            "PREDIAL",
                            null,
                            501L,
                            null,
                            null,
                            77L,
                            Dinero.de("1.00"),
                            Dinero.de("1.00"),
                            List.of("RT-011"),
                            kamayuk.rentas.rentas.dominio.OrigenDeDeterminacion.ORDINARIA,
                            EstadoDeDeterminacion.BORRADOR,
                            "siembra"));
            detallePorId.put(id, List.of(detalle));
        }

        @Override
        public Optional<Determinacion> findById(long id) {
            return cabeceras.stream().filter(c -> Long.valueOf(id).equals(c.id())).findFirst();
        }

        @Override
        public List<Determinacion> ultimasPredialesDe(Ejercicio ejercicio) {
            return List.copyOf(cabeceras);
        }

        @Override
        public Optional<Determinacion> ultimaPredialDe(Ejercicio ejercicio, long contribuyenteId) {
            return cabeceras.stream()
                    .filter(c -> c.contribuyenteId() == contribuyenteId)
                    .reduce((primera, segunda) -> segunda);
        }

        @Override
        public List<DetalleDeterminacionPredio> detalleDe(long determinacionId) {
            return detallePorId.getOrDefault(determinacionId, List.of());
        }

        @Override
        public Determinacion insertar(
                Determinacion determinacion, List<DetalleDeterminacionPredio> detalle) {
            insertadas++;
            detalleGuardado = List.copyOf(detalle);
            return new Determinacion(
                    900L + insertadas,
                    determinacion.ejercicio(),
                    determinacion.tributo(),
                    determinacion.periodo(),
                    determinacion.contribuyenteId(),
                    determinacion.predioId(),
                    determinacion.vehiculoId(),
                    determinacion.conjuntoId(),
                    determinacion.baseImponible(),
                    determinacion.montoDeterminado(),
                    determinacion.reglasAplicadas(),
                    determinacion.origen(),
                    determinacion.estado(),
                    "cajero.ventanilla");
        }

        @Override
        public Determinacion insertar(Determinacion determinacion) {
            throw new UnsupportedOperationException("El predial siempre lleva detalle por predio");
        }
    }

    private static final class AuditoriaDePrueba implements Auditoria {

        private final List<RegistroDeAuditoria> registros = new ArrayList<>();

        @Override
        public void registrar(RegistroDeAuditoria registro) {
            registros.add(registro);
        }
    }
}
