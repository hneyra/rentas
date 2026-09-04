import java.sql.*;
import java.util.*;

/** Las guardas del criterio de aceptacion, consultando el CATALOGO del motor, no el archivo. */
public final class Guardas {
    public static void main(String[] a) throws Exception {
        int fallos = 0;
        for (String base : a) {
            System.out.println("########## " + base);
            try (Connection c = DriverManager.getConnection(
                    aBase(base), usuario(), clave())) {
                fallos += g1(c) + g2(c) + g3(c) + g3b(c) + g4(c);
                cuenta(c);
            }
        }
        System.out.println(fallos == 0 ? "\nTODAS LAS GUARDAS EN VERDE" : "\n" + fallos + " GUARDA(S) EN ROJO");
        if (fallos > 0) System.exit(1);
    }

    /** 1. Ninguna tabla con municipalidad_id NOT NULL se queda sin RLS, y con FORCE. */
    static int g1(Connection c) throws SQLException {
        List<String> malas = lista(c, """
            SELECT c.relname || CASE WHEN NOT c.relrowsecurity THEN ' SIN RLS' ELSE '' END
                   || CASE WHEN NOT c.relforcerowsecurity THEN ' SIN FORCE' ELSE '' END
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname='public'
              JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname='municipalidad_id'
             WHERE c.relkind IN ('r','p') AND a.attnotnull
               AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity)
             ORDER BY 1""");
        return di("tabla de negocio sin RLS o sin FORCE", malas);
    }

    /** 2. Toda tabla con RLS tiene al menos una politica: RLS sin politica niega todo. */
    static int g2(Connection c) throws SQLException {
        return di("tabla con RLS y sin ninguna politica", lista(c, """
            SELECT c.relname FROM pg_class c
              JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
             WHERE c.relkind IN ('r','p') AND c.relrowsecurity
               AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid)
             ORDER BY 1"""));
    }

    /**
     * 3. Ninguna politica de una tabla DE TENANT tiene valor por omision: sin contexto, la
     * consulta FALLA.
     *
     * <p>Se acota a las tablas cuyo `municipalidad_id` es NOT NULL, y no es una excepcion
     * comoda: las tres tablas de valuacion son NACIONALES (ADR-0017), su `municipalidad_id` es
     * nulo y su politica empieza por `municipalidad_id IS NULL`, que devuelve cierto sin llegar
     * a `current_setting` — un cuadro del MEF tiene que verse desde cualquier contexto,
     * incluido el del proceso de carga, que no tiene ninguno. Exigirles la forma estricta seria
     * medir el aislamiento sobre la unica clase de tabla que por diseno no aisla.
     */
    static int g3(Connection c) throws SQLException {
        return di("politica de tabla DE TENANT con valor por omision", lista(c, """
            SELECT c.relname || '.' || p.polname
              FROM pg_policy p
              JOIN pg_class c ON c.oid = p.polrelid
              JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
              JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname='municipalidad_id'
             WHERE a.attnotnull
               AND (COALESCE(pg_get_expr(p.polqual, p.polrelid), '') LIKE '%current_setting%true%'
                 OR COALESCE(pg_get_expr(p.polwithcheck, p.polrelid), '') LIKE '%current_setting%true%')
             ORDER BY 1"""));
    }

    /**
     * 3b. Y el contraste, que es lo que impide que la 3 pase por estar mal escrita: las tablas
     * de tenant SI usan la forma estricta. Si alguien "arreglara" la 3 ensanchando su excepcion,
     * esta se pondria roja.
     */
    static int g3b(Connection c) throws SQLException {
        List<String> sinPolitica = lista(c, """
            SELECT c.relname FROM pg_class c
              JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
              JOIN pg_attribute a ON a.attrelid = c.oid AND a.attname='municipalidad_id'
             WHERE c.relkind IN ('r','p') AND a.attnotnull
               AND NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid
                    AND pg_get_expr(p.polqual, p.polrelid) LIKE '%current_setting(''app.municipalidad_id''::text)%')
             ORDER BY 1""");
        return di("tabla de tenant SIN una politica que exija el contexto", sinPolitica);
    }

    /** 4. El rol de la aplicacion NO tiene ningun privilegio sobre ninguna particion. */
    static int g4(Connection c) throws SQLException {
        return di("privilegio de sgtm_app sobre una PARTICION", lista(c, """
            SELECT tp.table_name || ' ' || tp.privilege_type
              FROM information_schema.table_privileges tp
              JOIN pg_class c ON c.relname = tp.table_name
              JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
              JOIN pg_inherits h ON h.inhrelid = c.oid
             WHERE tp.table_schema='public' AND tp.grantee='sgtm_app'
             ORDER BY 1"""));
    }

    static void cuenta(Connection c) throws SQLException {
        System.out.println("  tablas: " + lista(c, """
            SELECT count(*)::text FROM pg_class c
              JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
             WHERE c.relkind IN ('r','p') AND c.relname <> 'flyway_schema_history'""").get(0)
            + "   con RLS: " + lista(c, """
            SELECT count(*)::text FROM pg_class c
              JOIN pg_namespace n ON n.oid=c.relnamespace AND n.nspname='public'
             WHERE c.relkind IN ('r','p') AND c.relrowsecurity""").get(0)
            + "   politicas: " + lista(c, "SELECT count(*)::text FROM pg_policy").get(0)
            + "   disparadores: " + lista(c, """
            SELECT count(*)::text FROM pg_trigger WHERE NOT tgisinternal""").get(0));
    }

    static int di(String que, List<String> malas) {
        if (malas.isEmpty()) { System.out.println("  OK   ninguna " + que); return 0; }
        System.out.println("  ROJO " + malas.size() + " " + que + ":");
        for (String m : malas) System.out.println("         " + m);
        return 1;
    }

    static List<String> lista(Connection c, String sql) throws SQLException {
        List<String> r = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) r.add(rs.getString(1));
        }
        return r;
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
