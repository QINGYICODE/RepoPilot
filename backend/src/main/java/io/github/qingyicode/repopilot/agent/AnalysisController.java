package io.github.qingyicode.repopilot.agent;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analysis-runs")
public class AnalysisController {
    private final AnalysisService analyses;
    public AnalysisController(AnalysisService analyses) {this.analyses=analyses;}
    @PostMapping @ResponseStatus(HttpStatus.ACCEPTED)
    public AnalysisData.Result submit(@RequestBody AnalysisData.Request request) {return analyses.submit(request);}
    @GetMapping("/{id}")
    public AnalysisData.Result get(@PathVariable String id) {return analyses.get(id);}
}
