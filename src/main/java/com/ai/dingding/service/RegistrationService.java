package com.ai.dingding.service;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import shade.com.alibaba.fastjson2.JSONObject;

import jakarta.annotation.PreDestroy;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 SQLite 文件数据库的报名信息管理服务。
 * 数据持久化到 ./data/registrations.db，重启不丢失。
 * 表结构：registrations (id INTEGER PRIMARY KEY, name TEXT UNIQUE, phone TEXT,
 *                                  ding_approved INTEGER DEFAULT 0,
 *                                  meeting_approved INTEGER DEFAULT 0,
 *                                  created_at TEXT, updated_at TEXT)
 */
@Log4j2
@Service
public class RegistrationService {

    private static final String DB_PATH = "./data/registrations.db";
    private static final String DB_URL = "jdbc:sqlite:" + DB_PATH;

    /** 审批字段枚举 */
    public enum ApprovalField {
        DING("ding", "ding_approved"),
        MEETING("meeting", "meeting_approved");

        private final String param;
        private final String column;

        ApprovalField(String param, String column) {
            this.param = param;
            this.column = column;
        }

        public String getParam() { return param; }
        public String getColumn() { return column; }

        public static ApprovalField fromParam(String p) {
            for (ApprovalField f : values()) {
                if (f.param.equals(p)) return f;
            }
            return null;
        }
    }

    private final Connection conn;
    private int maxDingApprovalCount = 3;
    private int maxMeetingApprovalCount = 3;

    public void setMaxDingApprovalCount(int max) { this.maxDingApprovalCount = max; }
    public void setMaxMeetingApprovalCount(int max) { this.maxMeetingApprovalCount = max; }

    public RegistrationService() {
        try {
            // Ensure data directory exists before connecting
            java.nio.file.Path dataDir = java.nio.file.Paths.get("./data");
            if (!java.nio.file.Files.exists(dataDir)) {
                java.nio.file.Files.createDirectories(dataDir);
                log.info("已创建数据目录: {}", dataDir.toAbsolutePath());
            }
            this.conn = DriverManager.getConnection(DB_URL);
            this.conn.setAutoCommit(true);
            initTable();
        } catch (SQLException e) {
            throw new IllegalStateException("SQLite 初始化失败", e);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("无法创建数据目录 ./data", e);
        }
    }

    private void initTable() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE IF NOT EXISTS registrations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE,
                        phone TEXT NOT NULL,
                        ding_approved INTEGER DEFAULT 0,
                        meeting_approved INTEGER DEFAULT 0,
                        ding_approval_count INTEGER DEFAULT 0,
                        meeting_approval_count INTEGER DEFAULT 0,
                        created_at TEXT,
                        updated_at TEXT
                    )
                    """);
            // 兼容旧表：若列不存在则添加
            try { stmt.execute("ALTER TABLE registrations ADD COLUMN created_at TEXT"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE registrations ADD COLUMN updated_at TEXT"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE registrations ADD COLUMN ding_approval_count INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
            try { stmt.execute("ALTER TABLE registrations ADD COLUMN meeting_approval_count INTEGER DEFAULT 0"); } catch (SQLException ignored) {}
        }
        log.info("SQLite 数据库初始化完成");
    }

    /**
     * 批量插入报名记录，若用户名已存在则更新手机号。
     * 使用 INSERT ON CONFLICT DO UPDATE 减少 DB 往返。
     *
     * @param users 每个元素为 [name, phone]
     * @return 实际新增行数
     */
    public synchronized int batchInsert(List<String[]> users) throws SQLException {
        int inserted = 0;
        String now = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String sql = "INSERT INTO registrations (name, phone, created_at, updated_at) VALUES (?, ?, ?, ?) " +
                     "ON CONFLICT(name) DO UPDATE SET phone=excluded.phone, updated_at=excluded.updated_at";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String[] u : users) {
                ps.setString(1, u[0].trim());
                ps.setString(2, u[1].trim());
                ps.setString(3, now);
                ps.setString(4, now);
                ps.addBatch();
            }
            int[] results = ps.executeBatch();
            for (int r : results) {
                if (r > 0) inserted += r;
            }
        }
        return inserted;
    }

    /**
     * 按用户名模糊查询未审批用户。
     *
     * @param field 审批字段：ding=钉钉群审批，meeting=腾讯会议录播审批
     * @param name  用户名（模糊匹配），null 或空串表示不限
     * @return 未审批用户列表
     */
    public synchronized List<JSONObject> findUnapproved(ApprovalField field, String name) throws SQLException {
        String approvedCol = field.getColumn();
        if (!approvedCol.equals("ding_approved") && !approvedCol.equals("meeting_approved")) {
            throw new IllegalArgumentException("非法的审批字段: " + approvedCol);
        }
        String base = "SELECT id, name, phone, ding_approved, meeting_approved, ding_approval_count, meeting_approval_count, created_at, updated_at FROM registrations WHERE "
                + approvedCol + " = 0 AND ding_approval_count <= ? AND meeting_approval_count <= ?";
        String sql = name != null && !name.isBlank() ? base + " AND name LIKE ?" : base;

        List<JSONObject> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, maxDingApprovalCount);
            ps.setInt(2, maxMeetingApprovalCount);
            if (name != null && !name.isBlank()) {
                ps.setString(3, "%" + name.trim() + "%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject row = new JSONObject();
                    row.put("id", rs.getInt("id"));
                    row.put("name", rs.getString("name"));
                    row.put("phone", rs.getString("phone"));
                    row.put("dingApproved", rs.getInt("ding_approved") == 1);
                    row.put("meetingApproved", rs.getInt("meeting_approved") == 1);
                    row.put("dingApprovalCount", rs.getInt("ding_approval_count"));
                    row.put("meetingApprovalCount", rs.getInt("meeting_approval_count"));
                    row.put("createdAt", rs.getString("created_at"));
                    row.put("updatedAt", rs.getString("updated_at"));
                    results.add(row);
                }
            }
        }
        return results;
    }

    /**
     * 按用户名更新审批状态。
     *
     * @param name             用户名（精确匹配）
     * @param dingApproved      钉钉群审批状态，null 表示不更新
     * @param meetingApproved   腾讯会议录播审批状态，null 表示不更新
     * @return 实际更新的行数
     */
    public synchronized int updateApprovalByName(String name, Boolean dingApproved, Boolean meetingApproved) throws SQLException {
        if (dingApproved == null && meetingApproved == null) return 0;

        List<String> sets = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (dingApproved != null) {
            // 只有计数达到最大值时才标记为已审批
            sets.add("ding_approved = CASE WHEN ding_approval_count + 1 >= ? THEN 1 ELSE ding_approved END");
            params.add(maxDingApprovalCount);
            sets.add("ding_approval_count = ding_approval_count + 1");
        }
        if (meetingApproved != null) {
            sets.add("meeting_approved = CASE WHEN meeting_approval_count + 1 >= ? THEN 1 ELSE meeting_approved END");
            params.add(maxMeetingApprovalCount);
            sets.add("meeting_approval_count = meeting_approval_count + 1");
        }

        sets.add("updated_at = ?");
        params.add(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        params.add(name.trim());

        String sql = "UPDATE registrations SET " + String.join(", ", sets) + " WHERE phone = ?";
        log.info("updateApprovalByName SQL: {} | params: {}", sql, params);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                Object p = params.get(i);
                if (p instanceof Integer) ps.setInt(i + 1, (Integer) p);
                else ps.setString(i + 1, (String) p);
            }
            return ps.executeUpdate();
        }
    }

    /**
     * 查询存在未审批项的用户（钉钉群或会议录播有任意一个未审批）。
     *
     * @return 姓名列表
     */
    public synchronized List<String> findPendingAny() throws SQLException {
        String sql = "SELECT phone FROM registrations WHERE (ding_approved = 0 OR meeting_approved = 0) ORDER BY name";
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("phone"));
                }
            }
        }
        return names;
    }

    /**
     * 查询全部报名记录。
     */
    public synchronized List<JSONObject> findAll() throws SQLException {
        String sql = "SELECT id, name, phone, ding_approved, meeting_approved, ding_approval_count, meeting_approval_count, created_at, updated_at FROM registrations ORDER BY id DESC";
        List<JSONObject> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("id", rs.getInt("id"));
                row.put("name", rs.getString("name"));
                row.put("phone", rs.getString("phone"));
                row.put("dingApproved", rs.getInt("ding_approved") == 1);
                row.put("meetingApproved", rs.getInt("meeting_approved") == 1);
                row.put("dingApprovalCount", rs.getInt("ding_approval_count"));
                row.put("meetingApprovalCount", rs.getInt("meeting_approval_count"));
                row.put("createdAt", rs.getString("created_at"));
                row.put("updatedAt", rs.getString("updated_at"));
                results.add(row);
            }
        }
        return results;
    }

    /** 应用关闭时清理资源 */
    @PreDestroy
    public void close() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
                log.info("SQLite 连接已关闭");
            }
        } catch (SQLException e) {
            log.error("关闭 SQLite 连接异常", e);
        }
    }
}
