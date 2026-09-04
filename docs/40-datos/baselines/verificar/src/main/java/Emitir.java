import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Genera el V1__baseline.sql de un sistema desde el catalogo de la base de referencia. */
public final class Emitir {
    static Connection c;

    public static void main(String[] a) throws Exception {
        String base = a[0], salida = a[1], encabezado = a[2];
        List<String> tablas = new ArrayList<>(Arrays.asList(a[3].split(",")));
        Collections.sort(tablas);
        StringBuilder o = new StringBuilder(Files.readString(Path.of(encabezado)));
        c = DriverManager.getConnection(
                aBase(base), usuario(), clave());
        Set<String> t = new LinkedHashSet<>(tablas);

        sec(o, "1. DOMINIOS DE TIPO",
            "Un importe es `dinero`, no `numeric(15,2)`: el dominio lleva su CHECK y hace que"
          + " la restriccion viva en un solo sitio.");
        for (String[] d : filas("""
                SELECT t.typname, pg_catalog.format_type(t.typbasetype, t.typtypmod),
                       COALESCE(string_agg('CONSTRAINT ' || quote_ident(k.conname) || ' '
                            || pg_get_constraintdef(k.oid), ' ' ORDER BY k.conname), '')
                  FROM pg_type t
                  JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname='public'
                  LEFT JOIN pg_constraint k ON k.contypid = t.oid
                 WHERE t.typtype='d' GROUP BY t.oid, t.typname, t.typbasetype, t.typtypmod
                 ORDER BY t.typname""")) {
            o.append("CREATE DOMAIN ").append(d[0]).append(" AS ").append(d[1])
             .append(d[2].isEmpty() ? "" : "\n    " + d[2]).append(";\n");
        }

        sec(o, "2. FUNCIONES",
            "Van antes que las tablas porque una columna GENERADA las usa: `nombre_normalizado`"
          + " es la de `via`, y sin ella el `CREATE TABLE` falla. Las de disparador estan aqui"
          + " tambien; un disparador sin su funcion no protege nada.");
        for (String[] f : filas("""
                SELECT pg_get_functiondef(p.oid)
                  FROM pg_proc p
                  JOIN pg_namespace n ON n.oid = p.pronamespace AND n.nspname = 'public'
                 WHERE p.prokind = 'f'
                   AND NOT EXISTS (SELECT 1 FROM pg_depend d
                        WHERE d.objid = p.oid AND d.deptype = 'e')
                 ORDER BY p.proname""")) {
            o.append(f[0]).append(";\n\n");
        }

        sec(o, "3. TABLAS", "Las particionadas van antes que sus particiones.");
        for (String tb : t) if (esPadreOSuelta(tb)) crearTabla(o, tb);
        for (String tb : t) if (!esPadreOSuelta(tb)) crearParticion(o, tb);

        sec(o, "4. RESTRICCIONES",
            "Las foraneas al final para no depender del orden. Las que el esquema tiene NOT VALID"
          + " se emiten NOT VALID: validarlas es una consulta y el migrador corre sin contexto de"
          + " tenant (DAT-01 §0, hallazgo 4).");
        emitirRestricciones(o, t, false);
        emitirRestricciones(o, t, true);

        sec(o, "5. INDICES",
            "Empezando por municipalidad_id. Los de una tabla particionada se propagan solos a sus"
          + " particiones, asi que aqui no se repiten.");
        for (String tb : t) {
            for (String[] f : filasP("""
                    SELECT pg_get_indexdef(i.indexrelid)
                      FROM pg_index i
                      JOIN pg_class ic ON ic.oid = i.indexrelid
                      JOIN pg_class tc ON tc.oid = i.indrelid
                      JOIN pg_namespace n ON n.oid = tc.relnamespace AND n.nspname='public'
                     WHERE tc.relname = ?
                       AND NOT i.indisprimary
                       AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = i.indexrelid)
                       AND NOT EXISTS (SELECT 1 FROM pg_inherits h WHERE h.inhrelid = i.indexrelid)
                     ORDER BY ic.relname""", tb)) {
                // `pg_get_indexdef` emite `ON ONLY` para el indice de una tabla
                // particionada, y `ON ONLY` NO se propaga a las particiones: el padre
                // acabaria con el indice y sus particiones sin el. Medido: 10 indices, y
                // el sintoma es un plan distinto en la particion, invisible al leer.
                o.append(f[0].replace(" ON ONLY public.", " ON public.")).append(";\n");
            }
        }

        sec(o, "6. ROW LEVEL SECURITY",
            "Sin valor por omision: sin contexto de tenant, la consulta FALLA. Y FORCE, porque sin"
          + " el el DUENO de la tabla la omite. Cada particion repite su bloque: una particion NO"
          + " HEREDA la politica de su padre (DAT-01 §0, hallazgo 2).");
        for (String tb : t) {
            for (String[] f : filasP("""
                    SELECT c.relrowsecurity::text, c.relforcerowsecurity::text
                      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                     WHERE n.nspname='public' AND c.relname = ?""", tb)) {
                if ("true".equals(f[0])) o.append("ALTER TABLE ").append(tb)
                        .append(" ENABLE ROW LEVEL SECURITY;\n");
                if ("true".equals(f[1])) o.append("ALTER TABLE ").append(tb)
                        .append(" FORCE ROW LEVEL SECURITY;\n");
            }
            for (String[] f : filasP("""
                    SELECT pol.polname, pol.polcmd::text,
                           COALESCE((SELECT string_agg(quote_ident(r.rolname), ', ' ORDER BY r.rolname)
                             FROM pg_roles r WHERE r.oid = ANY(pol.polroles)), 'PUBLIC'),
                           COALESCE(pg_get_expr(pol.polqual, pol.polrelid), ''),
                           COALESCE(pg_get_expr(pol.polwithcheck, pol.polrelid), '')
                      FROM pg_policy pol
                      JOIN pg_class c ON c.oid = pol.polrelid
                      JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
                     WHERE c.relname = ? ORDER BY pol.polname""", tb)) {
                o.append("CREATE POLICY ").append(f[0]).append(" ON ").append(tb)
                 .append(" FOR ").append(cmd(f[1])).append(" TO ").append(f[2]);
                if (!f[3].isEmpty()) o.append("\n    USING (").append(f[3]).append(')');
                if (!f[4].isEmpty()) o.append("\n    WITH CHECK (").append(f[4]).append(')');
                o.append(";\n");
            }
        }

        sec(o, "7. PRIVILEGIOS",
            "El rol de la aplicacion NO es dueno ni superusuario, y NO recibe privilegios sobre las"
          + " particiones: se los da el padre. Los privilegios POR COLUMNA son los que sostienen"
          + " que un acto mueva solo lo suyo (V54); un volcado descuidado los devuelve enteros.");
        for (String tb : t) {
            for (String[] f : filasP("""
                    SELECT grantee, string_agg(privilege_type, ', ' ORDER BY privilege_type)
                      FROM information_schema.table_privileges
                     WHERE table_schema='public' AND table_name = ? AND grantee <> 'sgtm_owner'
                     GROUP BY grantee ORDER BY grantee""", tb)) {
                o.append("GRANT ").append(f[1]).append(" ON ").append(tb)
                 .append(" TO ").append(f[0]).append(";\n");
            }
            for (String[] f : filasP("""
                    SELECT cp.grantee, cp.privilege_type,
                           string_agg(quote_ident(cp.column_name), ', ' ORDER BY cp.column_name)
                      FROM information_schema.column_privileges cp
                     WHERE cp.table_schema='public' AND cp.table_name = ?
                       AND cp.grantee <> 'sgtm_owner'
                       AND NOT EXISTS (SELECT 1 FROM information_schema.table_privileges tp
                            WHERE tp.table_schema='public' AND tp.table_name = cp.table_name
                              AND tp.grantee = cp.grantee AND tp.privilege_type = cp.privilege_type)
                     GROUP BY cp.grantee, cp.privilege_type
                     ORDER BY cp.grantee, cp.privilege_type""", tb)) {
                o.append("GRANT ").append(f[1]).append(" (").append(f[2]).append(") ON ")
                 .append(tb).append(" TO ").append(f[0]).append(";\n");
            }
        }

        sec(o, "8. DISPARADORES DE INMUTABILIDAD Y DE INVARIANTE",
            "Con sus funciones. Un disparador sin su funcion no protege nada.");
        for (String tb : t) {
            for (String[] f : filasP("""
                    SELECT pg_get_triggerdef(tg.oid)
                      FROM pg_trigger tg
                      JOIN pg_class c ON c.oid = tg.tgrelid
                      JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
                     WHERE c.relname = ? AND NOT tg.tgisinternal ORDER BY tg.tgname""", tb)) {
                o.append(f[0]).append(";\n");
            }
        }

        sec(o, "9. COMENTARIOS", "El por que de una columna, que es lo primero que se pierde.");
        for (String tb : t) {
            for (String[] f : filasP("""
                    SELECT CASE WHEN d.objsubid = 0 THEN 'TABLE ' || quote_ident(c.relname)
                                ELSE 'COLUMN ' || quote_ident(c.relname) || '.' || quote_ident(a.attname) END,
                           quote_literal(d.description)
                      FROM pg_description d
                      JOIN pg_class c ON c.oid = d.objoid
                      JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
                      LEFT JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=d.objsubid
                     WHERE c.relname = ? ORDER BY d.objsubid""", tb)) {
                o.append("COMMENT ON ").append(f[0]).append(" IS ").append(f[1]).append(";\n");
            }
        }
        sinSufijoRepetido(o);
        Files.writeString(Path.of(salida), o.toString());
        System.out.println("baseline " + salida + ": " + t.size() + " tablas, "
                + o.toString().lines().count() + " lineas");
    }

    /**
     * Ninguna sentencia emitida repite el sufijo ` NOT VALID` (C-3).
     *
     * <p>Mira el TEXTO y no el catalogo, y no puede ser de otra forma: el motor acepta el
     * sufijo repetido -acumula el atributo- y su catalogo queda identico, asi que ni las
     * guardas de `Guardas.java` ni el comparador de `Retrato.java` pueden verlo. Lo que se
     * pierde es que el archivo sea estable en ida y vuelta, y eso solo se ve en el archivo.
     *
     * <p>Se pone roja volviendo a anadir el sufijo a mano en `emitirRestricciones`, que es
     * exactamente el defecto que dejo 36 sentencias asi en el baseline de `rentas`.
     */
    static void sinSufijoRepetido(CharSequence emitido) {
        List<String> malas = new ArrayList<>();
        int n = 0;
        for (String l : emitido.toString().split("\n", -1)) {
            n++;
            if (l.startsWith("--")) continue;      // la prosa SI puede nombrarlo
            if (l.contains("NOT VALID NOT VALID")) malas.add(n + ": " + l);
        }
        if (!malas.isEmpty()) {
            throw new IllegalStateException(malas.size() + " sentencia(s) con el sufijo"
                    + " ` NOT VALID` repetido:\n  " + String.join("\n  ", malas));
        }
    }

    static String cmd(String p) {
        return switch (p) { case "r" -> "SELECT"; case "a" -> "INSERT"; case "w" -> "UPDATE";
                            case "d" -> "DELETE"; default -> "ALL"; };
    }

    static boolean esPadreOSuelta(String tb) throws SQLException {
        return filasP("SELECT 1 FROM pg_inherits h JOIN pg_class c ON c.oid=h.inhrelid"
                + " WHERE c.relname = ?", tb).isEmpty();
    }

    static void crearTabla(StringBuilder o, String tb) throws SQLException {
        List<String> cols = new ArrayList<>();
        for (String[] f : filasP("""
                SELECT a.attname, pg_catalog.format_type(a.atttypid, a.atttypmod),
                       CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END,
                       COALESCE(pg_get_expr(d.adbin, d.adrelid), ''),
                       a.attgenerated::text, a.attidentity::text
                  FROM pg_attribute a
                  JOIN pg_class c ON c.oid = a.attrelid
                  JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
                  LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
                 WHERE c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
                 ORDER BY a.attnum""", tb)) {
            StringBuilder l = new StringBuilder("    " + f[0] + " " + f[1]);
            if ("a".equals(f[5])) l.append(" GENERATED ALWAYS AS IDENTITY");
            else if ("d".equals(f[5])) l.append(" GENERATED BY DEFAULT AS IDENTITY");
            else if ("s".equals(f[4])) l.append(" GENERATED ALWAYS AS (").append(f[3]).append(") STORED");
            else if (!f[3].isEmpty()) l.append(" DEFAULT ").append(f[3]);
            l.append(f[2]);
            cols.add(l.toString());
        }
        String part = uno("SELECT COALESCE(pg_get_partkeydef(c.oid),'') FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'"
                + " WHERE c.relname = ?", tb);
        o.append("CREATE TABLE ").append(tb).append(" (\n")
         .append(String.join(",\n", cols)).append("\n)")
         .append(part.isEmpty() ? "" : " PARTITION BY " + part).append(";\n\n");
    }

    static void crearParticion(StringBuilder o, String tb) throws SQLException {
        String padre = uno("SELECT p.relname FROM pg_inherits h JOIN pg_class c ON c.oid=h.inhrelid"
                + " JOIN pg_class p ON p.oid=h.inhparent WHERE c.relname = ?", tb);
        String lim = uno("SELECT pg_get_expr(c.relpartbound, c.oid) FROM pg_class c"
                + " JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'"
                + " WHERE c.relname = ?", tb);
        o.append("CREATE TABLE ").append(tb).append(" PARTITION OF ").append(padre)
         .append(' ').append(lim).append(";\n");
    }

    static void emitirRestricciones(StringBuilder o, Set<String> t, boolean foraneas)
            throws SQLException {
        for (String tb : t) {
            for (String[] f : filasP("""
                    SELECT k.conname, pg_get_constraintdef(k.oid), k.convalidated::text, k.contype::text
                      FROM pg_constraint k
                      JOIN pg_class c ON c.oid = k.conrelid
                      JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
                     WHERE c.relname = ?
                       AND k.conparentid = 0
                       AND k.conislocal
                       AND k.contype <> 't'
                       AND (k.contype = 'f') = ?
                     ORDER BY k.conname""", tb, String.valueOf(foraneas))) {
                // Una foranea cuyo destino no esta en este sistema NO se puede crear: el motor
                // deja de garantizarla y pasa a ser una invariante que hay que mantener viva
                // (ADR-0027, D-18). Se emite comentada y con su nombre, para que se vea lo que
                // se perdio en vez de que desaparezca en silencio.
                boolean cruza = foraneas && !t.contains(destinoDe(f[1]));
                if (cruza) {
                    o.append("--  [CRUZA LA FRONTERA] ").append(tb).append('.').append(f[0])
                     .append(": ").append(f[1]).append('\n');
                    continue;
                }
                // `pg_get_constraintdef` YA emite el sufijo ` NOT VALID` de una restriccion
                // no validada -medido contra PostgreSQL 16.15-, asi que anadirlo aqui lo
                // DUPLICA. El motor lo acepta -el atributo se acumula y el catalogo queda
                // identico, comprobado hasta con el sufijo TRIPLICADO-, pero el archivo deja
                // de ser estable en ida y vuelta: regenerarlo produce otro texto, y con
                // checksum de Flyway eso importa (C-3).
                //
                // No se confia en que siga siendo asi: se COMPRUEBA. Si algun dia el motor
                // dejara de emitirlo, esto falla nombrando la restriccion en vez de emitir un
                // baseline que crea validada una restriccion que no lo esta -que si seria un
                // defecto de fondo: validar es una consulta y el migrador corre sin contexto
                // de tenant (DAT-01 §0, hallazgo 4)-.
                boolean noValidada = !"true".equals(f[2]);
                if (noValidada != f[1].endsWith(" NOT VALID")) {
                    throw new IllegalStateException("El motor no emite el sufijo NOT VALID como"
                            + " se esperaba en " + tb + '.' + f[0] + " (convalidated=" + f[2]
                            + "): " + f[1]);
                }
                o.append("ALTER TABLE ").append(tb).append(" ADD CONSTRAINT ").append(f[0])
                 .append(' ').append(f[1]).append(";\n");
            }
        }
    }

    /** La tabla a la que apunta un `FOREIGN KEY ... REFERENCES x (...)`. */
    static String destinoDe(String def) {
        int i = def.indexOf("REFERENCES ");
        if (i < 0) return "";
        String r = def.substring(i + 11).trim();
        int j = r.indexOf('(');
        return (j < 0 ? r : r.substring(0, j)).trim();
    }

    static void sec(StringBuilder o, String titulo, String porque) {
        o.append("\n-- ").append("=".repeat(74)).append("\n--  ").append(titulo).append('\n');
        for (String l : envolver(porque)) o.append("--  ").append(l).append('\n');
        o.append("-- ").append("=".repeat(74)).append("\n\n");
    }

    static List<String> envolver(String s) {
        List<String> r = new ArrayList<>(); StringBuilder l = new StringBuilder();
        for (String w : s.split(" ")) {
            if (l.length() + w.length() > 72) { r.add(l.toString()); l = new StringBuilder(); }
            l.append(l.isEmpty() ? "" : " ").append(w);
        }
        if (!l.isEmpty()) r.add(l.toString());
        return r;
    }

    static List<String[]> filas(String sql) throws SQLException { return filasP(sql); }

    static List<String[]> filasP(String sql, String... args) throws SQLException {
        List<String[]> r = new ArrayList<>();
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                if ("true".equals(args[i]) || "false".equals(args[i])) s.setBoolean(i + 1, Boolean.parseBoolean(args[i]));
                else s.setString(i + 1, args[i]);
            }
            try (ResultSet rs = s.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    String[] f = new String[n];
                    for (int i = 0; i < n; i++) f[i] = rs.getString(i + 1) == null ? "" : rs.getString(i + 1);
                    r.add(f);
                }
            }
        }
        return r;
    }

    static String uno(String sql, String... a) throws SQLException {
        List<String[]> r = filasP(sql, a);
        return r.isEmpty() ? "" : r.get(0)[0];
    }
    /** La URL del motor, del entorno: el arnes no fija ningun puerto (INF-01 §4.1). */
    static String base() {
        String u = System.getenv("SGTM_BASELINE_URL");
        if (u == null || u.isBlank()) {
            throw new IllegalStateException(
                    "Falta SGTM_BASELINE_URL, p.ej. jdbc:postgresql://localhost:5432/postgres");
        }
        return u;
    }

    static String usuario() {
        return System.getenv().getOrDefault("SGTM_BASELINE_USUARIO", "postgres");
    }

    static String clave() {
        return System.getenv().getOrDefault("SGTM_BASELINE_CLAVE", "postgres");
    }

    static String admin() { return base(); }

    /** La misma URL, apuntando a otra base del mismo cluster. */
    static String aBase(String nombre) {
        return base().replaceFirst("/[^/?]+(\\?.*)?$", "/" + nombre);
    }

}
