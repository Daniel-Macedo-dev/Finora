package com.finora.api.forecast;

import com.finora.api.forecast.ForecastDtos.ForecastResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/forecast")
public class ForecastController {

    private final ForecastService service;

    public ForecastController(ForecastService service) {
        this.service = service;
    }

    @GetMapping
    public ForecastResponse forecast(@RequestParam(required = false) Integer days,
                                     @RequestParam(required = false) Long accountId) {
        return service.forecast(days, accountId);
    }
}
