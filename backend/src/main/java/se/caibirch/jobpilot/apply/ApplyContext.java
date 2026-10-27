package se.caibirch.jobpilot.apply;

import java.nio.file.Path;
import java.util.Map;

import se.caibirch.jobpilot.cvdb.CvModel.Profile;

/**
 * What the validator may compare Claude's answer with.
 *
 * @param memory answers memory by normalised question key
 * @param files approved document PDFs by "cv" and "letter"; a document that is not approved is simply absent
 */
public record ApplyContext(Profile profile, Map<String, String> memory, Map<String, Path> files) {
}
