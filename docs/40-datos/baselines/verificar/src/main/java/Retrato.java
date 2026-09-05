import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Vuelca un retrato EXHAUSTIVO del catalogo, restringido a un conjunto de tablas.
 *
 * <p>Es el comparador, y es independiente del emisor a proposito: si el emisor olvida un
 * privilegio de columna, un disparador o una politica, el retrato del lado que lo tiene y el del
 * que no difieren. Mira TODO lo que el catalogo sabe de una tabla, no solo lo que el emisor emite.
 */
public final class Retrato {
    public static void main(String[] a) throws Exception {
        String base = a[0], salida = a[1];
        Set<String> tablas = new TreeSet<>(Arrays.asList(a[2].split(",")));
        StringBuilder o = new StringBuilder();
        try (Connection c = DriverManager.getConnection(
                aBase(base), usuario(), clave())) {
            dominios(c, o);
            for (String t : tablas) tabla(c, o, t);
            funciones(c, o, tablas);
        }
        Files.writeString(Path.of(salida), o.toString());
        System.out.println("retrato de " + base + ": " + tablas.size() + " tablas, "
                + o.toString().lines().count() + " lineas -> " + salida);
    }

    /** Los dominios de tipo, con su base y sus CHECK. Se pierden en un volcado descuidado. */
    static void dominios(Connection c, StringBuilder o) throws SQLException {
        q(c, o, "DOMINIO", """
            SELECT t.typname || ' ' || pg_catalog.format_type(t.typbasetype, t.typtypmod)
                   || CASE WHEN t.typnotnull THEN ' NOT NULL' ELSE '' END
                   || COALESCE(' DEFAULT ' || t.typdefault, '')
                   || COALESCE(' | ' || string_agg(pg_get_constraintdef(k.oid), ' ' ORDER BY k.conname), '')
              FROM pg_type t
              JOIN pg_namespace n ON n.oid = t.typnamespace AND n.nspname = 'public'
              LEFT JOIN pg_constraint k ON k.contypid = t.oid
             WHERE t.typtype = 'd'
             GROUP BY t.oid, t.typname, t.typbasetype, t.typtypmod, t.typnotnull, t.typdefault
             ORDER BY t.typname""");
    }

    static void tabla(Connection c, StringBuilder o, String t) throws SQLException {
        o.append("\n########## TABLA ").append(t).append("\n");
        p(c, o, "  EXISTE", """
            SELECT c.relkind::text || ' rls=' || c.relrowsecurity || ' force=' || c.relforcerowsecurity
                   || COALESCE(' particionada_por=' || pg_get_partkeydef(c.oid), '')
                   || COALESCE(' particion_de=' || (SELECT p.relname FROM pg_inherits i
                        JOIN pg_class p ON p.oid = i.inhparent WHERE i.inhrelid = c.oid), '')
                   || COALESCE(' limites=' || pg_get_expr(c.relpartbound, c.oid), '')
              FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname='public' AND c.relname = ?""", t);
        p(c, o, "  COLUMNA", """
            SELECT row_number() OVER (ORDER BY a.attnum) || ' ' || a.attname || ' '
                   || pg_catalog.format_type(a.atttypid, a.atttypmod)
                   || CASE WHEN a.attnotnull THEN ' NOT NULL' ELSE '' END
                   || COALESCE(' DEFAULT ' || pg_get_expr(d.adbin, d.adrelid), '')
                   || CASE a.attgenerated WHEN 's' THEN ' GENERATED_STORED' ELSE '' END
                   || CASE a.attidentity WHEN 'a' THEN ' IDENTITY_ALWAYS'
                                         WHEN 'd' THEN ' IDENTITY_DEFAULT' ELSE '' END
              FROM pg_attribute a
              JOIN pg_class c ON c.oid = a.attrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname='public'
              LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
             WHERE c.relname = ? AND a.attnum > 0 AND NOT a.attisdropped
             ORDER BY a.attnum""", t);
        p(c, o, "  RESTRICCION", """
            SELECT k.conname || ' ' || pg_get_constraintdef(k.oid)
                   || CASE WHEN k.convalidated THEN '' ELSE ' [NOT VALID]' END
              FROM pg_constraint k
              JOIN pg_class c ON c.oid = k.conrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname='public'
             WHERE c.relname = ? ORDER BY k.conname""", t);
        p(c, o, "  INDICE", """
            SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename = ?
             ORDER BY indexname""", t);
        p(c, o, "  POLITICA", """
            SELECT pol.polname || ' cmd=' || pol.polcmd::text
                   || ' roles=' || COALESCE((SELECT string_agg(r.rolname, '+' ORDER BY r.rolname)
                        FROM pg_roles r WHERE r.oid = ANY(pol.polroles)), 'PUBLIC')
                   || ' using=' || COALESCE(pg_get_expr(pol.polqual, pol.polrelid), '-')
                   || ' check=' || COALESCE(pg_get_expr(pol.polwithcheck, pol.polrelid), '-')
              FROM pg_policy pol
              JOIN pg_class c ON c.oid = pol.polrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname='public'
             WHERE c.relname = ? ORDER BY pol.polname""", t);
        p(c, o, "  PRIVILEGIO_TABLA", """
            SELECT grantee || ' ' || privilege_type FROM information_schema.table_privileges
             WHERE table_schema='public' AND table_name = ? ORDER BY grantee, privilege_type""", t);
        p(c, o, "  PRIVILEGIO_COLUMNA", """
            SELECT grantee || ' ' || column_name || ' ' || privilege_type
              FROM information_schema.column_privileges
             WHERE table_schema='public' AND table_name = ?
               AND grantee <> (SELECT tableowner FROM pg_tables
                                WHERE schemaname='public' AND tablename = ?)
             ORDER BY grantee, column_name, privilege_type""", t, t);
        p(c, o, "  DISPARADOR", """
            SELECT tg.tgname || ' ' || pg_get_triggerdef(tg.oid)
              FROM pg_trigger tg
              JOIN pg_class c ON c.oid = tg.tgrelid
              JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname='public'
             WHERE c.relname = ? AND NOT tg.tgisinternal ORDER BY tg.tgname""", t);
        p(c, o, "  COMENTARIO", """
            SELECT COALESCE(a.attname, '<tabla>') || ': '
                   || md5(COALESCE(d.description, ''))
              FROM pg_description d
              JOIN pg_class c ON c.oid = d.objoid
              JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname='public'
              LEFT JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.objsubid
             WHERE c.relname = ? ORDER BY 1""", t);
    }

    /** Las funciones de los disparadores: sin ellas, un disparador emitido no hace nada. */
    static void funciones(Connection c, StringBuilder o, Set<String> tablas) throws SQLException {
        o.append("\n########## FUNCIONES DE DISPARADOR\n");
        try (PreparedStatement st = c.prepareStatement("""
                SELECT DISTINCT p.proname || ' | ' || md5(p.prosrc)
                  FROM pg_trigger tg
                  JOIN pg_class c ON c.oid = tg.tgrelid
                  JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname='public'
                  JOIN pg_proc p ON p.oid = tg.tgfoid
                 WHERE NOT tg.tgisinternal AND c.relname = ANY(?)
                 ORDER BY 1""")) {
            st.setArray(1, c.createArrayOf("text", tablas.toArray()));
            try (ResultSet r = st.executeQuery()) {
                while (r.next()) o.append("  FUNCION ").append(r.getString(1)).append('\n');
            }
        }
    }

    static void q(Connection c, StringBuilder o, String et, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet r = s.executeQuery(sql)) {
            while (r.next()) o.append(et).append(' ').append(r.getString(1)).append('\n');
        }
    }

    static void p(Connection c, StringBuilder o, String et, String sql, String... args)
            throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setString(i + 1, args[i]);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) o.append(et).append(' ').append(r.getString(1)).append('\n');
            }
        }
    }
    /** La URL del motor, del entorno: el arnes no fija ningun puerto (INF-01 §4.1). */
    static String base() {
        String u = System.getenv("KAMAYUK_BASELINE_URL");
        if (u == null || u.isBlank()) {
            throw new IllegalStateException(
                    "Falta KAMAYUK_BASELINE_URL, p.ej. jdbc:postgresql://localhost:5432/postgres");
        }
        return u;
    }

    static String usuario() {
        return System.getenv().getOrDefault("KAMAYUK_BASELINE_USUARIO", "postgres");
    }

    static String clave() {
        return System.getenv().getOrDefault("KAMAYUK_BASELINE_CLAVE", "postgres");
    }

    static String admin() { return base(); }

    /** La misma URL, apuntando a otra base del mismo cluster. */
    static String aBase(String nombre) {
        return base().replaceFirst("/[^/?]+(\\?.*)?$", "/" + nombre);
    }

}
