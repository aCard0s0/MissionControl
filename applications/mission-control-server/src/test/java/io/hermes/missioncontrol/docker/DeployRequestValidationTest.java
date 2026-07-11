package io.hermes.missioncontrol.docker;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
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
}
