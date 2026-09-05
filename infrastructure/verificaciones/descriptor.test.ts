import { describe, expect, it } from "vitest";
import type { EntornoDelDescriptor } from "@sgtm/infra-contrato";
import { rentas } from "../src/descriptor";

/**
 * El descriptor de `rentas`, verificado sobre lo que devuelve.
 *
 * Esto es lo que corre en la maquina de quien lo escribe y en el CI de este repositorio: **sin
 * Pulumi, sin token y sin cluster**. La auditoria completa —las convenciones de `INF-01` §4 y las
 * cinco prohibiciones— la hace `infrastructure` al componer; aqui se comprueba lo que este
 * repositorio decide y solo el.
 */

const ENTORNO: EntornoDelDescriptor = {
  ambiente: "stg",
  namespace: "kamayuk-rentas-stg",
  dominio: "stg.kamayuk.example",
  etiquetas: { "app.kubernetes.io/part-of": "kamayuk", ambiente: "stg" },
  imagenDe: (c) => `ghcr.io/hneyra/kamayuk-${c}:0eee58e43e04b1c2d3f4a5b6c7d8e9f0a1b2c3d4`,
  secretoDe: (c) => `kamayuk-rentas-stg-${c}`,
  prioridadDe: (clase) => `kamayuk-stg-prioridad-${clase}`,
  // Del AMBIENTE, no de este sistema (C-7): quien recibe el aviso cuando algo
  // se rompe aqui. `checkInvariants` de `infrastructure` rechaza el relleno.
  operacion: { responsable: "Guardia de plataforma", canal: "#kamayuk-guardia" },
};

describe("el descriptor de rentas", () => {
  it("declara su base, y SOLO la suya", () => {
    const base = rentas.baseDeDatos(ENTORNO);
    expect(base.nombre).toBe("rentas");
    for (const rol of base.roles) {
      expect(rol.sobre).toEqual(["rentas"]);
      // Un superusuario OMITE RLS aunque haya FORCE (DAT-01 §0, hallazgo 1).
      expect(rol.superusuario).toBe(false);
    }
  });

  it("no fija la etiqueta de ninguna imagen: la pide", () => {
    // La prohibicion (b) de `infrastructure`, comprobada aqui tambien porque es la que sostiene
    // que una liberacion normal NO sea un `pulumi up` (ADR-0011 §5).
    const admisibles = rentas.imagenes.map((n) => ENTORNO.imagenDe(n));
    const imagenes = [...rentas.despliegue(ENTORNO), ...rentas.migracion(ENTORNO)]
      .flatMap((m) =>
        m.kind === "Deployment"
          ? m.spec.template.spec.containers
          : m.kind === "Job"
            ? m.spec.template.spec.containers
            : [],
      )
      .map((c) => c.image);
    expect(imagenes.length).toBeGreaterThan(0);
    for (const i of imagenes) expect(admisibles).toContain(i);
  });

  it("todas sus rutas van bajo su prefijo", () => {
    for (const m of rentas.ingreso(ENTORNO)) {
      if (m.kind !== "IngressRoute") continue;
      for (const r of m.spec.routes) {
        for (const encaje of r.match.matchAll(/PathPrefix\(`([^`]*)`\)/g)) {
          expect(encaje[1]).toMatch(/^\/rentas(\/|$)/);
        }
      }
    }
  });

  it("no emite ningun Secret, y su inventario no trae valores", () => {
    const todos = [
      ...rentas.despliegue(ENTORNO),
      ...rentas.migracion(ENTORNO),
      ...rentas.ingreso(ENTORNO),
    ];
    expect(todos.some((m) => (m as { kind: string }).kind === "Secret")).toBe(false);
    for (const c of rentas.claves(ENTORNO)) {
      for (const campo of ["valor", "value", "data", "stringData", "password"]) {
        expect((c as unknown as Record<string, unknown>)[campo]).toBeUndefined();
      }
    }
  });

  it("todo contenedor declara limites de recursos", () => {
    const contenedores = [...rentas.despliegue(ENTORNO), ...rentas.migracion(ENTORNO)].flatMap((m) =>
      m.kind === "Deployment"
        ? m.spec.template.spec.containers
        : m.kind === "Job"
          ? m.spec.template.spec.containers
          : [],
    );
    for (const c of contenedores) {
      expect(c.resources.requests.cpu).toBeTruthy();
      expect(c.resources.limits.memory).toBeTruthy();
    }
  });

  it("su egreso es catastro, normativa y caja: el grafo de ADR-0029", () => {
    expect(destinosDeEgreso()).toEqual(["caja", "catastro", "normativa"]);
  });

  it("y sus DOS perfiles producen dos Deployments; el `batch` sin puerto", () => {
    // `ADR-0003` sigue siendo cierto DENTRO de este sistema: un artefacto, dos perfiles.
    const deployments = rentas.despliegue(ENTORNO).filter((m) => m.kind === "Deployment");
    expect(deployments.map((m) => m.metadata.name)).toEqual([
      "kamayuk-rentas-web",
      "kamayuk-rentas-batch",
    ]);
    const batch = deployments[1];
    if (batch?.kind !== "Deployment") throw new Error("falta el perfil batch");
    // El perfil batch no atiende HTTP: un puerto ahi es una superficie que nadie pidio.
    expect(batch.spec.template.spec.containers[0]?.ports).toBeUndefined();
    expect(batch.spec.template.spec.containers[0]?.livenessProbe).toBeUndefined();
  });
});

/** Los SISTEMAS a los que este descriptor declara egreso. El motor y la identidad no cuentan. */
function destinosDeEgreso(): string[] {
  const infra = ["postgres", "identidad"];
  return rentas
    .egreso(ENTORNO)
    .flatMap((p) => p.spec.egress ?? [])
    .flatMap((r) => r.to ?? [])
    .map((s) => s.podSelector?.matchLabels?.["componente"])
    .filter((c): c is string => c !== undefined && !infra.includes(c))
    .sort();
}
