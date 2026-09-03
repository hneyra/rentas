import js from "@eslint/js";
import tseslint from "typescript-eslint";

/**
 * Lo minimo, y una regla propia.
 *
 * `no-restricted-imports` sobre `@pulumi/*` es la que sostiene el contrato: un descriptor
 * devuelve **objetos planos**, y en cuanto importe Pulumi deja de poder auditarse desde
 * `infrastructure`. La misma idea que la regla de ESLint que impide leer configuracion dentro
 * de un componente.
 */
export default tseslint.config(
  { ignores: ["node_modules/**", "dist/**"] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  {
    rules: {
      "no-restricted-imports": [
        "error",
        {
          patterns: [
            {
              group: ["@pulumi/*", "pulumi"],
              message:
                "Un descriptor devuelve objetos PLANOS de Kubernetes, no recursos de Pulumi " +
                "(ADR-0011, ADR-0031 §2). Con un `pulumi.Input` dentro, `infrastructure` no " +
                "puede leer el manifiesto y la auditoria deja de valer a traves de la frontera.",
            },
            {
              group: ["node:process", "process", "dotenv"],
              message:
                "Un descriptor RECIBE su entorno (`EntornoDelDescriptor`), no lo lee. Leer " +
                "configuracion aqui convierte «falta un valor» en un fallo a mitad del " +
                "despliegue en vez de uno del arranque.",
            },
          ],
        },
      ],
    },
  },
);
