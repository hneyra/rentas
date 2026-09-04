package kamayuk.rentas.catastro.dominio;

/**
 * Urbano o rustico.
 *
 * <p>No es una clasificacion administrativa: decide como se valoriza. El urbano va por arancel de
 * via y valores unitarios de edificacion; el rustico, por grupos de tierra con arancel segun
 * clasificacion y calidad agrologica. Las dos rutas son distintas y las dos estan bloqueadas por
 * D-02a.
 */
public enum TipoPredio {
    URBANO,
    RUSTICO
}
