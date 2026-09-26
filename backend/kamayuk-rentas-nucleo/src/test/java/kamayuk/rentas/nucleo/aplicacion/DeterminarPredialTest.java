package kamayuk.rentas.nucleo.aplicacion;

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
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.nucleo.BeneficioRegistrado;
import kamayuk.rentas.nucleo.BeneficiosDelContribuyente;
import kamayuk.rentas.nucleo.dominio.EstadoDeDeterminacion;
import kamayuk.rentas.nucleo.dominio.predial.AporteDeTramo;
import kamayuk.rentas.nucleo.dominio.predial.CuotaDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionPredialCalculada;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionRepository;
import kamayuk.rentas.nucleo.dominio.predial.ModalidadDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.PredioEnLaBase;
import kamayuk.rentas.parametros.IdentificadorDeConjunto;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    private BeneficiosEnMemoria beneficios;

    /** Lo que `catastro` sello. Nace VACIO: sin valuacion, manda el autovaluo declarado (#38). */
    private kamayuk.rentas.nucleo.dobles.ValuacionesSelladasEnMemoria valuaciones;

    @BeforeEach
    void preparar() {
        valuaciones = new kamayuk.rentas.nucleo.dobles.ValuacionesSelladasEnMemoria();
        determinaciones = new DeterminacionesEnMemoria();
        predios = new PrediosDePrueba();
        auditoria = new AuditoriaDePrueba();
        beneficios = new BeneficiosEnMemoria();
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
                                        EJERCICIO,
                                        "C-001",
                                        uno,
                                        ModalidadDelPredial.TRIMESTRAL,
                                        false),
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
                // Hasta #38 esta asercion exigia «D-11». Ya no, y no es que se haya relajado: el
                // mensaje decia «el sistema no lo puede derivar todavia» y recitaba GOB-03, D-02b y
                // D-11, que son bloqueos de ESTE sistema. Quien valoriza es `catastro`, asi que lo
                // que hay que decir es que contesto EL —y aqui no contesto nada—. Las dos formas
                // del motivo las mide `LaValuacionSellada`.
                .hasMessageContaining("ni declarado, ni sellado por `catastro`")
                .hasMessageContaining("no ha publicado ninguna valuacion");
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
                                        ModalidadDelPredial.TRIMESTRAL,
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
    @DisplayName("#234 — una peticion sin modalidad no se construye: no hay valor por omision")
    void sinModalidadNoHayPeticion() {
        assertThatThrownBy(
                        () ->
                                new DeterminarPredial.Peticion(
                                        EJERCICIO,
                                        "C-001",
                                        List.of(declarado(11L, "100000.00")),
                                        null,
                                        false))
                .as(
                        "hasta #234 esto se leia como TRIMESTRAL, y con V21 esa suposicion quedaria"
                                + " escrita en la fila como si la hubiera elegido el contribuyente")
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("modalidad");
    }

    @Test
    @DisplayName("#234 — la modalidad pedida queda GUARDADA en la cabecera, y manda el cronograma")
    void laModalidadQuedaEnLaCabecera() {
        predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

        DeterminacionPredialCalculada alContado =
                servicio()
                        .determinar(
                                new DeterminarPredial.Peticion(
                                        EJERCICIO,
                                        "C-001",
                                        List.of(declarado(11L, "100000.00")),
                                        ModalidadDelPredial.CONTADO,
                                        false),
                                PORQUE);

        assertThat(alContado.cabecera().modalidad())
                .as(
                        "es la CABECERA la que la lleva, que es la fila que queda escrita: en la"
                                + " respuesta y en la base tiene que ser la misma")
                .isEqualTo(ModalidadDelPredial.CONTADO);
        assertThat(alContado.modalidad()).isEqualTo(ModalidadDelPredial.CONTADO);
        // UNA cuota y no cuatro: con el montaje trimestral, «aplica la modalidad pedida» y
        // «aplica siempre la trimestral» darian el mismo verde.
        assertThat(alContado.cuotas()).hasSize(1);
        assertThat(alContado.cuotas().get(0).vencimiento())
                .isEqualTo(LocalDate.parse("2026-02-27"));
        assertThat(alContado.cuotas().get(0).importe())
                .as("la cuota unica es el impuesto entero, no un cuarto")
                .isEqualTo(alContado.impuestoInsoluto());
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
                                                ModalidadDelPredial.TRIMESTRAL,
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
                                        EJERCICIO,
                                        "C-001",
                                        List.of(),
                                        ModalidadDelPredial.TRIMESTRAL,
                                        true),
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
                                                        ModalidadDelPredial.TRIMESTRAL,
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
                                                        ModalidadDelPredial.TRIMESTRAL,
                                                        true),
                                                PORQUE))
                .isInstanceOf(DeterminarPredial.ContribuyenteInexistente.class);
    }

    @Nested
    @DisplayName(
            "#331 — con un beneficio PREDIAL vigente al 1 de enero no se emite: RT-012 no existe")
    class LaGuardaDeLasDeducciones {

        /** Mismo predio, mismo autovaluo: 80 000,00 x 0,2 % = 160,00 para quien no tiene nada. */
        private void mismosPrediosParaLosDos() {
            predios.conVigencia(
                    501L, 31L, "10031", "AV. SULLANA 31", Porcentaje.total(), "2015-01-01", null);
            predios.conVigencia(
                    502L, 32L, "10032", "AV. SULLANA 32", Porcentaje.total(), "2015-01-01", null);
        }

        private DeterminacionPredialCalculada determinarA(String codigo, long predioId) {
            return servicio()
                    .determinar(
                            new DeterminarPredial.Peticion(
                                    EJERCICIO,
                                    codigo,
                                    List.of(declarado(predioId, "80000.00")),
                                    ModalidadDelPredial.TRIMESTRAL,
                                    true),
                            PORQUE);
        }

        @Test
        @DisplayName("el pensionista no recibe la cifra sin deducir; el vecino identico si")
        void conBeneficioNoSeEmiteYSinElSi() {
            mismosPrediosParaLosDos();
            beneficios.de(501L, pensionista("2025-06-01", null));

            assertThatThrownBy(() -> determinarA("C-001", 31L))
                    .as(
                            "sin RT-012, emitir le cobraria 160,00 a quien la ley le deduce 50"
                                    + " UIT: eso es lo que hacia el codigo hasta #331")
                    .isInstanceOf(DeterminarPredial.BeneficioPredialSinRegla.class)
                    .hasMessageContaining("C-001")
                    .hasMessageContaining("PENSIONISTA")
                    .hasMessageContaining("RT-012")
                    .hasMessageContaining("2026-01-01");
            assertThat(determinarA("C-002", 32L).impuestoInsoluto())
                    .as("el mismo predio sin beneficio se determina como siempre")
                    .isEqualTo(Dinero.de("160.00"));
        }

        @Test
        @DisplayName("un beneficio cesado antes del 1 de enero no detiene la emision")
        void elBeneficioCesadoNoCuenta() {
            mismosPrediosParaLosDos();
            beneficios.de(501L, pensionista("2020-01-01", "2025-12-31"));

            assertThat(determinarA("C-001", 31L).impuestoInsoluto()).isEqualTo(Dinero.de("160.00"));
        }

        @Test
        @DisplayName("un beneficio de otro tributo no es del predial")
        void unBeneficioDeOtroTributoNoCuenta() {
            mismosPrediosParaLosDos();
            beneficios.de(
                    501L,
                    new BeneficioRegistrado(
                            "DESCUENTO",
                            "DESCUENTO",
                            "ARBITRIOS",
                            null,
                            null,
                            "Ordenanza de prueba",
                            LocalDate.parse("2025-01-01"),
                            null));

            assertThat(determinarA("C-001", 31L).impuestoInsoluto()).isEqualTo(Dinero.de("160.00"));
        }

        private static BeneficioRegistrado pensionista(
                String desde, @org.jspecify.annotations.Nullable String hasta) {
            return new BeneficioRegistrado(
                    "PENSIONISTA",
                    "DEDUCCION",
                    "PREDIAL",
                    null,
                    null,
                    "TUO LTM art. 19",
                    LocalDate.parse(desde),
                    hasta == null ? null : LocalDate.parse(hasta));
        }
    }

    // ---------------------------------------------------------------- utilidades

    @org.junit.jupiter.api.Nested
    @DisplayName("#38 — la valuacion que `catastro` sello, y que manda cuando hay dos")
    class LaValuacionSellada {

        @Test
        @DisplayName("con valuacion sellada MANDA la sellada, y la declarada se guarda al lado")
        void mandaLaSellada() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
            valuaciones.conCifra(EJERCICIO, 11L, "180000.00", 42L);

            DeterminacionPredialCalculada calculada = determinar(declarado(11L, "100000.00"), null);

            PredioEnLaBase enLaBase = calculada.predios().get(0);
            assertThat(enLaBase.autovaluo())
                    .as(
                            "ADR-0024: «aqui llega un valor ya calculado y sellado, y sobre el se"
                                    + " aplican tramos, deducciones y alicuotas»")
                    .isEqualTo(Dinero.de("180000.00"));
            assertThat(enLaBase.autovaluoSellado()).isTrue();
            assertThat(enLaBase.valuacionConjuntoId())
                    .as("el conjunto lo fijo LA CORRIDA, no lo resuelve este sistema (ADR-0027 §2)")
                    .isEqualTo(42L);
            assertThat(enLaBase.valuacionHuella()).isNotNull();
            assertThat(enLaBase.autovaluoDeclarado())
                    .as(
                            "la declarada NO desaparece: si desapareciera, la discrepancia se"
                                    + " descubriria en ventanilla con el papel ya notificado")
                    .isEqualTo(Dinero.de("100000.00"));
            assertThat(calculada.cabecera().baseImponible()).isEqualTo(Dinero.de("180000.00"));
        }

        @Test
        @DisplayName(
                "sin valuacion sellada manda la declarada, que es el estado de casi todo el padron")
        void sinSelladaMandaLaDeclarada() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

            PredioEnLaBase enLaBase =
                    determinar(declarado(11L, "100000.00"), null).predios().get(0);

            assertThat(enLaBase.autovaluo()).isEqualTo(Dinero.de("100000.00"));
            assertThat(enLaBase.autovaluoSellado()).isFalse();
            assertThat(enLaBase.valuacionConjuntoId()).isNull();
            assertThat(enLaBase.autovaluoDeclarado())
                    .as("no hay dos cifras que comparar, asi que no se guarda ninguna «otra»")
                    .isNull();
        }

        @Test
        @DisplayName("una valuacion CON MOTIVO y sin cifras no vale como cero: manda la declarada")
        void unaValuacionConMotivoNoEsCero() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
            valuaciones.sinCifra(
                    EJERCICIO,
                    11L,
                    "No hay tabla de depreciacion para el uso de la ficha (RT-004)",
                    "TABLA_DE_DEPRECIACION");

            PredioEnLaBase enLaBase =
                    determinar(declarado(11L, "100000.00"), null).predios().get(0);

            assertThat(enLaBase.autovaluo())
                    .as(
                            "leerla como cero dejaria la base del contribuyente en cero y el recibo"
                                    + " saldria plausible (#48). Hoy es el caso de 19 de los 23 predios"
                                    + " de la demostracion")
                    .isEqualTo(Dinero.de("100000.00"));
            assertThat(enLaBase.autovaluoSellado()).isFalse();
        }

        @Test
        @DisplayName("AC-4 — un predio con valuacion sellada y SIN declaracion se determina")
        void conSelladaYSinDeclaracionSeDetermina() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
            valuaciones.conCifra(EJERCICIO, 11L, "150000.00", 42L);

            // Ni un `PredioDeclarado`: antes de #38 esto lanzaba `PredioSinAutovaluo` aunque
            // `valuacion_predio` trajera sus cuatro cifras.
            DeterminacionPredialCalculada calculada =
                    servicio()
                            .determinar(
                                    new DeterminarPredial.Peticion(
                                            EJERCICIO,
                                            "C-001",
                                            List.of(),
                                            ModalidadDelPredial.TRIMESTRAL,
                                            false),
                                    PORQUE);

            assertThat(calculada.cabecera().baseImponible()).isEqualTo(Dinero.de("150000.00"));
            assertThat(calculada.predios().get(0).autovaluoSellado()).isTrue();
        }

        @Test
        @DisplayName("y sin ninguna de las dos, el motivo dice lo que `catastro` contesto")
        void sinNingunaDeLasDosSeDiceQueContestoCatastro() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
            valuaciones.sinCifra(
                    EJERCICIO,
                    11L,
                    "Falta el % de actualizacion del ejercicio",
                    "PORCENTAJE_DE_ACTUALIZACION");

            assertThatThrownBy(
                            () ->
                                    servicio()
                                            .determinar(
                                                    new DeterminarPredial.Peticion(
                                                            EJERCICIO,
                                                            "C-001",
                                                            List.of(),
                                                            ModalidadDelPredial.TRIMESTRAL,
                                                            false),
                                                    PORQUE))
                    .as(
                            "antes de #38 este mensaje recitaba GOB-03, D-02b y D-11, que son"
                                    + " bloqueos de ESTE sistema. Quien valoriza es `catastro`, asi que"
                                    + " lo que hay que decir es que contesto")
                    .isInstanceOf(DeterminarPredial.PredioSinAutovaluo.class)
                    .hasMessageContaining("NO pudo calcularla")
                    .hasMessageContaining("PORCENTAJE_DE_ACTUALIZACION");
        }

        @Test
        @DisplayName("y si `catastro` no publico nada, lo dice de OTRA manera")
        void sinValuacionNiDeclaracionSeDistingueDeLaQueNoPudo() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

            assertThatThrownBy(
                            () ->
                                    servicio()
                                            .determinar(
                                                    new DeterminarPredial.Peticion(
                                                            EJERCICIO,
                                                            "C-001",
                                                            List.of(),
                                                            ModalidadDelPredial.TRIMESTRAL,
                                                            false),
                                                    PORQUE))
                    .as(
                            "«no ha publicado ninguna» y «publico y no pudo» se arreglan de"
                                    + " maneras distintas: correr la valuacion, o sellar la llave")
                    .isInstanceOf(DeterminarPredial.PredioSinAutovaluo.class)
                    .hasMessageContaining("no ha publicado ninguna valuacion");
        }

        @Test
        @DisplayName("y la corrida LEE las valuaciones: no coincide por casualidad")
        void laCorridaLeeLasValuaciones() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
            // La sellada y la declarada valen LO MISMO: el resultado no distingue nada, y por eso
            // lo que se afirma es la LECTURA. Es la mitad que #38 AC-5 pide y que ninguna cifra
            // podria sostener.
            valuaciones.conCifra(EJERCICIO, 11L, "100000.00", 42L);

            determinar(declarado(11L, "100000.00"), null);

            assertThat(valuaciones.loQuePreguntaron())
                    .as(
                            "el candado exigia que llegaran todas y la corrida determinaba con"
                                    + " otras cifras: una valuacion completa y una incompleta producian"
                                    + " el mismo recibo")
                    .contains(EJERCICIO.valor() + ":11");
        }
    }

    /**
     * <b>El obligado del ejercicio es el titular al 1 de enero</b> (#328; TUO LTM art. 10; NEG-05
     * §3: «una transferencia durante el ejercicio no cambia al obligado del ejercicio»).
     *
     * <p>La siembra es la venta del issue: P (autovaluo 200 000,00) es 100 % de A —C-001— hasta el
     * 14 de marzo de 2026 y 100 % de B —C-002— desde el 15, y el reloj esta en el 1 de abril. Con
     * la UIT de 5 500,00 y el cuadro del articulo 13, el predial 2026 de P es 82 500 x 0,2 % + 117
     * 500 x 0,6 % = <b>870,00</b>, y lo debe A. Es la siembra que distingue: con el doble anterior,
     * que contestaba lo mismo a cualquier fecha, leer el padron del reloj y leerlo al 1 de enero
     * daban el mismo verde.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("#328 — la titularidad que se determina es la del 1 de enero, no la del reloj")
    class LaTitularidadDelPrimeroDeEnero {

        /** El 1 de abril de 2026, con la venta del 15 de marzo ya registrada. */
        private static final Clock PRIMERO_DE_ABRIL =
                Clock.fixed(Instant.parse("2026-04-01T15:00:00Z"), ZoneId.of("America/Lima"));

        /** Un omiso o una fiscalizacion: el mismo ejercicio 2026, determinado en 2027. */
        private static final Clock EN_2027 =
                Clock.fixed(Instant.parse("2027-05-10T15:00:00Z"), ZoneId.of("America/Lima"));

        private static final long P = 33L;

        @BeforeEach
        void venderAMitadDeAnio() {
            predios.conVigencia(
                    501L,
                    P,
                    "10033",
                    "CALLE LA VENTA 33",
                    Porcentaje.total(),
                    "2019-06-01",
                    "2026-03-14");
            predios.conVigencia(
                    502L, P, "10033", "CALLE LA VENTA 33", Porcentaje.total(), "2026-03-15", null);
        }

        @Test
        @DisplayName("el que vendio en marzo es el obligado: P entra al 100 % y da 870,00")
        void elVendedorEsElObligadoDelEjercicio() {
            DeterminacionPredialCalculada calculada =
                    determinarA("C-001", PRIMERO_DE_ABRIL, declarado(P, "200000.00"));

            assertThat(calculada.predios())
                    .as(
                            "el 1 de abril P ya no esta a nombre de A, y aun asi es SUYO en 2026:"
                                    + " leer el padron del reloj lo dejaba sin base")
                    .extracting(PredioEnLaBase::predioId)
                    .containsExactly(P);
            assertThat(calculada.predios().get(0).porcentajePropiedad())
                    .isEqualTo(Porcentaje.total());
            assertThat(calculada.cabecera().baseImponible()).isEqualTo(Dinero.de("200000.00"));
            // 82 500 x 0.2 % = 165.00 ; 117 500 x 0.6 % = 705.00 ; total 870.00
            assertThat(calculada.impuestoInsoluto()).isEqualTo(Dinero.de("870.00"));
            assertThat(calculada.fechaCalculo())
                    .as(
                            "la fecha de CALCULO sigue siendo la del reloj (regla 9): son dos"
                                    + " fechas, y fundirlas al reves tambien es el defecto")
                    .isEqualTo(LocalDate.parse("2026-04-01"));
        }

        @Test
        @DisplayName("el que compro en marzo no tiene base en 2026: SinPrediosEnElPadron")
        void elCompradorNoTieneBaseEnElEjercicio() {
            assertThatThrownBy(
                            () -> determinarA("C-002", PRIMERO_DE_ABRIL, declarado(P, "200000.00")))
                    .as(
                            "B paga desde 2027. Determinarle 2026 le cargaria 870,00 que no debe, y"
                                    + " con A emitido en febrero el mismo predio se cobraria dos"
                                    + " veces")
                    .isInstanceOf(DeterminarPredial.SinPrediosEnElPadron.class)
                    .hasMessageContaining("C-002")
                    .hasMessageContaining("2025-12-31");
        }

        @Test
        @DisplayName(
                "con otros predios y los autovaluos del ejercicio, el vendedor no es PredioAjeno")
        void elVendedorConOtrosPrediosNoEsPredioAjeno() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
            // Lo que A declaro en febrero, antes de vender: los dos predios.
            determinaciones.sembrarDelEjercicio(
                    EJERCICIO,
                    7L,
                    DetalleDeterminacionPredio.nuevo(
                            11L,
                            Dinero.de("100000.00"),
                            Dinero.CERO,
                            Porcentaje.total(),
                            Dinero.de("100000.00")),
                    DetalleDeterminacionPredio.nuevo(
                            P,
                            Dinero.de("200000.00"),
                            Dinero.CERO,
                            Porcentaje.total(),
                            Dinero.de("200000.00")));

            DeterminacionPredialCalculada calculada = determinarA("C-001", PRIMERO_DE_ABRIL);

            assertThat(calculada.predios())
                    .extracting(PredioEnLaBase::predioId)
                    .containsExactlyInAnyOrder(11L, P);
            // 82 500 x 0.2 % = 165.00 ; 217 500 x 0.6 % = 1 305.00 ; total 1 470.00
            assertThat(calculada.impuestoInsoluto()).isEqualTo(Dinero.de("1470.00"));
        }

        @Test
        @DisplayName("recalcular 2026 en 2027 da el mismo obligado y el mismo centimo (regla 6)")
        void recalcularElEjercicioOtroAnioDaElMismoCentimo() {
            DeterminacionPredialCalculada enAbril =
                    determinarA("C-001", PRIMERO_DE_ABRIL, declarado(P, "200000.00"));
            DeterminacionPredialCalculada enElSiguiente =
                    determinarA("C-001", EN_2027, declarado(P, "200000.00"));

            assertThat(enElSiguiente.impuestoInsoluto())
                    .as(
                            "un omiso determinado en 2027 leia la titularidad de 2027: otro"
                                    + " obligado y otro importe para el MISMO ejercicio")
                    .isEqualTo(enAbril.impuestoInsoluto())
                    .isEqualTo(Dinero.de("870.00"));
            assertThat(enElSiguiente.fechaCalculo()).isEqualTo(LocalDate.parse("2027-05-10"));
            assertThatThrownBy(() -> determinarA("C-002", EN_2027, declarado(P, "200000.00")))
                    .isInstanceOf(DeterminarPredial.SinPrediosEnElPadron.class);
        }

        @Test
        @DisplayName("el uso que se enseña es el de la ficha al 1 de enero, no el de hoy")
        void elUsoEsElDeLaFichaAlPrimeroDeEnero() {
            LectorDeCaracteristicas fichas =
                    new UnaFichaQueCambiaDeUso(
                            P, "CASA HABITACION", LocalDate.parse("2026-03-01"), "COMERCIO");

            PredioEnLaBase enLaBase =
                    servicioCon(conjunto().construir(), PRIMERO_DE_ABRIL, fichas)
                            .determinar(
                                    new DeterminarPredial.Peticion(
                                            EJERCICIO,
                                            "C-001",
                                            List.of(declarado(P, "200000.00")),
                                            ModalidadDelPredial.TRIMESTRAL,
                                            false),
                                    PORQUE)
                            .predios()
                            .get(0);

            assertThat(enLaBase.uso())
                    .as(
                            "NEG-05 §3: la determinacion consulta las caracteristicas vigentes a la"
                                    + " fecha de referencia, no las actuales")
                    .isEqualTo("CASA HABITACION");
        }

        private DeterminacionPredialCalculada determinarA(
                String codigo, Clock reloj, DeterminarPredial.PredioDeclarado... declarados) {
            return servicioCon(conjunto().construir(), reloj, new SinCaracteristicas())
                    .determinar(
                            new DeterminarPredial.Peticion(
                                    EJERCICIO,
                                    codigo,
                                    List.of(declarados),
                                    ModalidadDelPredial.TRIMESTRAL,
                                    false),
                            PORQUE);
        }
    }

    /**
     * <b>Una transferencia del mismo 1 de enero no cambia al obligado de ese ejercicio</b> (#328,
     * ronda 1; TUO LTM art. 10, segundo parrafo: «cuando se efectue cualquier transferencia, el
     * adquirente asume la condicion de contribuyente a partir del 1 de enero del año siguiente de
     * producido el hecho»).
     *
     * <p>Es el borde que la lectura al 1 de enero no ve: {@code GestorDeTitularidad} cierra la
     * cuota anterior el dia antes de la transferencia, asi que con una venta fechada 2026-01-01 el
     * padron a ese dia ya dice «B» — y B asume desde 2027. Las dos pruebas se distinguen por un
     * dia: la venta del 1 de enero deja 2026 al vendedor, la del 31 de diciembre se lo da al
     * comprador. Con el reloj en abril, lejos de las dos fechas, para que el reloj no decida nada.
     */
    @org.junit.jupiter.api.Nested
    @DisplayName("#328 — la transferencia fechada el 1 de enero es del ejercicio siguiente")
    class LaTransferenciaDelPrimeroDeEnero {

        private static final Clock EN_ABRIL =
                Clock.fixed(Instant.parse("2026-04-01T15:00:00Z"), ZoneId.of("America/Lima"));

        private static final long P = 44L;

        @Test
        @DisplayName("vendida el 2026-01-01: 2026 sigue siendo del vendedor, no del comprador")
        void laVentaDelPrimeroDeEneroDejaElEjercicioAlVendedor() {
            // A -> B con fecha 2026-01-01: la cuota de A se cierra el 2025-12-31.
            predios.conVigencia(
                    501L,
                    P,
                    "10044",
                    "CALLE ANO NUEVO 44",
                    Porcentaje.total(),
                    "2019-06-01",
                    "2025-12-31");
            predios.conVigencia(
                    502L, P, "10044", "CALLE ANO NUEVO 44", Porcentaje.total(), "2026-01-01", null);

            DeterminacionPredialCalculada deA = determinarA("C-001", declarado(P, "200000.00"));

            assertThat(deA.predios())
                    .as(
                            "el hecho se produjo en 2026, asi que B asume desde el 1 de enero de"
                                    + " 2027: leer la titularidad al 2026-01-01 ya ve a B y deja a"
                                    + " A sin base")
                    .extracting(PredioEnLaBase::predioId)
                    .containsExactly(P);
            assertThat(deA.impuestoInsoluto()).isEqualTo(Dinero.de("870.00"));
            assertThatThrownBy(() -> determinarA("C-002", declarado(P, "200000.00")))
                    .as("y a B no se le puede cargar 2026: lo pagaria dos veces con A")
                    .isInstanceOf(DeterminarPredial.SinPrediosEnElPadron.class)
                    .hasMessageContaining("C-002")
                    .hasMessageContaining("2025-12-31");
        }

        @Test
        @DisplayName("comprada el 2025-12-31: 2026 ya es del comprador")
        void laCompraDelTreintaYUnoDeDiciembreEsDelComprador() {
            // A -> B con fecha 2025-12-31: la cuota de A se cierra el 2025-12-30.
            predios.conVigencia(
                    501L,
                    P,
                    "10044",
                    "CALLE ANO NUEVO 44",
                    Porcentaje.total(),
                    "2019-06-01",
                    "2025-12-30");
            predios.conVigencia(
                    502L, P, "10044", "CALLE ANO NUEVO 44", Porcentaje.total(), "2025-12-31", null);

            DeterminacionPredialCalculada deB = determinarA("C-002", declarado(P, "200000.00"));

            assertThat(deB.predios())
                    .as(
                            "el hecho se produjo en 2025: B asume desde el 1 de enero de 2026, y"
                                    + " leer la titularidad antes del 31 de diciembre se lo"
                                    + " devolveria a A")
                    .extracting(PredioEnLaBase::predioId)
                    .containsExactly(P);
            assertThat(deB.impuestoInsoluto()).isEqualTo(Dinero.de("870.00"));
            assertThatThrownBy(() -> determinarA("C-001", declarado(P, "200000.00")))
                    .isInstanceOf(DeterminarPredial.SinPrediosEnElPadron.class)
                    .hasMessageContaining("C-001");
        }

        private DeterminacionPredialCalculada determinarA(
                String codigo, DeterminarPredial.PredioDeclarado... declarados) {
            return servicioCon(conjunto().construir(), EN_ABRIL, new SinCaracteristicas())
                    .determinar(
                            new DeterminarPredial.Peticion(
                                    EJERCICIO,
                                    codigo,
                                    List.of(declarados),
                                    ModalidadDelPredial.TRIMESTRAL,
                                    false),
                            PORQUE);
        }
    }

    /**
     * <b>Lo que puede faltar se resuelve ANTES de asentar</b> (#359).
     *
     * <p>El derecho de emision, los vencimientos de la modalidad pedida y el punto de redondeo
     * {@code CUOTA} son las tres piezas del conjunto que hoy no publica nadie (D-02b, D-03c), y las
     * tres se resolvian <b>despues</b> de {@code registrar}: la fila y su {@code ALTA} ya estaban
     * confirmados cuando la peticion contestaba 422, y cada reintento dejaba otra.
     *
     * <h2>La siembra que distingue es {@code simulacion = false}</h2>
     *
     * <p>Las pruebas que habia de la cifra ausente —{@link #sinDerechoDeEmisionNoSeDetermina()} y
     * las de {@code PredialControllerTest}— simulaban todas, y simulando {@code registrar} no
     * escribe: ninguna podia ver la fila. Aqui se asienta, y lo que se cuenta son las dos
     * escrituras que el defecto dejaba —la fila en el doble del repositorio y el registro en el de
     * la auditoria—, no solo la excepcion, que salia igual con el defecto dentro.
     */
    @Nested
    @DisplayName("#359 — sin una pieza del conjunto no se asienta nada: ni la fila ni su ALTA")
    class LoQueFaltaSeResuelveAntesDeAsentar {

        @Test
        @DisplayName("sin DERECHO_EMISION_PREDIAL: ParametroAusente, cero filas y cero ALTA")
        void sinDerechoDeEmisionNoQuedaFila() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

            assertThatThrownBy(
                            () ->
                                    asentarCon(
                                            conjuntoSin(LoQueFalta.DERECHO_DE_EMISION),
                                            ModalidadDelPredial.TRIMESTRAL))
                    .isInstanceOf(ParametrosSellados.ParametroAusente.class)
                    .hasMessageContaining("DERECHO_EMISION_PREDIAL");

            assertThat(determinaciones.insertadas)
                    .as("el 422 que el usuario ve no puede dejar una determinacion confirmada")
                    .isZero();
            assertThat(auditoria.registros)
                    .as("ni el ALTA de una determinacion que nadie llego a tener")
                    .isEmpty();
        }

        @Test
        @DisplayName("TRIMESTRAL con solo el CONTADO publicado: 422, cero filas y cero ALTA")
        void sinLasCuotasDeLaModalidadNoQuedaFila() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

            assertThatThrownBy(
                            () ->
                                    asentarCon(
                                            conjuntoSin(LoQueFalta.CUOTAS_DEL_FRACCIONADO),
                                            ModalidadDelPredial.TRIMESTRAL))
                    .isInstanceOf(CuadroPredialParametrizado.ParametroDelPredialAusente.class)
                    .hasMessageContaining("TRIMESTRAL");

            assertThat(determinaciones.insertadas)
                    .as(
                            "la fila diria modalidad TRIMESTRAL (V21) y seria «la ultima» de un"
                                    + " cronograma que no existe")
                    .isZero();
            assertThat(auditoria.registros).isEmpty();
        }

        @Test
        @DisplayName("y el mismo conjunto SI asienta al CONTADO: lo que falta es de la modalidad")
        void elMismoConjuntoAsientaAlContado() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

            DeterminacionPredialCalculada alContado =
                    asentarCon(
                            conjuntoSin(LoQueFalta.CUOTAS_DEL_FRACCIONADO),
                            ModalidadDelPredial.CONTADO);

            assertThat(alContado.cabecera().id())
                    .as("sin este contraste, «no asienta nunca» pasaria las pruebas de arriba")
                    .isNotNull();
            assertThat(determinaciones.insertadas).isEqualTo(1);
            assertThat(auditoria.registros).hasSize(1);
        }

        @Test
        @DisplayName("sin el punto de redondeo CUOTA: PuntoSinPolitica, cero filas y cero ALTA")
        void sinElPuntoDeLaCuotaNoQuedaFila() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());

            assertThatThrownBy(
                            () ->
                                    asentarCon(
                                            conjuntoSin(LoQueFalta.PUNTO_CUOTA),
                                            ModalidadDelPredial.TRIMESTRAL))
                    .isInstanceOf(PoliticasDeRedondeo.PuntoSinPolitica.class)
                    .hasMessageContaining("CUOTA");

            assertThat(determinaciones.insertadas).isZero();
            assertThat(auditoria.registros).isEmpty();
        }

        private DeterminacionPredialCalculada asentarCon(
                ParametrosSellados sellados, ModalidadDelPredial modalidad) {
            return servicioCon(sellados)
                    .determinar(
                            new DeterminarPredial.Peticion(
                                    EJERCICIO,
                                    "C-001",
                                    List.of(declarado(11L, "100000.00")),
                                    modalidad,
                                    false),
                            PORQUE);
        }
    }

    /**
     * #361 — El conjunto sellado se resuelve <b>una vez</b> por determinacion.
     *
     * <p>Hasta #361 se resolvia cuatro: dos en {@code cuadro.vigenteEn} —los parametros por un
     * lado, el identificador por otro— y dos en {@code RegistrarDeterminacionPredial.calcular}. De
     * la primera salian los tramos, la UIT, el minimo, el derecho y los vencimientos; de la cuarta,
     * el {@code conjunto_id} que se guarda. Con {@code normativa} sellando «2026 v2» a mitad de la
     * operacion —o intermitente, con el repliegue al cacheado en una llamada y no en la otra—, la
     * determinacion decia haber salido de un conjunto que no la produjo.
     *
     * <p>La siembra que lo distingue es {@link
     * kamayuk.rentas.parametros.NormativaQueCambiaEntreLlamadas}: v1 y v2 difieren en la alicuota
     * del tramo 1, en la UIT y en el derecho de emision. Con el doble de siempre —el mismo conjunto
     * a cualquier pregunta— esta prueba sale verde con el defecto dentro.
     */
    @Nested
    @DisplayName("#361 — una sola resolucion del conjunto por determinacion")
    class UnaSolaResolucionDelConjunto {

        @Test
        @DisplayName(
                "con el conjunto cambiando entre llamadas, el conjunto_id guardado reproduce el"
                        + " monto al centimo")
        void elConjuntoGuardadoReproduceElMonto() {
            predios.con(11L, "10001", "AV. GRAU 100", Porcentaje.total());
            kamayuk.rentas.parametros.NormativaQueCambiaEntreLlamadas normativa =
                    new kamayuk.rentas.parametros.NormativaQueCambiaEntreLlamadas(
                            IdentificadorDeConjunto.de(1L),
                            cuadroDeLaVersion(1, "5500.00", "0.2", "4.50"),
                            IdentificadorDeConjunto.de(2L),
                            cuadroDeLaVersion(2, "5350.00", "0.4", "5.00"));

            DeterminacionPredialCalculada determinada =
                    servicioConElLector(normativa)
                            .determinar(
                                    new DeterminarPredial.Peticion(
                                            EJERCICIO,
                                            "C-001",
                                            List.of(declarado(11L, "100000.00")),
                                            ModalidadDelPredial.TRIMESTRAL,
                                            false),
                                    PORQUE);

            int resoluciones = normativa.resoluciones();
            Determinacion cabecera = determinada.cabecera();
            CuadroPredialParametrizado.Vigente delGuardado =
                    new CuadroPredialParametrizado(normativa)
                            .delConjunto(EJERCICIO, cabecera.conjuntoId());
            Dinero reproducido =
                    kamayuk.rentas.nucleo.dominio.predial.MinimoImponible.aplicarSobreBase(
                            kamayuk.rentas.nucleo.dominio.predial.TramosProgresivosAcumulativos
                                    .calcular(
                                            cabecera.baseImponible(),
                                            delGuardado.tramos(),
                                            delGuardado.redondeo()),
                            cabecera.baseImponible(),
                            delGuardado.minimoImponible());
            // Suaves: el rojo tiene que decir a la vez CUANTAS resoluciones hubo y QUE cifra se
            // mezclo, que son las dos cosas que #361 mide.
            org.assertj.core.api.SoftAssertions.assertSoftly(
                    blando -> {
                        blando.assertThat(resoluciones)
                                .as(
                                        "una determinacion resuelve el conjunto UNA vez: cada"
                                                + " resolucion de mas es otra pregunta por red a"
                                                + " normativa, con su propio repliegue, y puede"
                                                + " contestar otro conjunto")
                                .isEqualTo(1);
                        blando.assertThat(reproducido)
                                .as(
                                        "recalcular con el conjunto_id que la fila guarda tiene"
                                                + " que dar el mismo centimo (ARQ-09 §3, regla 6):"
                                                + " si no, la fila dice haber salido de un"
                                                + " conjunto que no la produjo")
                                .isEqualTo(cabecera.montoDeterminado());
                        blando.assertThat(determinada.nombreDelConjunto())
                                .as("la respuesta nombra el mismo conjunto que la fila guarda")
                                .isEqualTo(delGuardado.nombreDelConjunto());
                        blando.assertThat(determinada.uit())
                                .as("la UIT publicada es la del conjunto guardado")
                                .isEqualTo(delGuardado.uit());
                        blando.assertThat(determinada.derechoDeEmision())
                                .as("y el derecho de emision tambien")
                                .isEqualTo(delGuardado.derechoDeEmision());
                    });
        }

        private DeterminarPredial servicioConElLector(LectorDeParametros lector) {
            return new DeterminarPredial(
                    new PadronPredialDelEjercicio(determinaciones),
                    predios,
                    new SinCaracteristicas(),
                    new DirectorioDePrueba(),
                    new CuadroPredialParametrizado(lector),
                    valuaciones,
                    beneficios,
                    new RegistrarDeterminacionPredial(determinaciones, auditoria),
                    RELOJ);
        }

        /**
         * Un cuadro completo, con las tres cifras que distinguen una version de otra: la UIT, la
         * alicuota del tramo 1 y el derecho de emision. Lo demas es igual en las dos a proposito
         * —la base se redondea igual—, para que la unica diferencia sea la que se mide.
         */
        private ParametrosSellados cuadroDeLaVersion(
                int version, String uit, String alicuotaDelTramo1, String derecho) {
            return ParametrosSellados.de(EJERCICIO, version)
                    .numero("UIT", null, ValorNormativo.de(uit))
                    .numero("TRAMO_PREDIAL", "1", ValorNormativo.de(alicuotaDelTramo1))
                    .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                    .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.6"))
                    .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60"))
                    .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.0"))
                    .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                    .numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de(derecho))
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
                    .texto("REDONDEO", "CUOTA", "HALF_UP")
                    .construir();
        }
    }

    /** Cual de las tres piezas de #359 le falta al conjunto. */
    private enum LoQueFalta {
        DERECHO_DE_EMISION,
        CUOTAS_DEL_FRACCIONADO,
        PUNTO_CUOTA
    }

    /**
     * El conjunto completo <b>menos una</b> de las tres piezas de #359, y con todo lo demas que el
     * calculo atraviesa: si faltara tambien otra, la prueba podria salir roja por la que no mide.
     * El contado queda siempre publicado, que es la variante del issue —la ordenanza publica el
     * contado y todavia no las cuotas—.
     */
    private static ParametrosSellados conjuntoSin(LoQueFalta falta) {
        ParametrosSellados.Constructor conjunto =
                ParametrosSellados.de(EJERCICIO, 1)
                        .numero("UIT", null, ValorNormativo.de("5500.00"))
                        .numero("TRAMO_PREDIAL", "1", ValorNormativo.de("0.2"))
                        .numero("TRAMO_PREDIAL_LIMITE", "1", ValorNormativo.de("15"))
                        .numero("TRAMO_PREDIAL", "2", ValorNormativo.de("0.6"))
                        .numero("TRAMO_PREDIAL_LIMITE", "2", ValorNormativo.de("60"))
                        .numero("TRAMO_PREDIAL", "3", ValorNormativo.de("1.0"))
                        .numero("PREDIAL_MINIMO", null, ValorNormativo.de("0.6"))
                        .texto("PREDIAL_VENCIMIENTO", "CONTADO", "2026-02-27")
                        .numero("REDONDEO", "IMPUESTO_POR_TRAMO", ValorNormativo.de("2"))
                        .texto("REDONDEO", "IMPUESTO_POR_TRAMO", "HALF_UP")
                        .numero("REDONDEO", "BASE_DEL_CONTRIBUYENTE", ValorNormativo.de("2"))
                        .texto("REDONDEO", "BASE_DEL_CONTRIBUYENTE", "HALF_UP")
                        .numero("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", ValorNormativo.de("2"))
                        .texto("REDONDEO", "BASE_IMPONIBLE_DEL_PREDIO", "HALF_UP");
        if (falta != LoQueFalta.DERECHO_DE_EMISION) {
            conjunto.numero("DERECHO_EMISION_PREDIAL", null, ValorNormativo.de("4.50"));
        }
        if (falta != LoQueFalta.CUOTAS_DEL_FRACCIONADO) {
            conjunto.texto("PREDIAL_VENCIMIENTO", "1", "2026-02-27")
                    .texto("PREDIAL_VENCIMIENTO", "2", "2026-05-29")
                    .texto("PREDIAL_VENCIMIENTO", "3", "2026-08-31")
                    .texto("PREDIAL_VENCIMIENTO", "4", "2026-11-30");
        }
        if (falta != LoQueFalta.PUNTO_CUOTA) {
            conjunto.numero("REDONDEO", "CUOTA", ValorNormativo.de("2"))
                    .texto("REDONDEO", "CUOTA", "HALF_UP");
        }
        return conjunto.construir();
    }

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
                                EJERCICIO,
                                "C-001",
                                declarados,
                                ModalidadDelPredial.TRIMESTRAL,
                                false),
                        PORQUE);
    }

    private static DeterminarPredial.PredioDeclarado declarado(long predioId, String autovaluo) {
        return new DeterminarPredial.PredioDeclarado(predioId, Dinero.de(autovaluo), null);
    }

    private DeterminarPredial servicio() {
        return servicioCon(conjunto().construir());
    }

    private DeterminarPredial servicioCon(ParametrosSellados sellados) {
        return servicioCon(sellados, RELOJ, new SinCaracteristicas());
    }

    private DeterminarPredial servicioCon(
            ParametrosSellados sellados, Clock reloj, LectorDeCaracteristicas fichas) {
        LectorDeParametros lector = lector(sellados);
        return new DeterminarPredial(
                new PadronPredialDelEjercicio(determinaciones),
                predios,
                fichas,
                new DirectorioDePrueba(),
                new CuadroPredialParametrizado(lector),
                valuaciones,
                beneficios,
                new RegistrarDeterminacionPredial(determinaciones, auditoria),
                reloj);
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
                // El articulo 15 a): la clave del contado, que #234 vuelve pedible desde la
                // fila guardada. Sin ella el montaje solo sabria dibujar el fraccionado.
                .texto("PREDIAL_VENCIMIENTO", "CONTADO", "2026-02-27")
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

    /**
     * Los predios de cada contribuyente, <b>con la vigencia de cada cuota</b> (#328).
     *
     * <p>Hasta #328 este doble ignoraba los dos argumentos —devolvia la misma lista a quien se
     * preguntara y a la fecha que se preguntara— con el reloj fijo en agosto. Era la muestra
     * uniforme: «el padron del dia de calculo» y «el padron al 1 de enero» daban el mismo verde, y
     * por eso ninguna prueba vio que la determinacion leia la titularidad del reloj. Ahora cada
     * cuota tiene su vigencia, como {@code titularidad}: la transferencia cierra la anterior el dia
     * antes ({@code GestorDeTitularidad}).
     */
    /** Los beneficios registrados; responde por vigencia, como el puerto promete. */
    private static final class BeneficiosEnMemoria implements BeneficiosDelContribuyente {

        private final Map<Long, List<BeneficioRegistrado>> porContribuyente = new LinkedHashMap<>();

        void de(long contribuyenteId, BeneficioRegistrado beneficio) {
            porContribuyente
                    .computeIfAbsent(contribuyenteId, id -> new ArrayList<>())
                    .add(beneficio);
        }

        @Override
        public List<BeneficioRegistrado> vigentesA(long contribuyenteId, LocalDate aLaFecha) {
            return porContribuyente.getOrDefault(contribuyenteId, List.of()).stream()
                    .filter(beneficio -> beneficio.rigeEn(aLaFecha))
                    .toList();
        }
    }

    private static final class PrediosDePrueba implements PrediosDelContribuyente {

        /** Una cuota de titularidad; {@code desde} y {@code hasta} nulos son «siempre». */
        private record Cuota(
                long contribuyenteId,
                PredioDelContribuyente predio,
                @org.jspecify.annotations.Nullable LocalDate desde,
                @org.jspecify.annotations.Nullable LocalDate hasta) {

            boolean vigenteEn(long quien, LocalDate fecha) {
                return contribuyenteId == quien
                        && (desde == null || !fecha.isBefore(desde))
                        && (hasta == null || !fecha.isAfter(hasta));
            }
        }

        private final List<Cuota> cuotas = new ArrayList<>();

        void con(long predioId, String codigo, String direccion, Porcentaje cuota) {
            // Sin decir otra cosa, el predio tiene dueño completo —la cuota del contribuyente ES
            // todo lo registrado— y es de C-001 desde siempre.
            cuotas.add(
                    new Cuota(
                            DirectorioDePrueba.UNO.id(),
                            new PredioDelContribuyente(
                                    predioId, codigo, "URBANO", direccion, cuota),
                            null,
                            null));
        }

        /** Un predio cuyas cuotas NO cubren el predio entero (#690). */
        void conTitularidadIncompleta(
                long predioId,
                String codigo,
                String direccion,
                Porcentaje cuota,
                Porcentaje registrado) {
            cuotas.add(
                    new Cuota(
                            DirectorioDePrueba.UNO.id(),
                            new PredioDelContribuyente(
                                    predioId, codigo, "URBANO", direccion, cuota, registrado),
                            null,
                            null));
        }

        /** Una cuota con su vigencia, de cualquiera de los dos contribuyentes (#328). */
        void conVigencia(
                long contribuyenteId,
                long predioId,
                String codigo,
                String direccion,
                Porcentaje cuota,
                String desde,
                @org.jspecify.annotations.Nullable String hasta) {
            cuotas.add(
                    new Cuota(
                            contribuyenteId,
                            new PredioDelContribuyente(
                                    predioId, codigo, "URBANO", direccion, cuota),
                            LocalDate.parse(desde),
                            hasta == null ? null : LocalDate.parse(hasta)));
        }

        @Override
        public List<PredioDelContribuyente> de(long contribuyenteId, LocalDate fecha) {
            return cuotas.stream()
                    .filter(cuota -> cuota.vigenteEn(contribuyenteId, fecha))
                    .map(Cuota::predio)
                    .toList();
        }
    }

    /** Sin ficha catastral: el uso sale nulo, y la determinacion se hace igual. */
    private static final class SinCaracteristicas implements LectorDeCaracteristicas {
        @Override
        public Optional<CaracteristicasDelPredio> de(long predioId, LocalDate fecha) {
            return Optional.empty();
        }
    }

    /**
     * La ficha de UN predio que cambia de uso a mitad del ejercicio (#328).
     *
     * <p>Contesta segun la fecha, igual que el padron: un doble que ignorase el argumento no
     * distinguiria «el uso al 1 de enero» de «el uso de hoy».
     */
    private record UnaFichaQueCambiaDeUso(
            long predioId, String usoAntes, LocalDate cambiaEl, String usoDespues)
            implements LectorDeCaracteristicas {
        @Override
        public Optional<CaracteristicasDelPredio> de(long predio, LocalDate fecha) {
            if (predio != predioId) {
                return Optional.empty();
            }
            String uso = fecha.isBefore(cambiaEl) ? usoAntes : usoDespues;
            return Optional.of(new CaracteristicasDelPredio(uso, null, null));
        }
    }

    private static final class DirectorioDePrueba implements DirectorioDeContribuyentes {

        private static final ResumenDeContribuyente UNO =
                new ResumenDeContribuyente(501L, "C-001", "SUC. RUFINA MEDINA MEDINA", "03593174");

        /** El comprador de #328: sin un segundo contribuyente, una venta no tiene a quien ir. */
        private static final ResumenDeContribuyente DOS =
                new ResumenDeContribuyente(502L, "C-002", "SULLON VILCHEZ, JOSE RAUL", "29614026");

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("La determinacion no busca por texto");
        }

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            if (UNO.codigo().equals(codigo)) {
                return Optional.of(UNO);
            }
            return DOS.codigo().equals(codigo) ? Optional.of(DOS) : Optional.empty();
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            Map<Long, ResumenDeContribuyente> encontrados = new LinkedHashMap<>();
            if (ids.contains(UNO.id())) {
                encontrados.put(UNO.id(), UNO);
            }
            if (ids.contains(DOS.id())) {
                encontrados.put(DOS.id(), DOS);
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
                            kamayuk.rentas.nucleo.dominio.OrigenDeDeterminacion.ORDINARIA,
                            EstadoDeDeterminacion.BORRADOR,
                            "siembra",
                            ModalidadDelPredial.TRIMESTRAL));
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
                    "cajero.ventanilla",
                    determinacion.modalidad());
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
