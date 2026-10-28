package uz.eduping;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;

@RestController
public class HealthController {
    private final JdbcTemplate db;
    public HealthController(JdbcTemplate db) { this.db = db; }
    @GetMapping("/health/live") public Map<String,String> live() { return Map.of("status", "UP"); }
    @GetMapping("/health/ready") public ResponseEntity<Map<String,String>> ready() {
        try { db.queryForObject("SELECT 1", Integer.class); return ResponseEntity.ok(Map.of("status", "UP")); }
        catch (Exception e) { return ResponseEntity.status(503).body(Map.of("status", "DOWN")); }
    }
}
