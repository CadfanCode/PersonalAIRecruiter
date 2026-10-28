package se.caibirch.jobpilot.country;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/country")
public class CountryController {

    public record View(String selected, boolean chosen, boolean activityReport, List<Country> countries) {
    }

    public record Choice(String code) {
    }

    private final CountryRegistry registry;

    public CountryController(CountryRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    public View get() {
        Country selected = registry.selected();
        return new View(selected.code(), registry.isChosen(), selected.activityReport(), registry.countries());
    }

    @PutMapping
    public View choose(@RequestBody Choice choice) {
        registry.select(choice.code());
        return get();
    }
}
