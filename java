package com.habittracker;

import java.io.*;
import java.sql.*;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import javax.servlet.annotation.*;
import org.json.JSONObject;
import org.json.JSONArray;

@WebServlet("/api/habits")
public class HabitServlet extends HttpServlet {
    private Connection connection;
    
    @Override
    public void init() throws ServletException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String dbUrl = "jdbc:mysql://localhost:3306/habit_tracker";
            String dbUser = "your_username";
            String dbPassword = "your_password";
            connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
        } catch (Exception e) {
            throw new ServletException("Database connection failed", e);
        }
    }
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String userId = request.getParameter("user_id");
        String habitType = request.getParameter("type");
        
        try {
            JSONObject result = new JSONObject();
            
            if ("water".equals(habitType)) {
                result.put("water", getWaterData(userId));
            } else if ("steps".equals(habitType)) {
                result.put("steps", getStepsData(userId));
            } else if ("weight".equals(habitType)) {
                result.put("weight", getWeightData(userId));
            } else {
                result.put("habits", getAllHabits(userId));
                result.put("summary", getDailySummary(userId));
            }
            
            response.getWriter().write(result.toString());
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) 
            throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        try {
            StringBuilder sb = new StringBuilder();
            String line;
            BufferedReader reader = request.getReader();
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            
            JSONObject data = new JSONObject(sb.toString());
            String type = data.getString("type");
            
            JSONObject result = new JSONObject();
            switch (type) {
                case "water":
                    addWaterEntry(data);
                    result.put("message", "Water intake recorded successfully");
                    break;
                case "steps":
                    addStepsEntry(data);
                    result.put("message", "Steps recorded successfully");
                    break;
                case "weight":
                    addWeightEntry(data);
                    result.put("message", "Weight recorded successfully");
                    break;
                case "habit":
                    addHabit(data);
                    result.put("message", "Habit added successfully");
                    break;
                default:
                    throw new Exception("Unknown entry type");
            }
            
            response.getWriter().write(result.toString());
        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.getWriter().write("{\"error\": \"" + e.getMessage() + "\"}");
        }
    }
    
    private JSONArray getWaterData(String userId) throws SQLException {
        JSONArray waterData = new JSONArray();
        String sql = "SELECT DATE(entry_date) as date, SUM(amount_ml) as total_ml " +
                     "FROM water_intake WHERE user_id = ? AND entry_date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) " +
                     "GROUP BY DATE(entry_date) ORDER BY date";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                JSONObject item = new JSONObject();
                item.put("date", rs.getString("date"));
                item.put("amount", rs.getInt("total_ml") / 1000.0); // Convert to liters
                waterData.put(item);
            }
        }
        return waterData;
    }
    
    private JSONArray getStepsData(String userId) throws SQLException {
        JSONArray stepsData = new JSONArray();
        // Assuming steps are stored in habit_entries with habit name "Steps"
        String sql = "SELECT DATE(entry_date) as date, value as steps " +
                     "FROM habit_entries he JOIN habits h ON he.habit_id = h.id " +
                     "WHERE h.user_id = ? AND h.name = 'Steps' AND entry_date >= DATE_SUB(CURDATE(), INTERVAL 7 DAY) " +
                     "ORDER BY date";
        
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();
            
            while (rs.next()) {
                JSONObject item = new JSONObject();
                item.put("date", rs.getString("date"));
                item.put("steps", rs.getInt("steps"));
                stepsData.put(item);
            }
        }
        return stepsData;
    }
    
    private JSONObject getDailySummary(String userId) throws SQLException {
        JSONObject summary = new JSONObject();
        
        // Get today's water intake
        String waterSql = "SELECT COALESCE(SUM(amount_ml), 0) as total_ml FROM water_intake " +
                          "WHERE user_id = ? AND entry_date = CURDATE()";
        try (PreparedStatement stmt = connection.prepareStatement(waterSql)) {
            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                summary.put("water", rs.getInt("total_ml") / 1000.0);
            }
        }
        
        // Get today's steps
        String stepsSql = "SELECT value as steps FROM habit_entries he JOIN habits h ON he.habit_id = h.id " +
                         "WHERE h.user_id = ? AND h.name = 'Steps' AND entry_date = CURDATE()";
        try (PreparedStatement stmt = connection.prepareStatement(stepsSql)) {
            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                summary.put("steps", rs.getInt("steps"));
            } else {
                summary.put("steps", 0);
            }
        }
        
        // Get current weight (most recent entry)
        String weightSql = "SELECT weight_kg FROM weight_entries " +
                           "WHERE user_id = ? ORDER BY entry_date DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(weightSql)) {
            stmt.setString(1, userId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                summary.put("weight", rs.getDouble("weight_kg"));
            }
        }
        
        return summary;
    }
    
    private void addWaterEntry(JSONObject data) throws SQLException {
        String sql = "INSERT INTO water_intake (user_id, amount_ml, entry_date, entry_time) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, data.getString("user_id"));
            stmt.setInt(2, data.getInt("amount_ml"));
            stmt.setDate(3, Date.valueOf(data.getString("date")));
            stmt.setTime(4, Time.valueOf(data.getString("time") + ":00"));
            stmt.executeUpdate();
        }
    }
    
    private void addStepsEntry(JSONObject data) throws SQLException {
        // First get or create the Steps habit
        String habitId = getOrCreateHabit(data.getString("user_id"), "Steps", "health", 10000, "steps");
        
        String sql = "INSERT INTO habit_entries (habit_id, value, entry_date) VALUES (?, ?, CURDATE()) " +
                     "ON DUPLICATE KEY UPDATE value = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, Integer.parseInt(habitId));
            stmt.setInt(2, data.getInt("steps"));
            stmt.setInt(3, data.getInt("steps"));
            stmt.executeUpdate();
        }
    }
    
    private void addWeightEntry(JSONObject data) throws SQLException {
        String sql = "INSERT INTO weight_entries (user_id, weight_kg, entry_date, notes) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, data.getString("user_id"));
            stmt.setDouble(2, data.getDouble("weight_kg"));
            stmt.setDate(3, Date.valueOf(data.getString("date")));
            stmt.setString(4, data.optString("notes", ""));
            stmt.executeUpdate();
        }
    }
    
    private String getOrCreateHabit(String userId, String name, String category, double goalValue, String goalUnit) 
            throws SQLException {
        String checkSql = "SELECT id FROM habits WHERE user_id = ? AND name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(checkSql)) {
            stmt.setString(1, userId);
            stmt.setString(2, name);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("id");
            }
        }
        
        String insertSql = "INSERT INTO habits (user_id, name, category, goal_value, goal_unit) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, userId);
            stmt.setString(2, name);
            stmt.setString(3, category);
            stmt.setDouble(4, goalValue);
            stmt.setString(5, goalUnit);
            stmt.executeUpdate();
            
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        return null;
    }
    
    @Override
    public void destroy() {
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException e) {
            // Log error
        }
    }
}