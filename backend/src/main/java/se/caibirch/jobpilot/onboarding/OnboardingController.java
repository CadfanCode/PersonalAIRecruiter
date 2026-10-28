package se.caibirch.jobpilot.onboarding;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/onboarding")
public class OnboardingController {

    public record ModeInput(String mode) {
    }

    private final OnboardingService service;

    public OnboardingController(OnboardingService service) {
        this.service = service;
    }

    @GetMapping
    public OnboardingService.State state() {
        return service.state();
    }

    @PutMapping("/mode")
    public OnboardingService.State mode(@RequestBody ModeInput input) {
        return service.chooseMode(input.mode());
    }

    @PostMapping("/complete")
    public OnboardingService.State complete() {
        return service.complete();
    }

    @PostMapping("/reopen")
    public OnboardingService.State reopen() {
        return service.reopen();
    }
}
