package com.finora.api.forecast;

import com.finora.api.forecast.DueEventDtos.DueEventsResponse;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/events")
public class DueEventController {

    private final DueEventService service;

    public DueEventController(DueEventService service) {
        this.service = service;
    }

    @GetMapping("/due")
    public DueEventsResponse due(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.events(from, to);
    }
}
