package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeployRequestValidationTest {

  @Test
  void seedProfilesUseHermesLowercaseNameRules() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertTrue(validator.validate(
          new DeployRequest("dh-local", "demo", "latest", List.of("default", "ops-team"))).isEmpty());
      assertFalse(validator.validate(
          new DeployRequest("dh-local", "demo", "latest", List.of("Bad.Name"))).isEmpty());
    }
  }

  @Test
  void aDeployTagIsValidatedTheSameWayAnUpdateTagIs() {
    // the same value reaches the same daemon as an update tag; unconstrained, a typo was
    // only caught by the daemon — after the managed volume had already been created
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();

      for (String rejected : List.of("bad tag!", "v1\nlatest", "t".repeat(200))) {
        var violations = validator.validate(
            new DeployRequest("dh-local", "demo", rejected, List.of("default")));
        assertEquals(1, violations.size(), "should have been rejected: " + rejected);
        var violation = violations.iterator().next();
        assertEquals("version", violation.getPropertyPath().toString());
        assertEquals("invalid image tag", violation.getMessage());
      }

      for (String accepted : List.of("v2026.8.3", "latest")) {
        assertTrue(validator.validate(
                new DeployRequest("dh-local", "demo", accepted, List.of("default"))).isEmpty(),
            "should have been accepted: " + accepted);
      }
    }
  }

  @Test
  void anAbsentOrBlankVersionStillMeansLatest() {
    // DockerGateway.deploy maps a blank version onto 'latest', so the new rule must leave
    // the unset case alone rather than forcing the caller to spell out a tag
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      for (String unset : Arrays.asList(null, "")) {
        assertTrue(validator.validate(
                new DeployRequest("dh-local", "demo", unset, List.of("default")))
                .stream()
                .noneMatch(v -> v.getPropertyPath().toString().equals("version")),
            "an unset version must still mean 'latest'");
      }
    }
  }
}
