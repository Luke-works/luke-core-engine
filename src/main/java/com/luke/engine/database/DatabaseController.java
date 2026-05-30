package com.luke.engine.database;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@RestController
@RequestMapping("/api/database")
public class DatabaseController {

    @Autowired
    private DataSource dataSource;

    private static final String TENANT_COLUMN = "tenant_id_";

    /**
     * Check if a table has a tenant_id_ column.
     */
    private boolean hasTenantColumn(Connection conn, String tableName) throws SQLException {
        DatabaseMetaData meta = conn.getMetaData();
        try (ResultSet rs = meta.getColumns(null, "public", tableName, TENANT_COLUMN)) {
            return rs.next();
        }
    }

    @GetMapping("/tables")
    public ResponseEntity<List<Map<String, Object>>> getTables(
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) throws SQLException {
        List<Map<String, Object>> tables = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getTables(null, "public", "%", new String[]{"TABLE"})) {
                while (rs.next()) {
                    String name = rs.getString("TABLE_NAME");
                    Map<String, Object> table = new LinkedHashMap<>();
                    table.put("name", name);
                    table.put("type", rs.getString("TABLE_TYPE"));
                    table.put("schema", rs.getString("TABLE_SCHEM"));
                    table.put("tenantAware", hasTenantColumn(conn, name));
                    tables.add(table);
                }
            }
        }
        return ResponseEntity.ok(tables);
    }

    @GetMapping("/tables/{tableName}/columns")
    public ResponseEntity<List<Map<String, Object>>> getColumns(@PathVariable String tableName) throws SQLException {
        List<Map<String, Object>> columns = new ArrayList<>();
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(null, "public", tableName, "%")) {
                while (rs.next()) {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("name", rs.getString("COLUMN_NAME"));
                    col.put("type", rs.getString("TYPE_NAME"));
                    col.put("size", rs.getInt("COLUMN_SIZE"));
                    col.put("nullable", rs.getInt("NULLABLE") == DatabaseMetaData.columnNullable);
                    col.put("position", rs.getInt("ORDINAL_POSITION"));
                    columns.add(col);
                }
            }
        }
        return ResponseEntity.ok(columns);
    }

    @GetMapping("/tables/{tableName}/count")
    public ResponseEntity<Map<String, Object>> getTableRowCount(
            @PathVariable String tableName,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) throws SQLException {
        if (!tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid table name"));
        }
        try (Connection conn = dataSource.getConnection()) {
            boolean tenantAware = tenantId != null && !tenantId.isBlank() && hasTenantColumn(conn, tableName);
            String sql = "SELECT COUNT(*) as count FROM \"" + tableName + "\"";
            if (tenantAware) {
                sql += " WHERE \"" + TENANT_COLUMN + "\" = ?";
            }
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (tenantAware) {
                    ps.setString(1, tenantId);
                }
                ResultSet rs = ps.executeQuery();
                rs.next();
                return ResponseEntity.ok(Map.of("table", tableName, "count", rs.getLong("count"), "filtered", tenantAware));
            }
        }
    }

    @GetMapping("/tables/{tableName}/data")
    public ResponseEntity<Map<String, Object>> getTableData(
            @PathVariable String tableName,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) throws SQLException {
        if (!tableName.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid table name"));
        }
        if (limit > 500) limit = 500;

        try (Connection conn = dataSource.getConnection()) {
            boolean tenantAware = tenantId != null && !tenantId.isBlank() && hasTenantColumn(conn, tableName);
            String whereClause = tenantAware ? " WHERE \"" + TENANT_COLUMN + "\" = ?" : "";

            // Count
            try (PreparedStatement countPs = conn.prepareStatement(
                    "SELECT COUNT(*) FROM \"" + tableName + "\"" + whereClause)) {
                if (tenantAware) countPs.setString(1, tenantId);
                ResultSet countRs = countPs.executeQuery();
                countRs.next();
                long total = countRs.getLong(1);

                // Data
                try (PreparedStatement dataPs = conn.prepareStatement(
                        "SELECT * FROM \"" + tableName + "\"" + whereClause + " LIMIT " + limit + " OFFSET " + offset)) {
                    if (tenantAware) dataPs.setString(1, tenantId);
                    ResultSet rs = dataPs.executeQuery();
                    ResultSetMetaData rsMeta = rs.getMetaData();
                    int colCount = rsMeta.getColumnCount();

                    List<String> columnNames = new ArrayList<>();
                    for (int i = 1; i <= colCount; i++) {
                        columnNames.add(rsMeta.getColumnName(i));
                    }

                    List<Map<String, Object>> rows = new ArrayList<>();
                    while (rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= colCount; i++) {
                            Object val = rs.getObject(i);
                            row.put(rsMeta.getColumnName(i), val != null ? val.toString() : null);
                        }
                        rows.add(row);
                    }

                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("columns", columnNames);
                    result.put("rows", rows);
                    result.put("total", total);
                    result.put("offset", offset);
                    result.put("limit", limit);
                    result.put("filtered", tenantAware);
                    return ResponseEntity.ok(result);
                }
            }
        }
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> executeQuery(@RequestBody Map<String, String> body) {
        String sql = body.get("sql");
        if (sql == null || sql.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "SQL query is required"));
        }

        String trimmed = sql.trim().toUpperCase();
        if (!trimmed.startsWith("SELECT") && !trimmed.startsWith("EXPLAIN") && !trimmed.startsWith("SHOW")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only SELECT, EXPLAIN, and SHOW queries are allowed"));
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.setMaxRows(500);
            stmt.setQueryTimeout(30);

            long start = System.currentTimeMillis();
            ResultSet rs = stmt.executeQuery(sql);
            long duration = System.currentTimeMillis() - start;

            ResultSetMetaData rsMeta = rs.getMetaData();
            int colCount = rsMeta.getColumnCount();

            List<String> columnNames = new ArrayList<>();
            for (int i = 1; i <= colCount; i++) {
                columnNames.add(rsMeta.getColumnName(i));
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= colCount; i++) {
                    Object val = rs.getObject(i);
                    row.put(rsMeta.getColumnName(i), val != null ? val.toString() : null);
                }
                rows.add(row);
            }
            rs.close();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("columns", columnNames);
            result.put("rows", rows);
            result.put("rowCount", rows.size());
            result.put("durationMs", duration);
            return ResponseEntity.ok(result);

        } catch (SQLException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "sqlState", e.getSQLState() != null ? e.getSQLState() : "unknown"
            ));
        }
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getDatabaseInfo() throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            DatabaseMetaData meta = conn.getMetaData();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("product", meta.getDatabaseProductName());
            info.put("version", meta.getDatabaseProductVersion());
            info.put("driver", meta.getDriverName());
            info.put("driverVersion", meta.getDriverVersion());
            info.put("url", meta.getURL());
            info.put("username", meta.getUserName());
            info.put("maxConnections", meta.getMaxConnections());
            return ResponseEntity.ok(info);
        }
    }
}
