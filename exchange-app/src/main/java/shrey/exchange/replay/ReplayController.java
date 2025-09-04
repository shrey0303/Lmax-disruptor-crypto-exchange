package shrey.exchange.replay;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/replay")
@RequiredArgsConstructor
public class ReplayController {

    private final EventReplayService replayService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> triggerReplay(
            @RequestParam(defaultValue = "0") long fromOffset,
            @RequestParam(defaultValue = "1000") long toOffset) {
            
        try {
            Map<String, Object> result = replayService.replay(fromOffset, toOffset);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
