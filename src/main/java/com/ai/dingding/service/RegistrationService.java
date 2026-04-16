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
 *                                  meeting_approved INTEGER DEFAULT 0)
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
        String sql = """
                CREATE TABLE IF NOT EXISTS registrations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE,
                    phone TEXT NOT NULL,
                    ding_approved INTEGER DEFAULT 0,
                    meeting_approved INTEGER DEFAULT 0
                )
                """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(sql);
        }
        log.info("SQLite 内存数据库初始化完成");
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
        String sql = "INSERT INTO registrations (name, phone) VALUES (?, ?) " +
                     "ON CONFLICT(name) DO UPDATE SET phone=excluded.phone";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String[] u : users) {
                ps.setString(1, u[0].trim());
                ps.setString(2, u[1].trim());
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
        String sql = name != null && !name.isBlank()
                ? "SELECT id, name, phone, ding_approved, meeting_approved FROM registrations WHERE " + approvedCol + " = 0 AND name LIKE ?"
                : "SELECT id, name, phone, ding_approved, meeting_approved FROM registrations WHERE " + approvedCol + " = 0";

        List<JSONObject> results = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (name != null && !name.isBlank()) {
                ps.setString(1, "%" + name.trim() + "%");
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JSONObject row = new JSONObject();
                    row.put("id", rs.getInt("id"));
                    row.put("name", rs.getString("name"));
                    row.put("phone", rs.getString("phone"));
                    row.put("dingApproved", rs.getInt("ding_approved") == 1);
                    row.put("meetingApproved", rs.getInt("meeting_approved") == 1);
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
        List<String> sets = new ArrayList<>();
        if (dingApproved != null) sets.add("ding_approved = ?");
        if (meetingApproved != null) sets.add("meeting_approved = ?");
        if (sets.isEmpty()) return 0;

        String sql = "UPDATE registrations SET " + String.join(", ", sets) + " WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            if (dingApproved != null) ps.setBoolean(idx++, dingApproved);
            if (meetingApproved != null) ps.setBoolean(idx++, meetingApproved);
            ps.setString(idx, name.trim());
            return ps.executeUpdate();
        }
    }

    /**
     * 查询存在未审批项的用户（钉钉群或会议录播有任意一个未审批）。
     *
     * @return 姓名列表
     */
    public synchronized List<String> findPendingAny() throws SQLException {
        String sql = "SELECT name FROM registrations WHERE ding_approved = 0 OR meeting_approved = 0 ORDER BY name";
        List<String> names = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                names.add(rs.getString("name"));
            }
        }
        return names;
    }

    /**
     * 查询全部报名记录。
     */
    public synchronized List<JSONObject> findAll() throws SQLException {
        String sql = "SELECT id, name, phone, ding_approved, meeting_approved FROM registrations ORDER BY id DESC";
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
