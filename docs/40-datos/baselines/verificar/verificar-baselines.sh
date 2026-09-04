#!/usr/bin/env bash
#
# Verifica los cuatro baselines contra el esquema de `sgtm`, EJECUTANDO.
#
# Levanta cuatro bases vacias, les aplica su `V1__baseline.sql` con Flyway, y compara el
# esquema resultante con el que produce correr `V1..V78` restringido a las tablas de ese
# sistema: tablas, columnas, tipos, restricciones, indices, politicas de RLS, privilegios
# —incluidos los de columna—, disparadores, funciones, dominios y comentarios.
#
# NO lee los archivos: consulta el catalogo del motor. Un baseline que «se ve bien» y no
# cuadra es la forma mas cara de empezar cuatro repositorios.
#
#   ./verificar-baselines.sh --url jdbc:postgresql://localhost:5432/postgres \
#                            --usuario postgres --clave postgres
#
# CON POSTGIS: si el motor lo tiene, pasa `--con-postgis` y las migraciones se aplican
# ENTERAS. Sin el, se saltan las 6 sentencias de `predio` que lo necesitan —la columna
# `geometria`, su indice GiST y las 4 columnas generadas de `V65`— y el guion lo dice en su
# resumen en vez de callarlo.
set -euo pipefail

AQUI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
RAIZ=$(cd "$AQUI/../../../.." && pwd)
URL=""; USUARIO="postgres"; CLAVE="postgres"; CON_POSTGIS="no"
TRABAJO=$(mktemp -d)
trap 'rm -rf "$TRABAJO"' EXIT

while [ $# -gt 0 ]; do
    case "$1" in
        --url) URL=${2:?falta el valor de --url}; shift 2 ;;
        --usuario) USUARIO=${2:?}; shift 2 ;;
        --clave) CLAVE=${2:?}; shift 2 ;;
        --con-postgis) CON_POSTGIS="si"; shift ;;
        *) echo "Opcion desconocida: $1" >&2; exit 2 ;;
    esac
done
[ -n "$URL" ] || { echo "FALLO: falta --url (jdbc:postgresql://host:puerto/postgres)" >&2; exit 2; }

# ---------------------------------------------------------------------------
# Las migraciones de `sgtm`, tal cual o sin las 6 sentencias de PostGIS.
# ---------------------------------------------------------------------------
MIGRACIONES="$TRABAJO/migraciones"
mkdir -p "$MIGRACIONES"
cp "$RAIZ"/backend/sgtm-esquema/src/main/resources/db/migration/*.sql "$MIGRACIONES/"
SALTADAS=0
if [ "$CON_POSTGIS" = "no" ]; then
    python3 - "$MIGRACIONES" <<'PY'
import re, sys, os
d = sys.argv[1]
p = os.path.join(d, "V61__geometria_del_predio.sql"); s = open(p, encoding="utf-8").read()
s = s.replace("ALTER TABLE predio\n    ADD COLUMN geometria geography(MultiPolygon, 4326);",
              "-- [SIN POSTGIS]")
s = re.sub(r"COMMENT ON COLUMN predio\.geometria IS.*?;", "-- [SIN POSTGIS]", s, flags=re.S)
s = re.sub(r"CREATE INDEX predio_geometria_gix.*?;", "-- [SIN POSTGIS]", s, flags=re.S)
open(p, "w", encoding="utf-8").write(s)
p = os.path.join(d, "V65__marco_del_predio.sql"); s = open(p, encoding="utf-8").read()
s = re.sub(r"ALTER TABLE predio\n    ADD COLUMN marco_oeste.*?STORED;", "-- [SIN POSTGIS]", s, flags=re.S)
s = re.sub(r"COMMENT ON COLUMN predio\.marco_(oeste|sur|este|norte) IS.*?;", "-- [SIN POSTGIS]", s, flags=re.S)
s = re.sub(r"CREATE INDEX predio_marco_ix.*?;", "-- [SIN POSTGIS]", s, flags=re.S)
open(p, "w", encoding="utf-8").write(s)
PY
    SALTADAS=6
fi

export SGTM_BASELINE_URL="$URL" SGTM_BASELINE_USUARIO="$USUARIO" SGTM_BASELINE_CLAVE="$CLAVE"
ejecutar() { (cd "$AQUI" && "$RAIZ/backend/gradlew" -q -p "$AQUI" run -Dclase="$1" --args="$2" --console=plain); }

echo "── 1. La referencia: V1..V78 sobre una base vacia"
ejecutar Preparar "ref_baselines $MIGRACIONES"

FALLOS=0
for SISTEMA in rentas catastro normativa caja; do
    echo "── 2.$SISTEMA. El baseline sobre una base vacia"
    D="$TRABAJO/mig-$SISTEMA"; mkdir -p "$D"
    cp "$AQUI/../$SISTEMA/V1__baseline.sql" "$D/"
    ejecutar Preparar "t_$SISTEMA $D"

    TABLAS=$(grep -oE '^(CREATE TABLE) [a-z_0-9]+' "$D/V1__baseline.sql" \
             | awk '{print $3}' | sort -u | paste -sd, -)
    ejecutar Retrato "ref_baselines $TRABAJO/ref-$SISTEMA.txt $TABLAS"
    ejecutar Retrato "t_$SISTEMA $TRABAJO/new-$SISTEMA.txt $TABLAS"
    python3 "$AQUI/canonizar.py" "$TRABAJO/ref-$SISTEMA.txt" "$TRABAJO/refc-$SISTEMA.txt" >/dev/null
    python3 "$AQUI/canonizar.py" "$TRABAJO/new-$SISTEMA.txt" "$TRABAJO/newc-$SISTEMA.txt" >/dev/null

    # Las foraneas que CRUZAN la frontera no se pueden crear: su tabla de destino no existe
    # en este sistema. Estan declaradas en el baseline como `[CRUZA LA FRONTERA]`, y aqui se
    # descuentan UNA A UNA por su nombre, no por un filtro generico.
    CRUZAN=$(grep -c 'CRUZA LA FRONTERA' "$D/V1__baseline.sql" || true)
    DIFF="$TRABAJO/diff-$SISTEMA.txt"
    diff "$TRABAJO/refc-$SISTEMA.txt" "$TRABAJO/newc-$SISTEMA.txt" > "$DIFF" || true
    LINEAS=$(grep -c '^[<>]' "$DIFF" || true)
    ESPERADAS=$CRUZAN
    if [ "$LINEAS" -eq "$ESPERADAS" ]; then
        echo "   OK   diff de $SISTEMA: $LINEAS linea(s), las $CRUZAN foranea(s) que cruzan"
    else
        echo "   ROJO diff de $SISTEMA: $LINEAS linea(s), se esperaban $ESPERADAS"
        cat "$DIFF"
        FALLOS=$((FALLOS + 1))
    fi
done

echo "── 3. Las guardas, contra el catalogo del motor"
ejecutar Guardas "ref_baselines t_rentas t_catastro t_normativa t_caja" || FALLOS=$((FALLOS + 1))

echo
if [ "$SALTADAS" -gt 0 ]; then
    echo "AVISO: sin PostGIS. $SALTADAS sentencia(s) de \`predio\` NO se verificaron:"
    echo "  la columna \`geometria\`, el indice \`predio_geometria_gix\`, las 4 columnas"
    echo "  generadas del marco (\`V65\`) y el indice \`predio_marco_ix\`."
    echo "  Vuelve a correr con --con-postgis en un motor que lo tenga para cerrarlo."
fi
[ "$FALLOS" -eq 0 ] && { echo "LOS CUATRO BASELINES CUADRAN"; exit 0; }
echo "$FALLOS COMPROBACION(ES) EN ROJO" >&2; exit 1
