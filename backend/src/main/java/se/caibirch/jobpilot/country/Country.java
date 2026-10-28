package se.caibirch.jobpilot.country;

/**
 * @param market          how prompts describe the job market, for example "the Swedish job market (Platsbanken ...)"
 * @param activityReport  whether the country has a public activity report that JobPilot can fill in (Sweden only so far)
 * @param available       false for countries shown as "coming soon": no job source is implemented yet
 */
public record Country(String code, String name, String jobBoard, String market, boolean activityReport, boolean available) {
}
