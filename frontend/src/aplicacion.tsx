/**
 * El casco de `rentas-web`.
 *
 * F-1 levanta el andamiaje y **ninguna pantalla**: lo que se monta aqui es el minimo que
 * hace falta para que `yarn build` produzca un bundle y para que la prueba de Testing
 * Library tenga algo que buscar por su rol. Las opciones del manual entran despues, sobre
 * las barreras que este issue deja puestas.
 */
export function Aplicacion() {
  return (
    <main>
      <h1>Rentas</h1>
      <p>
        Contribuyentes, declaraciones juradas, determinación, cuenta corriente, valores,
        fiscalización, coactiva, sanciones y licencias.
      </p>
    </main>
  );
}
