package com.finora.api.purchaseanalysis;

import com.finora.api.purchaseanalysis.PurchaseAnalysisDtos.AnalysisResponse;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wishlist/{id}/analysis")
public class PurchaseAnalysisController {

    private final PurchaseAnalysisService service;

    public PurchaseAnalysisController(PurchaseAnalysisService service) {
        this.service = service;
    }

    @GetMapping
    public AnalysisResponse analyze(@PathVariable Long id) {
        return service.analyze(id, LocalDate.now());
    }
}
