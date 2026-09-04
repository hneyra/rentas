package kamayuk.rentas.parametros;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.dominio.ValorNormativo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * El motor, sin Spring, sin Docker y sin reloj.
 *
 * <h2>Aviso sobre las cifras</h2>
 *
 * <p><b>Todos los valores de esta prueba son ficticios y estan declarados como tales.</b> Los
 * factores {@code 2} y {@code 3} de las reglas de muestra no son alicuotas, ni tramos, ni nada que
 * aparezca en ninguna norma: son numeros que hacen facil comprobar que el grafo se resolvio. Las
 * cifras de verdad son D-02 y no entran hasta que se verifiquen.
 *
 * <p>Ninguna esta escrita en el codigo de las reglas: <b>salen de los parametros</b>, que es lo que
 * la regla 5 exige y lo que este motor existe para hacer posible.
 *
 * <p>Los identificadores van en el rango {@code RT-9xx} para que no se confundan con los reales de
 * NEG-05, que van de {@code RT-001} a {@code RT-016}.
 */
@DisplayName("ADR-0007 — Motor de reglas")
class MotorDeReglasTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2026);

    /**
     * Ficticia. Ni la escala, ni el modo, ni el punto elegido representan una decision sobre D-03:
     * ninguna regla de esta prueba redondea, el motor solo transporta la parametrizacion.
     */
    private static final PoliticasDeRedondeo REDONDEO_DE_PRUEBA =
            PoliticasDeRedondeo.construir()
                    .en(
                            PuntoDeRedondeo.AUTOVALUO_DEL_PREDIO,
                            new PoliticaDeRedondeo(2, RoundingMode.HALF_UP))
                    .construir();

    // Los conceptos de la muestra imitan la forma del grafo real —dos ramas que convergen—
    // sin usar sus nombres, para que nadie los confunda con el calculo del predial.
    private static final Concepto AREA = Concepto.de("AREA_FICTICIA");
    private static final Concepto RAMA_UNO = Concepto.de("RAMA_UNO");
    private static final Concepto RAMA_DOS = Concepto.de("RAMA_DOS");
    private static final Concepto CONVERGENCIA = Concepto.de("CONVERGENCIA");
    private static final Concepto TOTAL_DEL_CONJUNTO = Concepto.de("TOTAL_DEL_CONJUNTO");

    private static ParametrosSellados parametrosFicticios() {
        return ParametrosSellados.de(EJERCICIO, 1)
                .numero("FICTICIO", "factor-uno", ValorNormativo.de("2"))
                .numero("FICTICIO", "factor-dos", ValorNormativo.de("3"))
                .construir();
    }

    private static EntradaDeCalculo entrada(String area) {
        return new EntradaDeCalculo(
                EJERCICIO,
                EstadoDelCalculo.con(AREA, Dinero.de(area)),
                CaracteristicasDeLaPartida.ninguna(),
                parametrosFicticios(),
                REDONDEO_DE_PRUEBA);
    }

    // ------------------------------------------------------------------
    // Reglas de muestra. Ninguna lleva una cifra dentro.
    // ------------------------------------------------------------------

    /** Multiplica el area por un factor que sale de los parametros. */
    private static ReglaTributaria rama(
            String id, Concepto produce, String claveDelFactor, RangoDeEjercicios vigencia) {
        return new ReglaTributaria() {
            @Override
            public IdentificadorDeRegla identificador() {
                return IdentificadorDeRegla.de(id);
            }

            @Override
            public RangoDeEjercicios vigencia() {
                return vigencia;
            }

            @Override
            public String descripcion() {
                return "Regla ficticia de prueba; no representa ninguna norma";
            }

            @Override
            public Set<Concepto> requiere() {
                return Set.of(AREA);
            }

            @Override
            public Concepto produce() {
                return produce;
            }

            @Override
            public Dinero calcular(InsumosDeLaRegla insumos) {
                return insumos.de(AREA).por(insumos.numero("FICTICIO", claveDelFactor).valor());
            }
        };
    }

    /** Suma las dos ramas: la convergencia que una cadena lineal no puede expresar. */
    private static ReglaTributaria convergencia(String id) {
        return new ReglaTributaria() {
            @Override
            public IdentificadorDeRegla identificador() {
                return IdentificadorDeRegla.de(id);
            }

            @Override
            public RangoDeEjercicios vigencia() {
                return RangoDeEjercicios.desde(new Ejercicio(2004));
            }

            @Override
            public String descripcion() {
                return "Convergencia ficticia de dos ramas; no representa ninguna norma";
            }

            @Override
            public Set<Concepto> requiere() {
                return Set.of(RAMA_UNO, RAMA_DOS);
            }

            @Override
            public Concepto produce() {
                return CONVERGENCIA;
            }

            @Override
            public Dinero calcular(InsumosDeLaRegla insumos) {
                return insumos.de(RAMA_UNO).mas(insumos.de(RAMA_DOS));
            }
        };
    }

    private static ReglaDeAgregacion sumaDelConjunto(String id) {
        return new ReglaDeAgregacion() {
            @Override
            public IdentificadorDeRegla identificador() {
                return IdentificadorDeRegla.de(id);
            }

            @Override
            public RangoDeEjercicios vigencia() {
                return RangoDeEjercicios.desde(new Ejercicio(2004));
            }

            @Override
            public String descripcion() {
                return "Agregacion ficticia sobre el conjunto; no representa ninguna norma";
            }

            @Override
            public Concepto deCadaPartida() {
                return CONVERGENCIA;
            }

            @Override
            public Concepto produce() {
                return TOTAL_DEL_CONJUNTO;
            }

            @Override
            public Dinero agregar(List<Dinero> aportes, InsumosDeLaAgregacion insumos) {
                Dinero total = Dinero.de("0");
                for (Dinero aporte : aportes) {
                    total = total.mas(aporte);
                }
                return total;
            }
        };
    }

    private static CatalogoDeReglas catalogoCompleto() {
        return CatalogoDeReglas.vacio()
                .con(convergencia("RT-903"))
                .con(rama("RT-901", RAMA_UNO, "factor-uno", RangoDeEjercicios.desde(EJERCICIO)))
                .con(rama("RT-902", RAMA_DOS, "factor-dos", RangoDeEjercicios.desde(EJERCICIO)));
    }

    // ------------------------------------------------------------------

    @Nested
    @DisplayName("El grafo, no la cadena")
    class ElGrafo {

        @Test
        @DisplayName("dos ramas independientes convergen en una tercera regla")
        void dosRamasConvergen() {
            // El catalogo se registra con la convergencia PRIMERO, a proposito: si el motor
            // aplicara en orden de registro fallaria, porque RT-903 necesita lo que RT-901 y
            // RT-902 todavia no calcularon.
            ResultadoDelCalculo resultado =
                    new MotorDeReglas(catalogoCompleto()).aplicarA(entrada("100.00"));

            assertThat(resultado.exigir(RAMA_UNO)).isEqualTo(Dinero.de("200.00"));
            assertThat(resultado.exigir(RAMA_DOS)).isEqualTo(Dinero.de("300.00"));
            assertThat(resultado.exigir(CONVERGENCIA))
                    .as("la convergencia suma las dos ramas, no encadena una tras otra")
                    .isEqualTo(Dinero.de("500.00"));
        }

        @Test
        @DisplayName("el resultado conserva todos los conceptos, no solo el ultimo")
        void conservaElDesarrollo() {
            ResultadoDelCalculo resultado =
                    new MotorDeReglas(catalogoCompleto()).aplicarA(entrada("100.00"));

            assertThat(resultado.estado().conceptos())
                    .as("una determinacion muestra su desarrollo, no solo el total")
                    .contains(AREA, RAMA_UNO, RAMA_DOS, CONVERGENCIA);
        }

        @Test
        @DisplayName("la convergencia se aplica despues de las dos ramas, registrelas como sea")
        void elOrdenSaleDeLasDependencias() {
            ResultadoDelCalculo resultado =
                    new MotorDeReglas(catalogoCompleto()).aplicarA(entrada("100.00"));

            List<String> aplicadas = resultado.reglasComoTexto();
            assertThat(aplicadas).hasSize(3).endsWith("RT-903");
            assertThat(aplicadas.indexOf("RT-903"))
                    .as("el orden lo deduce el motor de lo declarado, no de la lista")
                    .isGreaterThan(aplicadas.indexOf("RT-901"))
                    .isGreaterThan(aplicadas.indexOf("RT-902"));
        }

        @Test
        @DisplayName("si falta un dato declarado, el motor dice cual y no emite")
        void faltaUnDatoDeclarado() {
            EntradaDeCalculo sinArea =
                    new EntradaDeCalculo(
                            EJERCICIO,
                            EstadoDelCalculo.vacio(),
                            CaracteristicasDeLaPartida.ninguna(),
                            parametrosFicticios(),
                            REDONDEO_DE_PRUEBA);

            assertThatThrownBy(() -> new MotorDeReglas(catalogoCompleto()).aplicarA(sinArea))
                    .as("emitir con lo que hay daria una cifra que nadie distingue de una correcta")
                    .isInstanceOf(MotorDeReglas.ElGrafoNoCierra.class)
                    .hasMessageContaining("AREA_FICTICIA");
        }

        @Test
        @DisplayName("dos reglas vigentes no pueden producir el mismo concepto")
        void dosReglasNoProducenLoMismo() {
            CatalogoDeReglas ambiguo =
                    CatalogoDeReglas.vacio()
                            .con(
                                    rama(
                                            "RT-901",
                                            RAMA_UNO,
                                            "factor-uno",
                                            RangoDeEjercicios.desde(EJERCICIO)))
                            .con(
                                    rama(
                                            "RT-904",
                                            RAMA_UNO,
                                            "factor-dos",
                                            RangoDeEjercicios.desde(EJERCICIO)));

            assertThatThrownBy(() -> new MotorDeReglas(ambiguo).aplicarA(entrada("100.00")))
                    .as("el importe dependeria de cual gane")
                    .isInstanceOf(MotorDeReglas.DosReglasProducenLoMismo.class);
        }

        @Test
        @DisplayName("una regla no puede leer un concepto que no declaro")
        void nadieLeeLoQueNoDeclaro() {
            ReglaTributaria tramposa =
                    new ReglaTributaria() {
                        @Override
                        public IdentificadorDeRegla identificador() {
                            return IdentificadorDeRegla.de("RT-905");
                        }

                        @Override
                        public RangoDeEjercicios vigencia() {
                            return RangoDeEjercicios.desde(EJERCICIO);
                        }

                        @Override
                        public String descripcion() {
                            return "Regla ficticia que lee lo que no declaro";
                        }

                        @Override
                        public Set<Concepto> requiere() {
                            return Set.of(AREA);
                        }

                        @Override
                        public Concepto produce() {
                            return CONVERGENCIA;
                        }

                        @Override
                        public Dinero calcular(InsumosDeLaRegla insumos) {
                            return insumos.de(RAMA_UNO);
                        }
                    };

            CatalogoDeReglas catalogo =
                    CatalogoDeReglas.vacio()
                            .con(
                                    rama(
                                            "RT-901",
                                            RAMA_UNO,
                                            "factor-uno",
                                            RangoDeEjercicios.desde(EJERCICIO)))
                            .con(tramposa);

            assertThatThrownBy(() -> new MotorDeReglas(catalogo).aplicarA(entrada("100.00")))
                    .as(
                            "si la regla usa otra cosa, el orden que el motor calculo no es el que hace"
                                    + " falta")
                    .isInstanceOf(InsumosDeLaRegla.ConceptoNoDeclarado.class)
                    .hasMessageContaining("RT-905");
        }
    }

    @Nested
    @DisplayName("Por contribuyente, no por predio")
    class PorContribuyente {

        @Test
        @DisplayName("la base agrega el aporte de todas las partidas")
        void laBaseEsLaDelConjunto() {
            MotorDeReglas motor =
                    new MotorDeReglas(catalogoCompleto().con(sumaDelConjunto("RT-910")));

            ResultadoDelContribuyente resultado =
                    motor.aplicarAlContribuyente(
                            List.of(entrada("100.00"), entrada("40.00"), entrada("10.00")));

            assertThat(resultado.cantidadDePartidas()).isEqualTo(3);
            assertThat(resultado.exigir(TOTAL_DEL_CONJUNTO))
                    .as(
                            "aplicar los tramos predio por predio produce un error sistematico a la"
                                    + " baja en todo el padron (NEG-05 §1)")
                    .isEqualTo(Dinero.de("750.00"));
        }

        @Test
        @DisplayName("el detalle de cada partida se conserva junto al total")
        void seConservaElDetalle() {
            MotorDeReglas motor =
                    new MotorDeReglas(catalogoCompleto().con(sumaDelConjunto("RT-910")));

            ResultadoDelContribuyente resultado =
                    motor.aplicarAlContribuyente(List.of(entrada("100.00"), entrada("40.00")));

            assertThat(resultado.porPartida().get(0).exigir(CONVERGENCIA))
                    .isEqualTo(Dinero.de("500.00"));
            assertThat(resultado.porPartida().get(1).exigir(CONVERGENCIA))
                    .as("la determinacion muestra el aporte de cada predio a la base")
                    .isEqualTo(Dinero.de("200.00"));
        }

        @Test
        @DisplayName("un contribuyente sin partidas no produce una base de cero")
        void sinPartidasNoHayCero() {
            MotorDeReglas motor = new MotorDeReglas(catalogoCompleto());

            assertThatThrownBy(() -> motor.aplicarAlContribuyente(List.of()))
                    .as("emitir un valor de cero es un acto distinto")
                    .isInstanceOf(MotorDeReglas.SinPartidas.class);
        }
    }

    @Nested
    @DisplayName("Versiones de una regla")
    class VersionesDeUnaRegla {

        @Test
        @DisplayName("se aplica la version del ejercicio del hecho imponible, no la ultima")
        void laDelEjercicioDelHecho() {
            CatalogoDeReglas catalogo =
                    CatalogoDeReglas.vacio()
                            .con(
                                    rama(
                                            "RT-901",
                                            RAMA_UNO,
                                            "factor-uno",
                                            RangoDeEjercicios.entre(
                                                    new Ejercicio(2004), new Ejercicio(2026))))
                            .con(
                                    rama(
                                            "RT-901",
                                            RAMA_UNO,
                                            "factor-dos",
                                            RangoDeEjercicios.desde(new Ejercicio(2027))));

            ResultadoDelCalculo resultado = new MotorDeReglas(catalogo).aplicarA(entrada("100.00"));

            assertThat(resultado.exigir(RAMA_UNO))
                    .as("una implementacion que ya se uso en una emision no se modifica nunca")
                    .isEqualTo(Dinero.de("200.00"));
        }

        @Test
        @DisplayName("dos versiones de la misma regla no pueden solaparse")
        void nadaDeSolapes() {
            CatalogoDeReglas conUna =
                    CatalogoDeReglas.vacio()
                            .con(
                                    rama(
                                            "RT-901",
                                            RAMA_UNO,
                                            "factor-uno",
                                            RangoDeEjercicios.desde(new Ejercicio(2004))));

            assertThatThrownBy(
                            () ->
                                    conUna.con(
                                            rama(
                                                    "RT-901",
                                                    RAMA_UNO,
                                                    "factor-dos",
                                                    RangoDeEjercicios.desde(new Ejercicio(2026)))))
                    .as("con dos vigentes, recalcular el pasado dejaria de ser reproducible")
                    .isInstanceOf(CatalogoDeReglas.VigenciasQueSeSolapan.class);
        }

        @Test
        @DisplayName("un ejercicio sin ninguna regla vigente no devuelve la base sin tocar")
        void sinReglasVigentes() {
            CatalogoDeReglas soloDesde2030 =
                    CatalogoDeReglas.vacio()
                            .con(
                                    rama(
                                            "RT-901",
                                            RAMA_UNO,
                                            "factor-uno",
                                            RangoDeEjercicios.desde(new Ejercicio(2030))));

            assertThatThrownBy(() -> new MotorDeReglas(soloDesde2030).aplicarA(entrada("100.00")))
                    .isInstanceOf(MotorDeReglas.SinReglasVigentes.class)
                    .hasMessageContaining("2026");
        }
    }

    @Nested
    @DisplayName("Los parametros son argumento")
    class LosParametrosSonArgumento {

        @Test
        @DisplayName("un parametro ausente no produce importe")
        void unParametroAusenteNoProduceImporte() {
            EntradaDeCalculo sinElFactor =
                    new EntradaDeCalculo(
                            EJERCICIO,
                            EstadoDelCalculo.con(AREA, Dinero.de("100.00")),
                            CaracteristicasDeLaPartida.ninguna(),
                            ParametrosSellados.de(EJERCICIO, 1).construir(),
                            REDONDEO_DE_PRUEBA);

            assertThatThrownBy(() -> new MotorDeReglas(catalogoCompleto()).aplicarA(sinElFactor))
                    .as("un calculo al que le falta un factor no debe producir un importe")
                    .isInstanceOf(ParametrosSellados.ParametroAusente.class);
        }

        @Test
        @DisplayName("no se calcula un ejercicio con el conjunto sellado de otro")
        void nadaDeCruzarEjercicios() {
            assertThatThrownBy(
                            () ->
                                    new EntradaDeCalculo(
                                            new Ejercicio(2027),
                                            EstadoDelCalculo.con(AREA, Dinero.de("100.00")),
                                            CaracteristicasDeLaPartida.ninguna(),
                                            parametrosFicticios(),
                                            REDONDEO_DE_PRUEBA))
                    .as("cruzarlos produce una cifra plausible y equivocada")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2027");
        }

        @Test
        @DisplayName("el mismo calculo repetido da el mismo centimo")
        void reproducible() {
            MotorDeReglas motor = new MotorDeReglas(catalogoCompleto());

            Dinero primera = motor.aplicarA(entrada("123.45")).exigir(CONVERGENCIA);
            Dinero segunda = motor.aplicarA(entrada("123.45")).exigir(CONVERGENCIA);

            assertThat(primera)
                    .as("sin reloj ni base de datos, repetir el calculo no puede dar otra cosa")
                    .isEqualTo(segunda);
        }
    }
}
