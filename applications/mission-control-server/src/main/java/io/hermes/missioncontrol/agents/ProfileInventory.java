package io.hermes.missioncontrol.agents;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Which profiles exist inside one container.
 *
 * <p>Its own component because two callers need the same listing for unrelated reasons:
 * {@link HermesProfiles} builds the agent inventory from it, and {@link HermesWebhooks} has to
 * know what the <em>other</em> profiles' webhook listeners already bound before it can hand one
 * a port that is free.
 */
@Component
class ProfileInventory {

  private final HermesContainerFiles files;

  ProfileInventory(HermesContainerFiles files) {
    this.files = files;
  }

  /**
   * The container's profile names, {@code default} first when the hermes home is initialized.
   *
   * <p>Names that could not be a profile are dropped rather than returned: this list is
   * concatenated into container paths, and {@code ls} answers with whatever is in the
   * directory.
   */
  List<String> names(String url, String containerId) {
    List<String> names = new ArrayList<>();
    if (files.dirExists(url, containerId, ProfilePaths.HERMES_HOME)) {
      names.add("default");
    }
    var ls = files.exec(url, containerId, List.of(
        "sh", "-lc", "ls -1 " + ProfilePaths.PROFILES_DIR + " 2>/dev/null || true"));
    for (String name : HermesContainerFiles.lines(ls.stdout())) {
      if ("default".equals(name)) continue;
      if (ProfilePaths.isValidName(name)) names.add(name);
    }
    return names;
  }
}
